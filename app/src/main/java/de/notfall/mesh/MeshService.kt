package de.notfall.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Mesh-Netz: Jedes Handy mit der App sucht ständig nach anderen, verbindet sich
 * automatisch und leitet alle Pakete weiter ("Store and Forward").
 * Wer gerade niemanden in Reichweite hat, speichert die Pakete bis zu 24 h und
 * gibt sie weiter, sobald ein anderes Handy auftaucht.
 */
class MeshService : Service() {

    companion object {
        const val SERVICE_ID = "de.notfall.mesh"
        const val MAX_HOPS = 15
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        private const val CH_STATUS = "status"
        private const val CH_MSG = "nachrichten"

        val inbox = MutableStateFlow<List<ChatMessage>>(emptyList())
        val peers = MutableStateFlow(0)
        val myId = MutableStateFlow<String?>(null)
        var instance: MeshService? = null
            private set
    }

    lateinit var crypto: Crypto
        private set
    private lateinit var nearby: ConnectionsClient
    private val connected = mutableSetOf<String>()
    private val store = LinkedHashMap<String, Packet>()   // Paket-ID -> Paket
    private val seen = HashSet<String>()

    // Zufälliger Name pro Start, damit man Personen nicht über den Funknamen verfolgen kann
    private val endpointName = UUID.randomUUID().toString().take(8)

    override fun onCreate() {
        super.onCreate()
        crypto = Crypto(this)
        myId.value = crypto.myId
        nearby = Nearby.getConnectionsClient(this)
        createChannels()
        ServiceCompat.startForeground(
            this, 1, statusNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        instance = this
        startMesh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        nearby.stopAllEndpoints()
        nearby.stopAdvertising()
        nearby.stopDiscovery()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Senden ----------

    fun sendChat(recipientId: String, text: String) {
        val p = Packet(ttl = MAX_HOPS, type = "MSG", body = crypto.encrypt(recipientId, text))
        inbox.value += ChatMessage(crypto.myId, text, p.created, mine = true, to = recipientId)
        accept(p, fromEndpoint = null)
    }

    fun sendSos(text: String, lat: Double?, lon: Double?) {
        val p = Packet(ttl = MAX_HOPS, type = "SOS", body = crypto.signSos(text, lat, lon))
        crypto.verifySos(p.body)?.let { inbox.value += it.copy(mine = true) }
        accept(p, fromEndpoint = null)
    }

    // ---------- Mesh-Logik ----------

    private fun accept(p: Packet, fromEndpoint: String?) {
        if (!seen.add(p.id)) return
        if (System.currentTimeMillis() - p.created > KEEP_MS) return

        if (fromEndpoint != null) {
            when (p.type) {
                "MSG" -> crypto.decrypt(p.body)?.let { deliver(it) }
                "SOS" -> crypto.verifySos(p.body)?.let { deliver(it) }
            }
        }

        if (p.ttl <= 0) return
        val fwd = p.copy(ttl = p.ttl - 1)
        store[fwd.id] = fwd
        pruneStore()
        val targets = connected.filter { it != fromEndpoint }
        if (targets.isNotEmpty()) nearby.sendPayload(targets, Payload.fromBytes(fwd.toBytes()))
    }

    private fun pruneStore() {
        val now = System.currentTimeMillis()
        store.values.removeAll { now - it.created > KEEP_MS }
        while (store.size > 500) store.remove(store.keys.first())
    }

    private fun deliver(m: ChatMessage) {
        inbox.value += m
        val title = if (m.sos) "SOS-NOTRUF empfangen!" else "Neue Nachricht"
        val text = if (m.sos) "${m.text}  Standort: ${m.position ?: "unbekannt"}" else m.text
        val n = NotificationCompat.Builder(this, CH_MSG)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(m.ts.toInt(), n)
        } catch (_: SecurityException) { }
    }

    // ---------- Nearby Connections (Bluetooth + WLAN-Direkt, kein Internet) ----------

    private fun startMesh() {
        val strategy = Strategy.P2P_CLUSTER   // viele-zu-viele, ideal für Mesh
        nearby.startAdvertising(endpointName, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
        nearby.startDiscovery(SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (id in connected) return
            // Nur eine Seite fragt an, sonst kollidieren die Anfragen
            if (endpointName < info.endpointName) nearby.requestConnection(endpointName, id, lifecycle)
        }

        override fun onEndpointLost(id: String) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            // Automatisch annehmen: Der Inhalt ist ohnehin Ende-zu-Ende-verschlüsselt
            nearby.acceptConnection(id, payloads)
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) return
            connected += id
            updatePeers()
            // Gespeicherte Pakete an das neue Handy weitergeben
            pruneStore()
            store.values.forEach { nearby.sendPayload(id, Payload.fromBytes(it.toBytes())) }
        }

        override fun onDisconnected(id: String) {
            connected -= id
            updatePeers()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            payload.asBytes()?.let { Packet.fromBytes(it) }?.let { accept(it, fromEndpoint = id) }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private fun updatePeers() {
        peers.value = connected.size
        getSystemService(NotificationManager::class.java).notify(1, statusNotification())
    }

    // ---------- Benachrichtigungen ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Mesh-Status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Nachrichten & SOS", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun statusNotification(): Notification =
        NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Notfall-Mesh aktiv")
            .setContentText("${connected.size} Geräte verbunden · ${store.size} Nachrichten zwischengespeichert")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
}
