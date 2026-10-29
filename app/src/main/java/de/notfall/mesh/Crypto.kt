package de.notfall.mesh

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.signature.SignatureConfig
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Ende-zu-Ende-Verschlüsselung.
 *
 * - Verschlüsselung: HPKE mit X25519 + HKDF-SHA256 + ChaCha20-Poly1305 (RFC 9180).
 *   Jede Nachricht nutzt einen neuen Einmal-Schlüssel.
 * - Signatur: Ed25519. Der Absender steckt INNERHALB des Chiffrats, deshalb sehen
 *   weiterleitende Handys weder Absender noch Empfänger noch Inhalt.
 * - Auffüllen auf 512-Byte-Blöcke, damit die Länge wenig über den Inhalt verrät.
 * - Private Schlüssel liegen verschlüsselt mit einem Schlüssel aus dem Android-Keystore
 *   (Hardware-geschützt, wo das Gerät es unterstützt).
 */
class Crypto(ctx: Context) {

    private val encKeys: KeysetHandle
    private val signKeys: KeysetHandle

    /** Öffentliche Identität (Base64). Wird mit Kontakten ausgetauscht. */
    val myId: String

    init {
        HybridConfig.register()
        SignatureConfig.register()
        // _RAW: kein Schlüssel-Präfix im Chiffrat, sonst wäre der Empfänger erkennbar
        encKeys = loadOrCreate(ctx, "enc", "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305_RAW")
        signKeys = loadOrCreate(ctx, "sign", "ED25519")
        val id = JSONObject()
            .put("e", b64(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encKeys.publicKeysetHandle)))
            .put("s", b64(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signKeys.publicKeysetHandle)))
        myId = b64(id.toString().toByteArray())
    }

    /** Verschlüsselt eine Chat-Nachricht für genau einen Empfänger. */
    fun encrypt(recipientId: String, text: String): ByteArray {
        val ts = System.currentTimeMillis()
        val inner = JSONObject()
            .put("from", myId)
            .put("ts", ts)
            .put("text", text)
            .put("sig", b64(sign(signedBytes("MSG", myId, ts, text))))
        val enc = publicKey(recipientId, "e").getPrimitive(HybridEncrypt::class.java)
        return enc.encrypt(pad(inner.toString().toByteArray()), CONTEXT)
    }

    /** Versucht eine Nachricht zu entschlüsseln. null = nicht für mich (nur weiterleiten). */
    fun decrypt(blob: ByteArray): ChatMessage? = try {
        val dec = encKeys.getPrimitive(HybridDecrypt::class.java)
        val o = JSONObject(String(unpad(dec.decrypt(blob, CONTEXT))))
        val from = o.getString("from")
        val ts = o.getLong("ts")
        val text = o.getString("text")
        verify(from, unb64(o.getString("sig")), signedBytes("MSG", from, ts, text))
        ChatMessage(from = from, text = text, ts = ts)
    } catch (e: Exception) {
        null
    }

    /**
     * SOS ist absichtlich NICHT verschlüsselt: Jeder (auch Fremde, Bergwacht, Polizei)
     * soll es lesen und weitergeben können. Es ist aber signiert, damit es unterwegs
     * nicht verändert werden kann.
     */
    fun signSos(text: String, lat: Double?, lon: Double?): ByteArray {
        val ts = System.currentTimeMillis()
        val pos = if (lat != null && lon != null) "%.5f,%.5f".format(java.util.Locale.ROOT, lat, lon) else ""
        val body = "$text|$pos"
        return JSONObject()
            .put("from", myId).put("ts", ts).put("text", text).put("pos", pos)
            .put("sig", b64(sign(signedBytes("SOS", myId, ts, body))))
            .toString().toByteArray()
    }

    fun verifySos(blob: ByteArray): ChatMessage? = try {
        val o = JSONObject(String(blob))
        val from = o.getString("from")
        val ts = o.getLong("ts")
        val text = o.getString("text")
        val pos = o.getString("pos")
        verify(from, unb64(o.getString("sig")), signedBytes("SOS", from, ts, "$text|$pos"))
        ChatMessage(from = from, text = text, ts = ts, sos = true, position = pos.ifEmpty { null })
    } catch (e: Exception) {
        null
    }

    private fun sign(data: ByteArray) = signKeys.getPrimitive(PublicKeySign::class.java).sign(data)

    private fun verify(senderId: String, sig: ByteArray, data: ByteArray) =
        publicKey(senderId, "s").getPrimitive(PublicKeyVerify::class.java).verify(sig, data)

    private fun signedBytes(type: String, from: String, ts: Long, text: String) =
        "$type|$from|$ts|$text".toByteArray()

    private fun publicKey(id: String, part: String): KeysetHandle {
        val o = JSONObject(String(unb64(id)))
        return TinkProtoKeysetFormat.parseKeysetWithoutSecret(unb64(o.getString(part)))
    }

    private fun loadOrCreate(ctx: Context, name: String, template: String): KeysetHandle =
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, name, "notfall_keys")
            .withKeyTemplate(KeyTemplates.get(template))
            .withMasterKeyUri("android-keystore://notfall_master")
            .build()
            .keysetHandle

    companion object {
        private val CONTEXT = "notfall-mesh-v1".toByteArray()
        private const val BLOCK = 512

        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

        /** Kurzer Fingerabdruck zum Vergleichen von Angesicht zu Angesicht. */
        fun fingerprint(id: String): String =
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
                .take(6).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")

        private fun pad(data: ByteArray): ByteArray {
            val size = ((data.size + 4 + BLOCK - 1) / BLOCK) * BLOCK
            return ByteBuffer.allocate(size).putInt(data.size).put(data).array()
        }

        private fun unpad(data: ByteArray): ByteArray {
            val buf = ByteBuffer.wrap(data)
            val len = buf.int
            return ByteArray(len).also { buf.get(it) }
        }
    }
}
