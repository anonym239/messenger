package de.notfall.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import java.io.File

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondaryContainer = Color(0xFFDDEFEC), onSecondaryContainer = Color(0xFF0E1F1D),
    background = Color(0xFFF6FAF9), onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF6FAF9), onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2), onSurfaceVariant = Color(0xFF3F4947),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF80CBC4), onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF004D40), onPrimaryContainer = Color(0xFFB2DFDB),
    secondaryContainer = Color(0xFF233B38), onSecondaryContainer = Color(0xFFDDEFEC),
    background = Color(0xFF0F1514), onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0F1514), onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947), onSurfaceVariant = Color(0xFFBEC9C6),
)

class MainActivity : ComponentActivity() {

    private val permissions = (MeshService.requiredPermissions() +
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Nach dem Erlauben den Funk komplett neu starten, damit alte Fehler verschwinden
            startMesh(restart = true)
        }

    private val askBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startMesh(restart = true) }

    private var onScanned: ((String) -> Unit)? = null
    private val scanner = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { onScanned?.invoke(it) }
        onScanned = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!MeshService.hasPermissions(this)) askPermissions.launch(permissions)

        val actions = UiActions(
            requestPermissions = { askPermissions.launch(permissions) },
            openAppSettings = {
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            enableBluetooth = ::enableBluetooth,
            openLocationSettings = { safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            startMesh = { startMesh(restart = true) },
            retryRadio = { startMesh(restart = true) },
            sendSos = { text ->
                val pos = lastPosition()
                try { Mesh.router.sendSos(text, pos?.first, pos?.second) } catch (_: Exception) { }
            },
            share = ::share,
            copy = { text ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Notfall-Mesh", text))
                Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
            },
            scanQr = { callback ->
                onScanned = callback
                try {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("QR-Code des anderen Handys scannen").setBeepEnabled(false).setOrientationLocked(false))
                } catch (_: Exception) {
                    Toast.makeText(this, "Kamera nicht verfügbar", Toast.LENGTH_SHORT).show()
                }
            },
        )

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                var issues by remember { mutableStateOf(RadioIssues()) }
                // Funkvoraussetzungen regelmäßig prüfen
                LaunchedEffect(Unit) {
                    while (true) {
                        issues = RadioIssues(!MeshService.hasPermissions(this@MainActivity), !bluetoothOn(), !locationOn())
                        delay(2000)
                    }
                }
                MeshApp(issues, actions)
                CrashReport()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Beim Öffnen sofort "Ich bin da" senden (und Dienst starten, falls nötig)
        startMesh(restart = false)
    }

    private fun startMesh(restart: Boolean) {
        if (Mesh.name.isBlank()) return
        MeshService.start(this, restart)
    }

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

    @SuppressLint("MissingPermission")
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
