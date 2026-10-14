package dev.essentialshare

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import dev.essentialshare.core.Crypto
import dev.essentialshare.core.Discovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** A phone that is advertising "touch to share" close to this one. */
class Nearby(val prefix: String, val host: String?, val port: Int, val rssi: Float) {
    /** 0 = far away, 1 = the phones are touching. */
    val closeness: Float get() = ((rssi - TouchRadar.FAR) / (TouchRadar.NEAR - TouchRadar.FAR)).coerceIn(0f, 1f)
}

/**
 * Touch to share, the "are these two phones touching" half. While the touch screen is open the phone advertises a
 * tiny Bluetooth LE packet (start of its device id, its address on the Wi-Fi, the port) and listens for the same
 * packets of other phones. A sudden strong signal means the phones are against each other: [touch] fires.
 * The packet is not trusted for anything but finding the other phone: the connection that follows is the normal
 * encrypted one, and an unknown phone still has to be confirmed with the code on both screens.
 */
@SuppressLint("MissingPermission")
class TouchRadar(private val ctx: Context, private val scope: CoroutineScope) {
    private class Entry(val prefix: String, var host: String?, var port: Int, var rssi: Float, var seenAt: Long) {
        var high = 0
        var lastTouch = 0L
    }

    private val entries = HashMap<String, Entry>()
    private val _nearby = MutableStateFlow<List<Nearby>>(emptyList())
    val nearby: StateFlow<List<Nearby>> = _nearby.asStateFlow()
    private val _touch = MutableSharedFlow<Nearby>(extraBufferCapacity = 4)
    val touch: SharedFlow<Nearby> = _touch.asSharedFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var running = false
    private var ticker: Job? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private var lastPayload: ByteArray? = null

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise failed: $errorCode"); _error.value = "advertise" }
        }
        advertiseCallback = cb
        val data = payload()
        lastPayload = data
        runCatching {
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false).setTimeout(0).build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false).addServiceUuid(SERVICE).build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false).addManufacturerData(COMPANY, data).build(),
                cb,
            )
        }.onFailure { Log.w(TAG, "advertise: $it"); _error.value = "advertise" }
    }

    private fun stopAdvertising() {
        runCatching { advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertiseCallback = null
    }

    /** The address on the Wi-Fi may have changed since the packet was built: advertise the new one. */
    fun refreshAdvertising() {
        if (!running || lastPayload?.contentEquals(payload()) == true) return
        stopAdvertising(); startAdvertising()
    }

    /**
     * [scan] = false is the quiet background mode: the phone only advertises, so a phone that is being touched can
     * find it. The phone that is doing the touching scans.
     */
    fun start(scan: Boolean = true) {
        if (running) return
        val ad = adapter
        if (ad == null || !ad.isEnabled) { _error.value = "off"; return }
        running = true
        _error.value = null

        startAdvertising()
        if (!scan) return

        val scanner = ad.bluetoothLeScanner
        if (scanner != null) {
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)
                override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode"); _error.value = "scan" }
            }
            scanCallback = cb
            runCatching {
                scanner.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(SERVICE).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),
                    cb,
                )
            }.onFailure { Log.w(TAG, "scan: $it"); _error.value = "scan" }
        } else _error.value = "scan"

        ticker = scope.launch {
            while (true) {
                delay(120)
                val now = System.currentTimeMillis()
                entries.values.removeAll { now - it.seenAt > STALE_MS }
                publish()
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        ticker?.cancel(); ticker = null
        stopAdvertising()
        runCatching { scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCallback = null; lastPayload = null; request = null
        entries.clear()
        _nearby.value = emptyList()
    }

    private fun onResult(r: ScanResult) {
        val data = r.scanRecord?.getManufacturerSpecificData(COMPANY) ?: return
        if (data.size < PAYLOAD_SIZE || data[0].toInt() != VERSION) return
        val prefix = Crypto.hex(data.copyOfRange(1, 7))
        if (prefix == Rt.node.selfId.take(PREFIX_CHARS)) return
        val ip = data.copyOfRange(7, 11)
        val host = if (ip.all { it.toInt() == 0 }) null else ip.joinToString(".") { (it.toInt() and 0xff).toString() }
        val port = ((data[11].toInt() and 0xff) shl 8) or (data[12].toInt() and 0xff)
        val now = System.currentTimeMillis()
        val known = entries[prefix]
        val e = known ?: Entry(prefix, host, port, r.rssi.toFloat(), now).also { entries[prefix] = it }
        // a quick average: touching is a fast move, a slow filter would lag behind it
        if (known != null) e.rssi = e.rssi * 0.5f + r.rssi * 0.5f
        e.host = host; e.port = port; e.seenAt = now
        if (e.rssi >= TOUCH) {
            if (++e.high >= 3 && now - e.lastTouch > COOLDOWN_MS) {
                e.lastTouch = now
                _touch.tryEmit(Nearby(e.prefix, e.host, e.port, e.rssi))
            }
        } else if (e.rssi < TOUCH - 6) e.high = 0
        publish()
    }

    private fun publish() {
        _nearby.value = entries.values.map { Nearby(it.prefix, it.host, it.port, it.rssi) }.sortedByDescending { it.rssi }
    }

    /** Who this packet asks to open a direct Wi-Fi link, and the seed both sides turn into the link's password. */
    private class Request(val target: String, val seed: ByteArray)

    @Volatile private var request: Request? = null

    /**
     * No shared Wi-Fi: ask the touched phone to join the direct Wi-Fi link this phone is opening. The packet then
     * carries who it is for and a random seed; the phone that is for it is scanning for exactly this.
     */
    fun requestDirect(targetPrefix: String, seed: ByteArray) {
        request = Request(targetPrefix, seed)
        if (running) { stopAdvertising(); startAdvertising() }
    }

    fun clearRequest() {
        if (request == null) return
        request = null
        if (running) { stopAdvertising(); startAdvertising() }
    }

    private fun payload(): ByteArray {
        val req = request
        val out = ByteArray(if (req != null) REQUEST_SIZE else PAYLOAD_SIZE)
        out[0] = VERSION.toByte()
        Discovery.hexToBytes(Rt.node.selfId.take(PREFIX_CHARS)).copyInto(out, 1)
        localAddress()?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }?.forEachIndexed { i, b -> out[7 + i] = b.toByte() }
        val port = Rt.node.port
        out[11] = (port ushr 8).toByte(); out[12] = port.toByte()
        if (req != null) {
            Discovery.hexToBytes(req.target).copyInto(out, PAYLOAD_SIZE)
            req.seed.copyInto(out, PAYLOAD_SIZE + 6)
        }
        return out
    }

    companion object {
        private const val TAG = "EssentialShare"
        private val SERVICE = ParcelUuid(UUID.fromString("e55e0001-5ba5-4e7e-9c1d-0a11e55e5a4e"))
        private const val COMPANY = 0xFFFF
        val SERVICE_UUID: ParcelUuid get() = SERVICE
        const val COMPANY_ID = COMPANY
        private const val VERSION = 1
        private const val PREFIX_CHARS = 12
        private const val PAYLOAD_SIZE = 13
        /** 13 + who it is for (6) + seed (8): exactly what fits next to the 4 header bytes of a 31-byte scan response */
        private const val REQUEST_SIZE = 27
        private const val STALE_MS = 2500L
        private const val COOLDOWN_MS = 10_000L

        // Signal strength in dBm. These are starting values: they depend on the phone, the case and where the
        // antenna sits, so they are meant to be tuned on real devices. The "tap to connect" list is the fallback.
        // One fixed level: the phones must be right against each other. The "phones meet" end of the scale sits just above it.
        const val FAR = -85f
        const val TOUCH = -38f
        const val NEAR = TOUCH + 4f

        val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)

        fun hasPermissions() = permissions.all { Rt.hasPermission(it) }

        /** Being found by the phone that touches this one needs only the advertise permission. */
        fun canAdvertise() = Rt.hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)

        /** null = fine, otherwise why touching cannot work. */
        fun unavailable(ctx: Context): String? {
            val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return "none"
            if (!ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) return "none"
            return if (ad.isEnabled) null else "off"
        }
    }
}

/**
 * The touched phone's ears while the app is closed: scans (the controller filters, so the processor sleeps) for
 * packets that ask this phone to open a direct Wi-Fi link, and reports one when the sender is really touching.
 */
@SuppressLint("MissingPermission")
class RequestScanner(private val ctx: Context, private val onRequest: (senderPrefix: String, seed: ByteArray, port: Int) -> Unit) {
    private var callback: ScanCallback? = null
    private val hits = HashMap<String, Int>()
    private val lastFired = HashMap<String, Long>()

    val running get() = callback != null

    fun start() {
        if (callback != null) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val me = Rt.node.selfId.take(PREFIX)
        // match bytes 13..18 of the manufacturer data (the "for" field) against this phone's own id
        val data = ByteArray(19).also { Discovery.hexToBytes(me).copyInto(it, 13) }
        val mask = ByteArray(19) { if (it >= 13) 0xFF.toByte() else 0 }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result, me)
            override fun onScanFailed(errorCode: Int) { Log.w("EssentialShare", "request scan failed: $errorCode"); callback = null }
        }
        callback = cb
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(TouchRadar.SERVICE_UUID).setManufacturerData(TouchRadar.COMPANY_ID, data, mask).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),
                cb,
            )
        }.onFailure { Log.w("EssentialShare", "request scan: $it"); callback = null }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        hits.clear()
    }

    private fun handle(r: ScanResult, me: String) {
        val d = r.scanRecord?.getManufacturerSpecificData(TouchRadar.COMPANY_ID) ?: return
        if (d.size < 27 || d[0].toInt() != 1) return
        if (Crypto.hex(d.copyOfRange(13, 19)) != me) return
        val sender = Crypto.hex(d.copyOfRange(1, 7))
        // a request is only answered when the phone that sent it is touching this one
        val n = if (r.rssi >= TouchRadar.TOUCH) (hits[sender] ?: 0) + 1 else 0
        hits[sender] = n
        val now = System.currentTimeMillis()
        if (n >= 3 && now - (lastFired[sender] ?: 0) > 25_000) {
            lastFired[sender] = now
            hits[sender] = 0
            onRequest(sender, d.copyOfRange(19, 27), ((d[11].toInt() and 0xff) shl 8) or (d[12].toInt() and 0xff))
        }
    }

    private companion object { const val PREFIX = 12 }
}

/** Two short knocks when the phones meet. */
fun touchHaptic(ctx: Context) = vibrate(ctx, longArrayOf(0, 28, 70, 46), intArrayOf(0, 180, 0, 255))

/** A soft pulse when the files have arrived. */
fun doneHaptic(ctx: Context) = vibrate(ctx, longArrayOf(0, 20, 50, 20, 50, 60), intArrayOf(0, 120, 0, 160, 0, 255))

private fun vibrate(ctx: Context, timings: LongArray, amplitudes: IntArray) {
    runCatching {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }
}
