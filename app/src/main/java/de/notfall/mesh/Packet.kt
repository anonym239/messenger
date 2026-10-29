package de.notfall.mesh

import org.json.JSONObject
import java.util.UUID

/**
 * Was über Funk von Handy zu Handy geht. Weiterleitende Handys sehen nur:
 * eine zufällige ID, die Anzahl restlicher Sprünge, den Typ und verschlüsselte Bytes.
 */
data class Packet(
    val id: String = UUID.randomUUID().toString(),
    val ttl: Int,
    val type: String,          // "MSG" (verschlüsselt) oder "SOS" (offen, signiert)
    val created: Long = System.currentTimeMillis(),
    val body: ByteArray,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("id", id).put("ttl", ttl).put("type", type).put("c", created)
        .put("b", Crypto.b64(body))
        .toString().toByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): Packet? = try {
            val o = JSONObject(String(bytes))
            Packet(o.getString("id"), o.getInt("ttl"), o.getString("type"), o.getLong("c"), Crypto.unb64(o.getString("b")))
        } catch (e: Exception) {
            null
        }
    }
}

data class ChatMessage(
    val from: String,
    val text: String,
    val ts: Long,
    val sos: Boolean = false,
    val position: String? = null,
    val mine: Boolean = false,
    val to: String? = null,
)
