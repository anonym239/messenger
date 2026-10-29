package de.notfall.mesh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Was über Funk von Handy zu Handy geht. Weiterleitende Handys sehen nur:
 * eine zufällige ID, die Anzahl restlicher Sprünge, den Typ und (bei MSG) verschlüsselte Bytes.
 */
data class Packet(
    val id: String = UUID.randomUUID().toString(),
    val ttl: Int,
    val type: String,          // "HELLO" (offen, signiert), "MSG" (verschlüsselt), "SOS" (offen, signiert)
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

enum class Status { WAITING, RELAYED, DELIVERED }

data class ChatMessage(
    val mid: String,
    val peer: String,          // ID des Gesprächspartners
    val text: String,
    val ts: Long,
    val mine: Boolean,
    val status: Status = Status.DELIVERED,
    val sos: Boolean = false,
    val position: String? = null,
    val senderName: String? = null,
)

data class Device(
    val id: String,
    val name: String,          // Name, den das Gerät selbst sendet
    val lastSeen: Long,        // 0 = noch nie per Funk gesehen
    val hops: Int,             // 0 = direkt verbunden, 1 = über ein anderes Handy, …
    val alias: String? = null, // eigener Name für den Kontakt (überschreibt [name])
    val saved: Boolean = false,// von Hand hinzugefügt / gespeichert
) {
    val display get() = alias?.takeIf { it.isNotBlank() } ?: name
    fun online(now: Long = System.currentTimeMillis()) = lastSeen > 0 && now - lastSeen < MeshRouter.ONLINE_MS
}

/** Gesprächspartner-ID für den öffentlichen Kanal "Alle in der Nähe". */
const val PUBLIC_CHANNEL = "ALLE"

/** Zustand, den die Oberfläche anzeigt. Wird in einer Datei gespeichert (null = nur im Speicher). */
class MeshState(private val file: File? = null) {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val connected = MutableStateFlow(0)
    val lastRead = MutableStateFlow<Map<String, Long>>(emptyMap())
    val newDevices = MutableSharedFlow<Device>(extraBufferCapacity = 16)

    init {
        load()
    }

    fun addMessage(m: ChatMessage) {
        if (messages.value.any { it.mid == m.mid }) return
        messages.value = (messages.value + m).takeLast(2000)
        save()
    }

    fun setStatus(mid: String, status: Status) {
        messages.value = messages.value.map {
            if (it.mid == mid && it.mine && it.status < status) it.copy(status = status) else it
        }
        save()
    }

    fun putDevice(d: Device) {
        devices.value = devices.value + (d.id to d)
        save()
    }

    fun deleteChat(peer: String) {
        messages.value = messages.value.filter { it.peer != peer || it.sos }
        save()
    }

    fun removeDevice(id: String) {
        devices.value = devices.value - id
        save()
    }

    fun markRead(peer: String) {
        lastRead.value = lastRead.value + (peer to System.currentTimeMillis())
    }

    fun unread(peer: String): Int {
        val since = lastRead.value[peer] ?: 0L
        return messages.value.count { it.peer == peer && !it.mine && !it.sos && it.ts > since }
    }

    private fun save() {
        val f = file ?: return
        try {
            val msgs = JSONArray()
            messages.value.forEach {
                msgs.put(JSONObject().put("mid", it.mid).put("peer", it.peer).put("text", it.text).put("ts", it.ts)
                    .put("mine", it.mine).put("status", it.status.name).put("sos", it.sos)
                    .put("pos", it.position ?: "").put("sn", it.senderName ?: ""))
            }
            val devs = JSONArray()
            devices.value.values.forEach {
                devs.put(JSONObject().put("id", it.id).put("name", it.name).put("seen", it.lastSeen).put("hops", it.hops)
                    .put("alias", it.alias ?: "").put("saved", it.saved))
            }
            f.writeText(JSONObject().put("messages", msgs).put("devices", devs).toString())
        } catch (_: Exception) { }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            val msgs = o.getJSONArray("messages")
            messages.value = (0 until msgs.length()).map { i ->
                val m = msgs.getJSONObject(i)
                ChatMessage(m.getString("mid"), m.getString("peer"), m.getString("text"), m.getLong("ts"),
                    m.getBoolean("mine"), Status.valueOf(m.getString("status")), m.getBoolean("sos"),
                    m.getString("pos").ifEmpty { null }, m.getString("sn").ifEmpty { null })
            }
            val devs = o.getJSONArray("devices")
            devices.value = (0 until devs.length()).associate { i ->
                val d = devs.getJSONObject(i)
                d.getString("id") to Device(d.getString("id"), d.getString("name"), d.getLong("seen"), d.getInt("hops"),
                    d.optString("alias").ifEmpty { null }, d.optBoolean("saved"))
            }
        } catch (_: Exception) { }
    }
}
