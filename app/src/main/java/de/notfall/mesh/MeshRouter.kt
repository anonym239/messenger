package de.notfall.mesh

import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Die Mesh-Logik, unabhängig vom Funk. Jedes Handy:
 *  - meldet sich regelmäßig mit HELLO (Name + öffentlicher Schlüssel),
 *  - leitet JEDES Paket an alle anderen verbundenen Handys weiter (Flooding mit Sprungzähler),
 *  - speichert Pakete bis zu 24 h und gibt sie an neu auftauchende Handys weiter (Store and Forward),
 *  - bestätigt empfangene Nachrichten verschlüsselt (✓✓ beim Absender).
 *
 * Pakettypen:
 *  HELLO – offen, signiert: "Ich bin da" mit Name
 *  MSG   – Ende-zu-Ende-verschlüsselt: Text oder Empfangsbestätigung
 *  PUB   – offen, signiert: Nachricht an alle in der Nähe
 *  SOS   – offen, signiert: Notruf mit Standort
 *
 * Alle Methoden laufen auf demselben Thread (Main-Thread).
 */
class MeshRouter(
    private val crypto: Crypto,
    val state: MeshState,
    private val myName: () -> String,
    private val send: (endpoints: List<String>, data: ByteArray) -> Unit,
    private val onIncoming: (ChatMessage) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_HOPS = 15
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        const val HELLO_KEEP_MS = 5 * 60 * 1000L
        const val ONLINE_MS = 3 * 60 * 1000L
        const val MAX_TEXT = 2000
    }

    val myId get() = crypto.myId
    private val connected = LinkedHashSet<String>()
    private val store = LinkedHashMap<String, Packet>()   // Paket-ID -> Paket (zum Weitergeben)
    private val seen = LinkedHashSet<String>()

    val storedCount get() = store.size

    // ---------- Ereignisse vom Funk ----------

    fun onConnected(endpoint: String) {
        if (!connected.add(endpoint)) return
        state.connected.value = connected.size
        // Alles Gespeicherte an das neue Handy geben (inkl. wartender eigener Nachrichten)
        prune()
        store.values.toList().forEach { send(listOf(endpoint), it.toBytes()) }
        state.messages.value.filter { it.mine && it.status == Status.WAITING }
            .forEach { state.setStatus(it.mid, Status.RELAYED) }
        announce()
    }

    fun onDisconnected(endpoint: String) {
        connected -= endpoint
        state.connected.value = connected.size
    }

    fun onAllDisconnected() {
        connected.clear()
        state.connected.value = 0
    }

    fun onReceive(endpoint: String, data: ByteArray) {
        Packet.fromBytes(data)?.let { handle(it, endpoint) }
    }

    // ---------- Kontakte ----------

    fun isValidId(id: String) = crypto.isValidId(id)

    /** Kontakt von Hand hinzufügen (ID eingefügt oder QR-Code gescannt). */
    fun addContact(id: String, name: String): Boolean {
        val clean = id.trim()
        if (clean == myId || !crypto.isValidId(clean)) return false
        val old = state.devices.value[clean]
        state.putDevice(
            old?.copy(alias = name.trim().ifBlank { old.alias }, saved = true)
                ?: Device(clean, name.trim().ifBlank { "Kontakt" }, 0L, -1, alias = name.trim().ifBlank { null }, saved = true)
        )
        return true
    }

    fun rename(id: String, alias: String) {
        val d = state.devices.value[id] ?: return
        state.putDevice(d.copy(alias = alias.trim().ifBlank { null }, saved = true))
    }

    fun forget(id: String) {
        state.deleteChat(id)
        state.removeDevice(id)
    }

    // ---------- Senden ----------

    /** "Ich bin da" – mit Name, damit andere Handys das neue Gerät anzeigen. */
    fun announce() {
        val name = myName()
        if (name.isBlank()) return
        val body = crypto.signPublic("HELLO", JSONObject().put("name", name))
        handle(Packet(ttl = MAX_HOPS, type = "HELLO", created = now(), body = body), null)
    }

    fun sendText(peer: String, text: String): ChatMessage {
        if (peer == PUBLIC_CHANNEL) return sendPublic(text)
        val t = text.take(MAX_TEXT)
        val mid = UUID.randomUUID().toString()
        val ts = now()
        val content = JSONObject().put("t", "text").put("mid", mid).put("text", t).put("ts", ts).put("name", myName())
        val msg = ChatMessage(mid, peer, t, ts, mine = true, status = initialStatus())
        state.addMessage(msg)
        handle(Packet(ttl = MAX_HOPS, type = "MSG", created = ts, body = crypto.seal(peer, content)), null)
        return msg
    }

    /** Nachricht an alle in der Nähe (nicht verschlüsselt, aber signiert). */
    fun sendPublic(text: String): ChatMessage {
        val t = text.take(MAX_TEXT)
        val ts = now()
        val content = JSONObject().put("name", myName()).put("text", t).put("ts", ts)
        val p = Packet(ttl = MAX_HOPS, type = "PUB", created = ts, body = crypto.signPublic("PUB", content))
        val msg = ChatMessage(p.id, PUBLIC_CHANNEL, t, ts, mine = true, status = initialStatus(), senderName = myName())
        state.addMessage(msg)
        handle(p, null)
        return msg
    }

    fun sendSos(text: String, lat: Double?, lon: Double?) {
        val pos = if (lat != null && lon != null) String.format(Locale.ROOT, "%.5f, %.5f", lat, lon) else ""
        val ts = now()
        val content = JSONObject().put("name", myName()).put("text", text.take(MAX_TEXT)).put("pos", pos).put("ts", ts)
        val p = Packet(ttl = MAX_HOPS, type = "SOS", created = ts, body = crypto.signPublic("SOS", content))
        state.addMessage(ChatMessage(p.id, myId, text, ts, mine = true, sos = true,
            position = pos.ifEmpty { null }, senderName = myName(), status = initialStatus()))
        handle(p, null)
    }

    private fun initialStatus() = if (connected.isEmpty()) Status.WAITING else Status.RELAYED

    // ---------- Kern: empfangen, auswerten, weiterleiten ----------

    private fun handle(p: Packet, from: String?) {
        if (!seen.add(p.id)) return                    // schon gesehen -> keine Endlosschleifen
        if (seen.size > 20000) seen.remove(seen.first())
        val age = now() - p.created
        if (age > KEEP_MS || (p.type == "HELLO" && age > HELLO_KEEP_MS)) return

        if (from != null) {
            try {
                when (p.type) {
                    "HELLO" -> onHello(p)
                    "MSG" -> onMessage(p)
                    "PUB" -> onPublic(p)
                    "SOS" -> onSos(p)
                }
            } catch (_: Exception) { }
        }

        if (p.ttl <= 0) return
        val fwd = p.copy(ttl = p.ttl - 1)
        store[fwd.id] = fwd
        prune()
        val targets = connected.filter { it != from }
        if (targets.isNotEmpty()) send(targets, fwd.toBytes())
    }

    private fun onHello(p: Packet) {
        val (id, c) = crypto.verifyPublic("HELLO", p.body) ?: return
        if (id == myId) return
        val hops = (MAX_HOPS - 1 - p.ttl).coerceAtLeast(0)
        val old = state.devices.value[id]
        val name = c.getString("name").take(40)
        val d = old?.copy(name = name, lastSeen = now(), hops = hops) ?: Device(id, name, now(), hops)
        state.putDevice(d)
        if (old == null || !old.online(now())) state.newDevices.tryEmit(d)
    }

    private fun onMessage(p: Packet) {
        val (from, c) = crypto.open(p.body) ?: return  // nicht für mich: nur weiterleiten
        when (c.getString("t")) {
            "text" -> {
                val mid = c.getString("mid")
                val name = c.optString("name").take(40)
                val old = state.devices.value[from]
                if (old == null) state.putDevice(Device(from, name.ifBlank { "Unbekannt" }, 0L, -1))
                else if (name.isNotBlank() && old.name != name) state.putDevice(old.copy(name = name))
                val m = ChatMessage(mid, from, c.getString("text").take(MAX_TEXT), c.getLong("ts"), mine = false)
                val isNew = state.messages.value.none { it.mid == mid }
                state.addMessage(m)
                if (isNew) onIncoming(m)
                // Empfangsbestätigung zurück an den Absender (verschlüsselt)
                val ack = JSONObject().put("t", "ack").put("mid", mid)
                handle(Packet(ttl = MAX_HOPS, type = "MSG", created = now(), body = crypto.seal(from, ack)), null)
            }
            "ack" -> state.setStatus(c.getString("mid"), Status.DELIVERED)
        }
    }

    private fun onPublic(p: Packet) {
        val (from, c) = crypto.verifyPublic("PUB", p.body) ?: return
        if (from == myId) return
        val m = ChatMessage(p.id, PUBLIC_CHANNEL, c.getString("text").take(MAX_TEXT), c.getLong("ts"), mine = false,
            senderName = c.optString("name").take(40).ifBlank { "Unbekannt" })
        state.addMessage(m)
        onIncoming(m)
    }

    private fun onSos(p: Packet) {
        val (from, c) = crypto.verifyPublic("SOS", p.body) ?: return
        if (from == myId) return
        val m = ChatMessage(p.id, from, c.getString("text"), c.getLong("ts"), mine = false, sos = true,
            position = c.optString("pos").ifEmpty { null }, senderName = c.optString("name"))
        state.addMessage(m)
        onIncoming(m)
    }

    private fun prune() {
        val t = now()
        store.values.removeAll {
            t - it.created > (if (it.type == "HELLO") HELLO_KEEP_MS else KEEP_MS)
        }
        while (store.size > 1000) store.remove(store.keys.first())
    }
}
