package dev.essentialshare.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    val random = SecureRandom()
    private val X25519_SPKI_PREFIX =
        byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()

    fun rawPublic(pub: PublicKey): ByteArray = pub.encoded.let { it.copyOfRange(it.size - 32, it.size) }

    fun publicFromRaw(raw: ByteArray): PublicKey =
        KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(X25519_SPKI_PREFIX + raw))

    fun privateFromPkcs8(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("X25519").generatePrivate(PKCS8EncodedKeySpec(bytes))

    fun dh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("X25519").apply { init(priv); doPhase(pub, true) }.generateSecret()

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").also { md -> parts.forEach { md.update(it) } }.digest()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        var t = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}

/** Long-lived device identity: an X25519 key pair. The device id is derived from the public key. */
class Identity(val keyPair: KeyPair) {
    val publicRaw: ByteArray = Crypto.rawPublic(keyPair.public)
    val id: String = idOf(publicRaw)

    companion object {
        fun idOf(publicRaw: ByteArray) = Crypto.hex(Crypto.sha256(publicRaw)).take(32)

        fun load(store: Store): Identity {
            val priv = store.get("identity.private")
            val pub = store.get("identity.public")
            if (priv != null && pub != null) {
                runCatching {
                    return Identity(KeyPair(Crypto.publicFromRaw(Crypto.unb64(pub)), Crypto.privateFromPkcs8(Crypto.unb64(priv))))
                }
            }
            val kp = Crypto.generateKeyPair()
            store.put("identity.private", Crypto.b64(kp.private.encoded))
            store.put("identity.public", Crypto.b64(Crypto.rawPublic(kp.public)))
            return Identity(kp)
        }
    }
}

/** Result of the key exchange: directional keys plus a short code both users can compare. */
class Session(val sendKey: ByteArray, val recvKey: ByteArray, val peerStatic: ByteArray, val code: String)

object Handshake {
    private val MAGIC = byteArrayOf('E'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte())

    /**
     * Mutually authenticated key exchange: ephemeral and static X25519 keys are all mixed, so only the holders
     * of both static private keys can derive the session keys, and every session is forward secret.
     */
    fun run(input: DataInputStream, output: OutputStream, me: Identity, initiator: Boolean): Session {
        val eph = Crypto.generateKeyPair()
        val ephRaw = Crypto.rawPublic(eph.public)
        output.write(MAGIC + ephRaw + me.publicRaw)
        output.flush()

        val magic = ByteArray(4).also { input.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("not an Essential Share peer")
        val peerEph = ByteArray(32).also { input.readFully(it) }
        val peerStatic = ByteArray(32).also { input.readFully(it) }
        if (peerStatic.contentEquals(me.publicRaw)) throw IOException("connected to self")

        val peerEphKey = Crypto.publicFromRaw(peerEph)
        val peerStaticKey = Crypto.publicFromRaw(peerStatic)
        val ee = Crypto.dh(eph.private, peerEphKey)
        val myStaticTheirEph = Crypto.dh(me.keyPair.private, peerEphKey)
        val myEphTheirStatic = Crypto.dh(eph.private, peerStaticKey)
        val ss = Crypto.dh(me.keyPair.private, peerStaticKey)

        val eI = if (initiator) ephRaw else peerEph
        val sI = if (initiator) me.publicRaw else peerStatic
        val eR = if (initiator) peerEph else ephRaw
        val sR = if (initiator) peerStatic else me.publicRaw
        val transcript = Crypto.sha256(MAGIC, eI, sI, eR, sR)

        // role-independent order: ee, (initiator static x responder eph), (initiator eph x responder static), ss
        val ikm = if (initiator) ee + myStaticTheirEph + myEphTheirStatic + ss
        else ee + myEphTheirStatic + myStaticTheirEph + ss
        val okm = Crypto.hkdf(ikm, transcript, "essential-share/1".toByteArray(), 64)
        val i2r = okm.copyOfRange(0, 32)
        val r2i = okm.copyOfRange(32, 64)

        val sas = Crypto.sha256(transcript, "sas".toByteArray())
        var n = 0L
        for (i in 0 until 4) n = (n shl 8) or (sas[i].toLong() and 0xff)
        val code = "%06d".format(n % 1_000_000)
        return if (initiator) Session(i2r, r2i, peerStatic, code) else Session(r2i, i2r, peerStatic, code)
    }
}

/** AES-256-GCM framed stream: [u32 length][ciphertext+tag], per-direction key and counter nonce. */
class SecureChannel(
    private val input: DataInputStream,
    private val output: OutputStream,
    sendKey: ByteArray,
    recvKey: ByteArray,
) {
    private val sendKeySpec = SecretKeySpec(sendKey, "AES")
    private val recvKeySpec = SecretKeySpec(recvKey, "AES")
    private val sendCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val recvCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var sendCounter = 0L
    private var recvCounter = 0L
    private val writeLock = Any()
    private val header = ByteArray(4)

    private fun nonce(counter: Long): GCMParameterSpec {
        val n = ByteArray(12)
        for (i in 0 until 8) n[11 - i] = (counter ushr (8 * i)).toByte()
        return GCMParameterSpec(128, n)
    }

    fun write(buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        synchronized(writeLock) {
            sendCipher.init(Cipher.ENCRYPT_MODE, sendKeySpec, nonce(sendCounter++))
            val ct = sendCipher.doFinal(buf, off, len)
            header[0] = (ct.size ushr 24).toByte(); header[1] = (ct.size ushr 16).toByte()
            header[2] = (ct.size ushr 8).toByte(); header[3] = ct.size.toByte()
            output.write(header)
            output.write(ct)
            output.flush()
        }
    }

    /** Blocks for the next frame; throws [EOFException] when the peer closed. */
    fun read(): ByteArray {
        val n = input.readInt()
        if (n < 16 || n > MAX_FRAME + 16) throw IOException("bad frame length $n")
        val ct = ByteArray(n)
        input.readFully(ct)
        recvCipher.init(Cipher.DECRYPT_MODE, recvKeySpec, nonce(recvCounter++))
        return recvCipher.doFinal(ct)
    }

    companion object {
        const val MAX_FRAME = 1 shl 20
    }
}
