package de.notfall.mesh

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Zustand des Funks, wird oben in der App angezeigt. */
data class RadioState(
    val advertising: Boolean = false,   // andere können mich finden
    val discovering: Boolean = false,   // ich suche andere
    val error: String? = null,          // verständliche Fehlermeldung
)

/**
 * Funk-Schicht: Google Nearby Connections über Bluetooth, BLE und WLAN-Direkt.
 * Funktioniert ohne Internet und ohne Mobilfunk – auch im Flugmodus, solange
 * Bluetooth (und am besten WLAN) eingeschaltet ist.
 */
class MeshService : Service() {

    companion object {
        const val SERVICE_ID = "de.notfall.mesh.v1"
        const val ACTION_RESTART = "de.notfall.mesh.RESTART"
        private const val TICK_MS = 10_000L
        private const val ANNOUNCE_EVERY = 6        // alle 60 s HELLO
        private const val RESTART_EVERY = 12        // alle 2 min Suche neu starten, wenn allein
        val radio = MutableStateFlow(RadioState())

        fun start(ctx: Context, restart: Boolean = false) {
            try {
                val i = Intent(ctx, MeshService::class.java)
                if (restart) i.action = ACTION_RESTART
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) { }
        }

        /** Berechtigungen, ohne die Nearby nicht funken kann. */
        fun requiredPermissions(): List<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        fun hasPermissions(ctx: Context) = requiredPermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private lateinit var nearby: ConnectionsClient
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connected = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private val discovered = mutableMapOf<String, String>()   // Endpoint -> Funkname
    private val strategy = Strategy.P2P_CLUSTER                // viele-zu-viele, ideal für Mesh
    private var ticks = 0
    private var radioStarted = false

    // Zufälliger Funkname pro Start: Fremde können Personen nicht über den Funknamen verfolgen
    private val endpointName = UUID.randomUUID().toString().take(8)

    override fun onCreate() {
        super.onCreate()
        goForeground()
        nearby = Nearby.getConnectionsClient(this)
        Mesh.transport = { eps, data ->
            try {
                nearby.sendPayload(eps, Payload.fromBytes(data))
            } catch (_: Exception) { }
        }
        scope.launch { Mesh.state.newDevices.collect { Notifier.device(this@MeshService, it) } }
        handler.postDelayed(tick, 2_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Bei jedem Öffnen der App: Vordergrund-Typ aktualisieren (Standort evtl. neu erlaubt),
        // Funk starten bzw. nach neuer Berechtigung komplett neu starten, "Ich bin da" senden
        goForeground()
        if (intent?.action == ACTION_RESTART || !radioStarted || radio.value.error != null) restartRadio()
        Mesh.router.announce()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        Mesh.transport = null
        stopRadio()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val locationOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // Typ "location" erlaubt dem Funk, auch im Hintergrund nach Geräten zu suchen
        val withLocation = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, 1, statusNotification(),
                if (locationOk) withLocation else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            try {
                ServiceCompat.startForeground(this, 1, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (_: Exception) { }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (ticks % ANNOUNCE_EVERY == 0) Mesh.router.announce()
            when {
                // Fehler (z. B. Bluetooth war aus): alle 30 s automatisch neu versuchen
                radio.value.error != null && ticks % 3 == 0 -> restartRadio()
                connected.isEmpty() && ticks % RESTART_EVERY == 0 -> restartRadio()
            }
            // Gefundene, aber noch nicht verbundene Geräte erneut versuchen (egal wer "dran" ist)
            discovered.keys.filter { it !in connected && it !in pending }.forEach { connect(it) }
            updateNotification()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun stopRadio() {
        try {
            nearby.stopAdvertising(); nearby.stopDiscovery(); nearby.stopAllEndpoints()
        } catch (_: Exception) { }
        connected.clear(); pending.clear(); discovered.clear()
        Mesh.router.onAllDisconnected()
        radioStarted = false
        radio.value = RadioState()
    }

    private fun restartRadio() {
        if (!hasPermissions(this)) {
            radio.value = RadioState(error = "Berechtigung „Geräte in der Nähe“ bzw. „Standort“ fehlt.")
            return
        }
        // Bestehende Verbindungen behalten, nur Werbung/Suche neu starten
        try { nearby.stopAdvertising(); nearby.stopDiscovery() } catch (_: Exception) { }
        radioStarted = true
        radio.value = radio.value.copy(advertising = false, discovering = false)

        nearby.startAdvertising(endpointName, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { radio.value = radio.value.copy(advertising = true, error = errorIfAnyStopped(adv = true)) }
            .addOnFailureListener { e -> onRadioFailure(e, advertising = true) }
        nearby.startDiscovery(SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { radio.value = radio.value.copy(discovering = true, error = errorIfAnyStopped(disc = true)) }
            .addOnFailureListener { e -> onRadioFailure(e, advertising = false) }
    }

    /** Fehler nur behalten, solange noch ein Teil nicht läuft. */
    private fun errorIfAnyStopped(adv: Boolean = false, disc: Boolean = false): String? {
        val r = radio.value
        return if ((r.advertising || adv) && (r.discovering || disc)) null else r.error
    }

    private fun onRadioFailure(e: Exception, advertising: Boolean) {
        val code = (e as? ApiException)?.statusCode
        if (code == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || code == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
            radio.value = if (advertising) radio.value.copy(advertising = true) else radio.value.copy(discovering = true)
            return
        }
        radio.value = radio.value.copy(error = friendlyError(code, e))
    }

    private fun friendlyError(code: Int?, e: Exception): String = when (code) {
        in 8030..8040 -> "Android hat eine Berechtigung noch nicht freigegeben. Öffne die App-Einstellungen und erlaube „Geräte in der Nähe“ und „Standort“ (genau). Es wird automatisch neu versucht."
        ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth-Fehler. Bluetooth aus- und wieder einschalten."
        ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "Bluetooth oder WLAN ist ausgeschaltet. Bitte einschalten (geht auch im Flugmodus)."
        ConnectionsStatusCodes.API_CONNECTION_FAILED_ALREADY_IN_USE -> "Eine andere App nutzt den Nahfunk gerade."
        else -> "Funk konnte nicht starten (${code ?: e.javaClass.simpleName}). Es wird automatisch neu versucht."
    }

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
