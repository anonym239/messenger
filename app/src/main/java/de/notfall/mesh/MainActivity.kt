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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SosRed = Color(0xFFD32F2F)
private val MeshGreen = Color(0xFF2E7D32)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondaryContainer = Color(0xFFE0F2F1),
    background = Color(0xFFF6FAF9), surface = Color(0xFFF6FAF9),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF80CBC4), onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF004D40), onPrimaryContainer = Color(0xFFB2DFDB),
    secondaryContainer = Color(0xFF1E3532),
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askPermissions.launch(permissions)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                App()
            }
        }
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

    // ---------------------------------------------------------------- Hauptgerüst

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val prefs = remember { getSharedPreferences("contacts", Context.MODE_PRIVATE) }
        var contacts by remember { mutableStateOf(prefs.all.mapValues { it.value as String }) }
        val messages by MeshService.inbox.collectAsState()
        val peers by MeshService.peers.collectAsState()
        val myId by MeshService.myId.collectAsState()
        var tab by remember { mutableIntStateOf(0) }
        var openChat by remember { mutableStateOf<String?>(null) }   // Kontakt-ID

        fun nameOf(id: String) = contacts.entries.firstOrNull { it.value == id }?.key
            ?: "Unbekannt · ${Crypto.fingerprint(id)}"

        fun saveContact(name: String, id: String) {
            prefs.edit().putString(name.trim(), id.trim()).apply()
            contacts = prefs.all.mapValues { it.value as String }
        }

        BackHandler(enabled = openChat != null) { openChat = null }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (openChat != null) IconButton(onClick = { openChat = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                        }
                    },
                    title = {
                        Column {
                            Text(openChat?.let { nameOf(it) } ?: "Notfall-Mesh", fontWeight = FontWeight.Bold)
                            Text(
                                if (openChat != null) "Ende-zu-Ende verschlüsselt" else "Ohne Internet · ohne Server",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                    actions = { PeerBadge(peers) },
                )
            },
            bottomBar = {
                if (openChat == null) NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Email, null) }, label = { Text("Chats") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Warning, null) }, label = { Text("SOS") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Person, null) }, label = { Text("Ich") })
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    openChat != null -> ChatScreen(openChat!!, messages)
                    tab == 0 -> ChatList(contacts, messages, ::nameOf, onOpen = { openChat = it }, onAdd = ::saveContact)
                    tab == 1 -> SosScreen(messages, ::nameOf)
                    else -> MeScreen(myId, peers)
                }
            }
        }
    }

    @Composable
    private fun PeerBadge(peers: Int) {
        val color = if (peers > 0) MeshGreen else Color.Gray
        Row(
            Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text("$peers in Reichweite", color = color, style = MaterialTheme.typography.labelMedium)
        }
    }

    // ---------------------------------------------------------------- Chat-Liste

    @Composable
    private fun ChatList(
        contacts: Map<String, String>,
        messages: List<ChatMessage>,
        nameOf: (String) -> String,
        onOpen: (String) -> Unit,
        onAdd: (String, String) -> Unit,
    ) {
        var showAdd by remember { mutableStateOf(false) }
        // Auch Nachrichten von noch unbekannten Absendern anzeigen
        val ids = (contacts.values + messages.filter { !it.sos }.map { if (it.mine) it.to!! else it.from }).distinct()

        Box(Modifier.fillMaxSize()) {
            if (ids.isEmpty()) {
                EmptyHint(Icons.Default.Email, "Noch keine Kontakte", "Tippe auf +, um die ID eines Freundes hinzuzufügen.")
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(ids) { id ->
                    val last = messages.lastOrNull { !it.sos && (it.from == id || it.to == id) }
                    ListItem(
                        modifier = Modifier.clickable { onOpen(id) },
                        leadingContent = { Avatar(nameOf(id)) },
                        headlineContent = { Text(nameOf(id), fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text(last?.let { (if (it.mine) "Du: " else "") + it.text } ?: "Noch keine Nachrichten", maxLines = 1)
                        },
                        trailingContent = { last?.let { Text(time(it.ts), style = MaterialTheme.typography.labelSmall) } },
                    )
                    HorizontalDivider(Modifier.padding(start = 72.dp))
                }
            }
            FloatingActionButton(
                onClick = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Default.Add, "Kontakt hinzufügen") }
        }

        if (showAdd) AddContactDialog(onDismiss = { showAdd = false }, onAdd = { n, i -> onAdd(n, i); showAdd = false })
    }

    @Composable
    private fun AddContactDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
        var name by remember { mutableStateOf("") }
        var id by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Kontakt hinzufügen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(id, { id = it }, label = { Text("ID einfügen") }, maxLines = 3)
                    if (id.isNotBlank()) Text("Fingerabdruck: ${Crypto.fingerprint(id.trim())}\nVergleiche ihn mit dem Handy deines Kontakts.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && id.isNotBlank(), onClick = { onAdd(name, id) }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        )
    }

    // ---------------------------------------------------------------- Einzelner Chat

    @Composable
    private fun ChatScreen(contactId: String, messages: List<ChatMessage>) {
        val chat = messages.filter { !it.sos && (it.from == contactId || it.to == contactId) }
        var text by remember { mutableStateOf("") }

        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), reverseLayout = true) {
                items(chat.reversed()) { m -> Bubble(m) }
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text, { text = it }, Modifier.weight(1f),
                    placeholder = { Text("Nachricht") }, shape = RoundedCornerShape(24.dp), maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    enabled = text.isNotBlank(),
                    onClick = { MeshService.instance?.sendChat(contactId, text.trim()); text = "" },
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Senden") }
            }
        }
    }

    @Composable
    private fun Bubble(m: ChatMessage) {
        val bg = if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
        val fg = if (m.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, if (m.mine) 4.dp else 18.dp, if (m.mine) 18.dp else 4.dp))
                    .background(bg).padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(m.text, color = fg)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(10.dp), tint = fg.copy(alpha = 0.6f))
                    Spacer(Modifier.width(3.dp))
                    Text(time(m.ts), color = fg.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        }
    }

    // ---------------------------------------------------------------- SOS

    @Composable
    private fun SosScreen(messages: List<ChatMessage>, nameOf: (String) -> String) {
        val sos = messages.filter { it.sos }
        var confirm by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        val pulse = rememberInfiniteTransition(label = "puls")
            .animateFloat(1f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "puls")

        LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.size(200.dp).scale(pulse.value).clip(CircleShape)
                        .background(SosRed).clickable { confirm = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SOS", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black)
                        Text("Notruf senden", color = Color.White)
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Was ist passiert? (optional)") },
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth())
                Text(
                    "Wird mit GPS-Standort an ALLE Handys mit dieser App geschickt und von ihnen weitergeleitet.\n" +
                        "Versuche immer zuerst die 112. Sie funktioniert auch ohne SIM-Karte und über jedes Netz.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(24.dp),
                )
                if (sos.isNotEmpty()) Text("Empfangene Notrufe", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            items(sos.reversed()) { m ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SosRed.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = SosRed)
                            Spacer(Modifier.width(8.dp))
                            Text(if (m.mine) "Dein Notruf" else nameOf(m.from), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(time(m.ts), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(m.text, Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp))
                            Text(m.position ?: "Standort unbekannt", fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = SosRed) },
            title = { Text("Notruf wirklich senden?") },
            text = { Text("Alle Handys mit Notfall-Mesh in der Nähe werden alarmiert und sehen deinen Standort.") },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(containerColor = SosRed), onClick = {
                    val pos = lastPosition()
                    MeshService.instance?.sendSos(note.ifBlank { "Ich brauche Hilfe!" }, pos?.first, pos?.second)
                    note = ""; confirm = false
                }) { Text("SOS senden") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
    }

    // ---------------------------------------------------------------- Ich

    @Composable
    private fun MeScreen(myId: String?, peers: Int) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Dein Fingerabdruck", style = MaterialTheme.typography.titleMedium)
                        Text(myId?.let { Crypto.fingerprint(it) } ?: "wird erzeugt …",
                            fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Vergleiche ihn persönlich mit deinen Kontakten.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = myId != null, onClick = {
                                getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("Notfall-Mesh ID", myId))
                                Toast.makeText(this@MainActivity, "ID kopiert", Toast.LENGTH_SHORT).show()
                            }) { Text("ID kopieren") }
                            Button(enabled = myId != null, onClick = {
                                startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, myId),
                                    "ID teilen"))
                            }) {
                                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Teilen")
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("So funktioniert's", style = MaterialTheme.typography.titleMedium)
                        Text("• Kein Server, kein Internet: Dein Handy IST der Server und leitet Nachrichten anderer weiter.")
                        Text("• Reichweite pro Handy: ca. 50–150 m (Bluetooth / WLAN-Direkt).")
                        Text("• Jedes weitere Handy mit der App verlängert die Strecke (bis zu 15 Sprünge).")
                        Text("• Ist niemand in der Nähe, wird die Nachricht bis zu 24 h gespeichert und später weitergegeben.")
                        Text("• Gerade verbunden: $peers Handy(s).", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Helfer

    @Composable
    private fun Avatar(name: String) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }

    @Composable
    private fun EmptyHint(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        }
    }

    private fun time(ts: Long) = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ts))
}
