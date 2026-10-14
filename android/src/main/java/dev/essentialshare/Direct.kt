package dev.essentialshare

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import dev.essentialshare.core.Crypto
import dev.essentialshare.core.TransferState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A direct Wi-Fi link for phones that are not on the same network.
 *
 * The phone that touches opens a Wi-Fi Direct group under a name and password both phones can work out (the name
 * from the sender's id, the password from a random seed that travels in the Bluetooth packet). The touched phone
 * joins it like an ordinary Wi-Fi network and the usual encrypted link runs over it. Nothing here is trusted: the
 * packet is readable by anyone close by, so what protects the files is the same mutual key check and pairing code
 * as on any network.
 */
object Direct {
    private const val TAG = "EssentialShare"
    private const val GO_ADDRESS = "192.168.49.1"

    fun ssid(senderPrefix: String) = "DIRECT-ES-" + senderPrefix.take(10)

    fun passphrase(seed: ByteArray): String =
        Crypto.b64(Crypto.sha256(seed, "essential-share/direct".toByteArray())).filter { it.isLetterOrDigit() }.take(16)

    // ---- sender: owns the group ----------------------------------------------------------------------

    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var hosting = false

    /** Opens the group. [onResult] is called on the main thread. */
    @SuppressLint("MissingPermission")
    fun host(ctx: Context, senderPrefix: String, seed: ByteArray, onResult: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val mgr = app.getSystemService(WifiP2pManager::class.java)
        val ch = mgr?.initialize(app, Looper.getMainLooper(), null)
        if (mgr == null || ch == null) { onResult(false); return }
        val cfg = runCatching {
            WifiP2pConfig.Builder().setNetworkName(ssid(senderPrefix)).setPassphrase(passphrase(seed))
                .enablePersistentMode(false).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build()
        }.getOrElse { Log.w(TAG, "direct config: $it"); onResult(false); return }
        p2p = mgr; channel = ch
        fun create() {
            runCatching {
                mgr.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { hosting = true; touch(); monitor(app); onResult(true) }
                    override fun onFailure(reason: Int) { Log.w(TAG, "createGroup failed: $reason"); onResult(false) }
                })
            }.onFailure { Log.w(TAG, "createGroup: $it"); onResult(false) }
        }
        // a group left over from before would make createGroup fail as busy
        runCatching {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = create()
                override fun onFailure(reason: Int) = create()
            })
        }.onFailure { create() }
    }

    @SuppressLint("MissingPermission")
    private fun stopHost() {
        val mgr = p2p ?: return
        val ch = channel ?: return
        runCatching { mgr.removeGroup(ch, null) }
        hosting = false
    }

    // ---- touched phone: joins -------------------------------------------------------------------------

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Joins the sender's group and links to it. [onFail] is called if the group never shows up or the link does not
     * come out of it. Both callbacks arrive on the main thread.
     */
    fun join(ctx: Context, senderPrefix: String, seed: ByteArray, port: Int, onFail: () -> Unit) {
        val app = ctx.applicationContext
        leave()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val spec = runCatching { WifiNetworkSpecifier.Builder().setSsid(ssid(senderPrefix)).setWpa2Passphrase(passphrase(seed)).build() }
            .getOrElse { Log.w(TAG, "direct specifier: $it"); onFail(); return }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec).build()
        var started = false
        fun link(network: Network, lp: LinkProperties?) {
            if (started) return
            started = true
            val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: GO_ADDRESS
            touch(); monitor(app)
            // a socket made by the network itself goes over this link and not over whatever else the phone is on
            Rt.node.connectNearby(senderPrefix, gateway, port, timeoutMs = 5000, socketFactory = { network.socketFactory.createSocket() }) { ok ->
                if (!ok) Rt.scope.launch { leave(); onFail() }
            }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { link(network, cm.getLinkProperties(network)) }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { link(network, lp) }
            override fun onUnavailable() { Log.w(TAG, "direct network unavailable"); Rt.scope.launch { leave(); onFail() } }
        }
        callback = cb
        runCatching { cm.requestNetwork(request, cb, 40_000) }.onFailure { Log.w(TAG, "direct request: $it"); leave(); onFail() }
    }

    private fun leave() {
        val cb = callback ?: return
        callback = null
        runCatching { Rt.appContext().getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }

    // ---- tidying up -----------------------------------------------------------------------------------

    private var lastActive = 0L
    private var monitorJob: Job? = null

    private fun touch() { lastActive = System.currentTimeMillis() }

    /** Lets the direct link go once nothing has moved over it for a while. */
    private fun monitor(app: Context) {
        if (monitorJob != null) return
        monitorJob = Rt.scope.launch {
            while (hosting || callback != null) {
                delay(3000)
                if (Rt.node.transfers.value.any { it.state == TransferState.ACTIVE || it.needsAnswer }) touch()
                if (System.currentTimeMillis() - lastActive > 45_000) { stopHost(); leave() }
            }
            monitorJob = null
        }
    }
}
