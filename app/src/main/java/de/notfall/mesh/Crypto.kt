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
 * - Private Schlüssel liegen verschlüsselt mit einem Schlüssel aus dem Android-Keystore.
 */
class Crypto(ctx: Context, storeName: String = "notfall_keys") {

    private val encKeys: KeysetHandle
    private val signKeys: KeysetHandle
    private val decryptor: HybridDecrypt
    private val signer: PublicKeySign
    private val encryptors = HashMap<String, HybridEncrypt>()
    private val verifiers = HashMap<String, PublicKeyVerify>()

    /** Öffentliche Identität (Base64). Wird automatisch per HELLO verteilt. */
    val myId: String

    init {
        HybridConfig.register()
        SignatureConfig.register()
        // _RAW: kein Schlüssel-Präfix im Chiffrat, sonst wäre der Empfänger erkennbar
        encKeys = loadOrCreate(ctx, storeName, "enc", "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305_RAW")
        signKeys = loadOrCreate(ctx, storeName, "sign", "ED25519")
        @Suppress("DEPRECATION")
        decryptor = encKeys.getPrimitive(HybridDecrypt::class.java)
        @Suppress("DEPRECATION")
        signer = signKeys.getPrimitive(PublicKeySign::class.java)
        val id = JSONObject()
            .put("e", b64(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encKeys.publicKeysetHandle)))
            .put("s", b64(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signKeys.publicKeysetHandle)))
        myId = b64(id.toString().toByteArray())
    }

    /** Verschlüsselt [content] so, dass nur [recipientId] es lesen kann. */
    fun seal(recipientId: String, content: JSONObject): ByteArray {
        val c = content.toString()
        val inner = JSONObject()
            .put("from", myId)
            .put("c", c)
            .put("sig", b64(signer.sign("MSG|$myId|$c".toByteArray())))
        return encryptor(recipientId).encrypt(pad(inner.toString().toByteArray()), CONTEXT)
    }

    /** Entschlüsselt und prüft die Signatur. null = nicht für mich (nur weiterleiten). */
    fun open(blob: ByteArray): Pair<String, JSONObject>? = try {
        val o = JSONObject(String(unpad(decryptor.decrypt(blob, CONTEXT))))
        val from = o.getString("from")
        val c = o.getString("c")
        verifier(from).verify(unb64(o.getString("sig")), "MSG|$from|$c".toByteArray())
        from to JSONObject(c)
    } catch (e: Exception) {
        null
    }

    /** Offen lesbar, aber signiert (für HELLO und SOS – jeder soll es lesen können). */
    fun signPublic(type: String, content: JSONObject): ByteArray {
        val c = content.toString()
        return JSONObject()
            .put("from", myId)
            .put("c", c)
            .put("sig", b64(signer.sign("$type|$myId|$c".toByteArray())))
            .toString().toByteArray()
    }

    fun verifyPublic(type: String, blob: ByteArray): Pair<String, JSONObject>? = try {
        val o = JSONObject(String(blob))
        val from = o.getString("from")
        val c = o.getString("c")
        verifier(from).verify(unb64(o.getString("sig")), "$type|$from|$c".toByteArray())
        from to JSONObject(c)
    } catch (e: Exception) {
        null
    }

    /** Prüft, ob [id] eine gültige Notfall-Mesh-ID ist (z. B. nach Einfügen oder QR-Scan). */
    fun isValidId(id: String): Boolean = try {
        encryptor(id); verifier(id); true
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun encryptor(id: String) = encryptors.getOrPut(id) {
        publicKey(id, "e").getPrimitive(HybridEncrypt::class.java)
    }

    @Suppress("DEPRECATION")
    private fun verifier(id: String) = verifiers.getOrPut(id) {
        publicKey(id, "s").getPrimitive(PublicKeyVerify::class.java)
    }

    private fun publicKey(id: String, part: String): KeysetHandle {
        val o = JSONObject(String(unb64(id)))
        return TinkProtoKeysetFormat.parseKeysetWithoutSecret(unb64(o.getString(part)))
    }

    private fun loadOrCreate(ctx: Context, store: String, name: String, template: String): KeysetHandle =
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, name, store)
            .withKeyTemplate(KeyTemplates.get(template))
            .withMasterKeyUri("android-keystore://${store}_master")
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
