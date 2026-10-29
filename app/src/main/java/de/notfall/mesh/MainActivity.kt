package de.notfall.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions.launch(permissions)
        setContent { MaterialTheme { App() } }
    }

    private fun startMesh() {
        ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
    }

    @SuppressLint("MissingPermission")
    private fun lastPosition(): Pair<Double, Double>? = try {
        // GPS funktioniert auch ganz ohne Internet und Mobilfunk
        val lm = getSystemService(LocationManager::class.java)
        (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
            ?.let { it.latitude to it.longitude }
    } catch (_: Exception) {
        null
    }

    @Composable
    private fun App() {
        val prefs = remember { getSharedPreferences("contacts", Context.MODE_PRIVATE) }
        var contacts by remember { mutableStateOf(prefs.all.mapValues { it.value as String }) }
        val messages by MeshService.inbox.collectAsState()
        val peers by MeshService.peers.collectAsState()
        var selected by remember { mutableStateOf<String?>(null) }
        var text by remember { mutableStateOf("") }
        var newName by remember { mutableStateOf("") }
        var newId by remember { mutableStateOf("") }
        val myId by MeshService.myId.collectAsState()

        fun nameOf(id: String) = contacts.entries.firstOrNull { it.value == id }?.key
            ?: "Unbekannt (${Crypto.fingerprint(id)})"

        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("Notfall-Mesh", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Handys mit der App in Reichweite: $peers")

            Button(
                onClick = {
                    val pos = lastPosition()
                    MeshService.instance?.sendSos(text.ifBlank { "Ich brauche Hilfe!" }, pos?.first, pos?.second)
                    text = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(64.dp),
            ) { Text("SOS SENDEN (mit GPS-Standort)", fontSize = 18.sp) }

            myId?.let { myId ->
                Text("Mein Fingerabdruck: ${Crypto.fingerprint(myId)}")
                TextButton(onClick = {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Notfall-Mesh ID", myId))
                }) { Text("Meine ID kopieren (an Kontakte geben)") }
            }

            Row {
                OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("Name") })
                OutlinedTextField(newId, { newId = it }, Modifier.weight(2f), label = { Text("ID einfügen") })
            }
            TextButton(onClick = {
                if (newName.isNotBlank() && newId.isNotBlank()) {
                    prefs.edit().putString(newName.trim(), newId.trim()).apply()
                    contacts = prefs.all.mapValues { it.value as String }
                    newName = ""; newId = ""
                }
            }) { Text("Kontakt hinzufügen") }

            LazyRow {
                items(contacts.keys.toList()) { name ->
                    FilterChip(
                        selected = selected == contacts[name],
                        onClick = { selected = contacts[name] },
                        label = { Text(name) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text("Nachricht") })
                Button(
                    enabled = selected != null && text.isNotBlank(),
                    onClick = { MeshService.instance?.sendChat(selected!!, text); text = "" },
                ) { Text("Senden") }
            }

            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(messages.reversed()) { m ->
                    val who = when {
                        m.mine && m.sos -> "Ich (SOS)"
                        m.mine -> "Ich → ${nameOf(m.to!!)}"
                        m.sos -> "SOS von ${nameOf(m.from)}"
                        else -> nameOf(m.from)
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(if (m.sos) Color(0xFFFFCDD2) else Color(0xFFE8F5E9))
                            .padding(8.dp)
                    ) {
                        Text(who, fontWeight = FontWeight.Bold)
                        Text(m.text)
                        m.position?.let { Text("Standort: $it") }
                    }
                }
            }
        }
    }
}
