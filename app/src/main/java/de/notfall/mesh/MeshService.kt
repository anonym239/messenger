package de.notfall.mesh

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Funk-Schicht: Google Nearby Connections über Bluetooth, BLE und WLAN-Direkt.
 * Funktioniert ohne Internet und ohne Mobilfunk – auch im Flugmodus, solange
 * Bluetooth (und am besten WLAN) eingeschaltet ist.
 */
class MeshService : Service() {

    companion object {
        const val SERVICE_ID = "de.notfall.mesh.v1"
        private const val TICK_MS = 10_000L
        private const val ANNOUNCE_EVERY = 6        // alle 60 s HELLO
        private const val RESTART_EVERY = 12        // alle 2 min Suche neu starten, wenn allein
        var running = false
            private set
        val radioError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }

    private lateinit var nearby: ConnectionsClient
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connected = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val discovered = mutableMapOf<String, String>()   // Endpoint -> Funkname
    private val strategy = Strategy.P2P_CLUSTER                // viele-zu-viele, ideal für Mesh
    private var ticks = 0

    // Zufälliger Funkname pro Start: Fremde können Personen nicht über den Funknamen verfolgen
    private val endpointName = UUID.randomUUID().toString().take(8)

    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(this, 1, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            radioError.value = "Hintergrunddienst konnte nicht starten: ${e.message}"
        }
        running = true
        nearby = Nearby.getConnectionsClient(this)
        Mesh.transport = { eps, data ->
            try {
                nearby.sendPayload(eps, Payload.fromBytes(data))
            } catch (_: Exception) { }
        }
        startRadio()
        scope.launch { Mesh.state.newDevices.collect { Notifier.device(this@MeshService, it) } }
        handler.postDelayed(tick, 2_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // App wurde geöffnet -> Funk (neu) starten, z. B. nach erteilter Berechtigung,
        // und sofort "Ich bin da" senden
        startRadio()
        Mesh.router.announce()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        Mesh.transport = null
        try {
            nearby.stopAllEndpoints(); nearby.stopAdvertising(); nearby.stopDiscovery()
        } catch (_: Exception) { }
        connected.toList().forEach { Mesh.router.onDisconnected(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (ticks % ANNOUNCE_EVERY == 0) Mesh.router.announce()
            if (connected.isEmpty() && ticks % RESTART_EVERY == 0) {
                try { nearby.stopAdvertising(); nearby.stopDiscovery() } catch (_: Exception) { }
                startRadio()
            }
            // Gefundene, aber noch nicht verbundene Geräte erneut versuchen (egal wer "dran" ist)
            discovered.keys.filter { it !in connected && it !in pending }.forEach { connect(it) }
            updateNotification()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startRadio() {
        nearby.startAdvertising(endpointName, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { radioError.value = null }
            .addOnFailureListener { if (!it.alreadyRunning()) radioError.value = "Senden nicht möglich: ${it.message}" }
        nearby.startDiscovery(SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { if (!it.alreadyRunning()) radioError.value = "Suche nicht möglich: ${it.message}" }
    }

    private fun Exception.alreadyRunning() = message?.contains("ALREADY") == true || message?.contains("8001") == true ||
        message?.contains("8002") == true

    private fun connect(id: String) {
        pending += id
        nearby.requestConnection(endpointName, id, lifecycle)
            .addOnFailureListener { pending -= id }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            discovered[id] = info.endpointName
            // Nur eine Seite fragt sofort an, damit sich die Anfragen nicht kreuzen.
            // Die andere Seite versucht es beim nächsten Tick, falls nichts passiert.
            if (id !in connected && id !in pending && endpointName < info.endpointName) connect(id)
        }

        override fun onEndpointLost(id: String) {
            discovered -= id
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            pending += id
            // Automatisch annehmen: Inhalte sind ohnehin Ende-zu-Ende-verschlüsselt
            nearby.acceptConnection(id, payloads).addOnFailureListener { pending -= id }
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            pending -= id
            if (!result.status.isSuccess) return
            connected += id
            Mesh.router.onConnected(id)
            updateNotification()
        }

        override fun onDisconnected(id: String) {
            connected -= id
            pending -= id
            Mesh.router.onDisconnected(id)
            updateNotification()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            try {
                Mesh.router.onReceive(id, bytes)
            } catch (_: Exception) { }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private fun updateNotification() {
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(1, statusNotification())
        } catch (_: Exception) { }
    }

    private fun statusNotification(): Notification =
        NotificationCompat.Builder(this, Notifier.CH_STATUS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Notfall-Mesh aktiv")
            .setContentText(
                if (connected.isEmpty()) "Suche nach Geräten …"
                else "${connected.size} Gerät(e) direkt verbunden · leitet Nachrichten weiter"
            )
            .setOngoing(true)
            .setContentIntent(Notifier.openApp(this))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
}
