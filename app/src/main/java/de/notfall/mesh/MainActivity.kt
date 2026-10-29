package de.notfall.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondaryContainer = Color(0xFFDDEFEC), onSecondaryContainer = Color(0xFF0E1F1D),
    background = Color(0xFFF6FAF9), surface = Color(0xFFF6FAF9),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF80CBC4), onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF004D40), onPrimaryContainer = Color(0xFFB2DFDB),
    secondaryContainer = Color(0xFF233B38), onSecondaryContainer = Color(0xFFDDEFEC),
    background = Color(0xFF0F1514), surface = Color(0xFF0F1514),
)

class MainActivity : ComponentActivity() {

    private val permissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startMesh() }

    private val askBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startMesh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!hasPermissions()) askPermissions.launch(permissions)

        val actions = UiActions(
            requestPermissions = { askPermissions.launch(permissions) },
            enableBluetooth = ::enableBluetooth,
            openLocationSettings = { safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            startMesh = ::startMesh,
            sendSos = { text ->
                val pos = lastPosition()
                try { Mesh.router.sendSos(text, pos?.first, pos?.second) } catch (_: Exception) { }
            },
            share = ::share,
        )

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                var issues by remember { mutableStateOf(RadioIssues()) }
                val radioError by MeshService.radioError.collectAsState()
                // Funkvoraussetzungen regelmäßig prüfen
                LaunchedEffect(Unit) {
                    while (true) {
                        issues = RadioIssues(!hasPermissions(), !bluetoothOn(), !locationOn(), null)
                        delay(3000)
                    }
                }
                MeshApp(issues.copy(error = radioError), actions)
                CrashReport()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Beim Öffnen sofort "Ich bin da" senden (und Dienst starten, falls nötig)
        startMesh()
    }

    private fun startMesh() {
        if (Mesh.name.isBlank()) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
        } catch (_: Exception) { }
    }

    private fun hasPermissions() = permissions
        .filter { it != Manifest.permission.POST_NOTIFICATIONS && it != Manifest.permission.ACCESS_COARSE_LOCATION }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun bluetoothOn(): Boolean = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled ?: true
    } catch (_: Exception) {
        true
    }

    private fun locationOn(): Boolean = try {
        val lm = getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
    } catch (_: Exception) {
        true
    }

    private fun enableBluetooth() {
        try {
            askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Exception) {
            safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (_: Exception) { }
    }

    private fun share(text: String) {
        safeStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    @SuppressLint("MissingPermission")
    private fun lastPosition(): Pair<Double, Double>? = try {
        // GPS funktioniert auch ganz ohne Internet und Mobilfunk
        val lm = getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    } catch (_: Exception) {
        null
    }

    /** Zeigt nach einem Absturz den Fehlerbericht an, damit er geteilt werden kann. */
    @Composable
    private fun CrashReport() {
        val file = remember { File(filesDir, App.CRASH_FILE) }
        var report by remember { mutableStateOf(if (file.exists()) file.readText() else null) }
        val r = report ?: return
        AlertDialog(
            onDismissRequest = { file.delete(); report = null },
            title = { Text("Die App ist abgestürzt") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Bitte teile diesen Bericht mit dem Entwickler, damit der Fehler behoben werden kann.")
                    Text(r.take(4000), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = { share(r); file.delete(); report = null }) { Text("Teilen") } },
            dismissButton = { TextButton(onClick = { file.delete(); report = null }) { Text("Schließen") } },
        )
    }
}
