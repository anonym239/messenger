@file:OptIn(ExperimentalMaterial3Api::class)

package de.notfall.mesh

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val SosRed = Color(0xFFD32F2F)
val OnlineGreen = Color(0xFF2E9D4F)

/** Probleme, die das Funken verhindern – werden oben als Hinweis angezeigt. */
data class RadioIssues(
    val missingPermissions: Boolean = false,
    val bluetoothOff: Boolean = false,
    val locationOff: Boolean = false,
    val error: String? = null,
)

class UiActions(
    val requestPermissions: () -> Unit,
    val enableBluetooth: () -> Unit,
    val openLocationSettings: () -> Unit,
    val startMesh: () -> Unit,
    val sendSos: (String) -> Unit,
    val share: (String) -> Unit,
)

// ====================================================================== Einstieg

@Composable
fun MeshApp(issues: RadioIssues, actions: UiActions) {
    var name by rememberSaveable { mutableStateOf(Mesh.name) }
    if (name.isBlank()) {
        Onboarding { n ->
            Mesh.name = n
            name = n
            actions.startMesh()
        }
    } else {
        MainLayout(issues, actions)
    }
}

@Composable
private fun Onboarding(onDone: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Radar(active = true, size = 140.dp)
            Spacer(Modifier.height(24.dp))
            Text("Notfall-Mesh", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                "Chatten ohne Internet, ohne Mobilfunk und ohne Server.\nHandys in der Nähe verbinden sich direkt und leiten Nachrichten füreinander weiter.",
                textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp).widthIn(max = 420.dp),
            )
            OutlinedTextField(
                input, { input = it.take(30) },
                label = { Text("Dein Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) onDone(input) }),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("nameField"),
            )
            Text("So sehen dich andere in ihrer Geräteliste.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = input.isNotBlank(),
                onClick = { onDone(input) },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp).testTag("startButton"),
            ) { Text("Los geht's", fontSize = 17.sp) }
        }
    }
}

// ====================================================================== Grundgerüst (Handy & Tablet)

@Composable
private fun MainLayout(issues: RadioIssues, actions: UiActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // "Neues Gerät in der Nähe" als Hinweis mit direktem Chat-Knopf
    LaunchedEffect(Unit) {
        Mesh.state.newDevices.collect { d ->
            val r = snackbar.showSnackbar("📱 ${d.name} ist in der Nähe", actionLabel = "Chatten",
                duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) { tab = 0; openPeer = d.id }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val listWidth = if (maxWidth > 900.dp) 380.dp else 320.dp
        val tabs = listOf(
            Triple("Chats", Icons.Default.Email, 0),
            Triple("SOS", Icons.Default.Warning, 1),
            Triple("Ich", Icons.Default.Person, 2),
        )

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    Spacer(Modifier.height(12.dp))
                    tabs.forEach { (label, icon, i) ->
                        NavigationRailItem(tab == i, { tab = i }, { Icon(icon, label) }, label = { Text(label) })
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(listWidth)) {
                                PeerListScreen(issues, actions, selected = openPeer, onOpen = { openPeer = it })
                            }
                            VerticalDivider()
                            Box(Modifier.weight(1f)) {
                                val p = openPeer
                                if (p != null) ChatScreen(p, showBack = false, onBack = { openPeer = null })
                                else EmptyState(Icons.Default.Email, "Wähle einen Chat",
                                    "Geräte in der Nähe erscheinen automatisch links.")
                            }
                        }
                        1 -> SosScreen(actions)
                        else -> MeScreen(actions)
                    }
                }
            }
        } else {
            val p = openPeer
            if (tab == 0 && p != null) {
                BackHandler { openPeer = null }
                ChatScreen(p, showBack = true, onBack = { openPeer = null })
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEach { (label, icon, i) ->
                                NavigationBarItem(tab == i, { tab = i }, { Icon(icon, label) }, label = { Text(label) })
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets(0),
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> PeerListScreen(issues, actions, selected = null, onOpen = { openPeer = it })
                            1 -> SosScreen(actions)
                            else -> MeScreen(actions)
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }
}

// ====================================================================== Geräte- & Chatliste

private data class PeerRow(val id: String, val name: String, val device: Device?, val last: ChatMessage?, val unread: Int)

@Composable
private fun PeerListScreen(issues: RadioIssues, actions: UiActions, selected: String?, onOpen: (String) -> Unit) {
    val devices by Mesh.state.devices.collectAsState()
    val messages by Mesh.state.messages.collectAsState()
    val connected by Mesh.state.connected.collectAsState()
    Mesh.state.lastRead.collectAsState().value
    val now = rememberNow()

    val ids = (devices.keys + messages.filter { !it.sos }.map { it.peer }).distinct()
    val rows = ids.map { id ->
        PeerRow(id, devices[id]?.name ?: "Unbekannt · ${Crypto.fingerprint(id)}", devices[id],
            messages.lastOrNull { it.peer == id && !it.sos }, Mesh.state.unread(id))
    }
    val online = rows.filter { it.device?.online(now) == true }.sortedBy { it.device!!.hops }
    val offline = rows.filter { it.device?.online(now) != true }.sortedByDescending { it.last?.ts ?: it.device?.lastSeen ?: 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Notfall-Mesh", fontWeight = FontWeight.Bold)
                        Text(
                            if (connected > 0) "$connected direkt verbunden · ${online.size} erreichbar" else "Suche nach Geräten …",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connected > 0) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = { Radar(active = true, size = 36.dp, modifier = Modifier.padding(end = 12.dp)) },
            )
        },
        contentWindowInsets = WindowInsets.statusBars,
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("peerList")) {
            item { IssueBanners(issues, actions) }
            if (rows.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Radar(active = true, size = 160.dp)
                        Text("Suche nach Geräten in der Nähe …", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp))
                        Text(
                            "Andere müssen Notfall-Mesh ebenfalls geöffnet haben. Sobald ein Handy gefunden wird, erscheint es hier und ihr könnt sofort chatten.",
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (online.isNotEmpty()) item { SectionHeader("In der Nähe · erreichbar") }
            items(online, key = { "on" + it.id }) { PeerItem(it, now, it.id == selected, onOpen) }
            if (offline.isNotEmpty()) item { SectionHeader("Früher gesehen") }
            items(offline, key = { "off" + it.id }) { PeerItem(it, now, it.id == selected, onOpen) }
        }
    }
}

@Composable
private fun PeerItem(r: PeerRow, now: Long, selected: Boolean, onOpen: (String) -> Unit) {
    val isOnline = r.device?.online(now) == true
    ListItem(
        modifier = Modifier.clickable { onOpen(r.id) }.testTag("peer_${r.name}"),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        leadingContent = { Avatar(r.name, isOnline) },
        headlineContent = { Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val sub = r.last?.let { (if (it.mine) "Du: " else "") + it.text } ?: reachText(r.device, now)
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                r.last?.let { Text(time(it.ts), style = MaterialTheme.typography.labelSmall) }
                if (r.unread > 0) {
                    Box(
                        Modifier.padding(top = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) { Text("${r.unread}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }
                }
            }
        },
    )
}

private fun reachText(d: Device?, now: Long): String = when {
    d == null -> "Noch nicht gesehen"
    !d.online(now) -> "Zuletzt da: ${time(d.lastSeen)}"
    d.hops == 0 -> "Direkt verbunden"
    d.hops == 1 -> "Erreichbar über 1 anderes Handy"
    else -> "Erreichbar über ${d.hops} andere Handys"
}

@Composable
private fun IssueBanners(issues: RadioIssues, actions: UiActions) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        if (issues.missingPermissions) Banner(Icons.Default.Lock, "Berechtigungen fehlen",
            "Ohne „Geräte in der Nähe“ kann die App nicht funken.", "Erlauben", actions.requestPermissions)
        if (issues.bluetoothOff) Banner(Icons.Default.Settings, "Bluetooth ist aus",
            "Auch im Flugmodus kannst du Bluetooth und WLAN wieder einschalten – das reicht, Internet ist nicht nötig.",
            "Einschalten", actions.enableBluetooth)
        if (issues.locationOff) Banner(Icons.Default.LocationOn, "Standort ist aus",
            "Viele Handys finden andere Geräte nur mit eingeschaltetem Standort (GPS braucht kein Internet).",
            "Öffnen", actions.openLocationSettings)
        issues.error?.let { Banner(Icons.Default.Warning, "Funkproblem", it, null, null) }
    }
}

@Composable
private fun Banner(icon: ImageVector, title: String, text: String, action: String?, onAction: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

// ====================================================================== Chat

@Composable
private fun ChatScreen(peer: String, showBack: Boolean, onBack: () -> Unit) {
    val devices by Mesh.state.devices.collectAsState()
    val all by Mesh.state.messages.collectAsState()
    val now = rememberNow()
    val d = devices[peer]
    val name = d?.name ?: "Unbekannt"
    val chat = all.filter { it.peer == peer && !it.sos }
    var text by rememberSaveable(peer) { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(peer, chat.size) {
        Mesh.state.markRead(peer)
        if (chat.isNotEmpty()) listState.animateScrollToItem(0)
    }

    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        try {
            Mesh.router.sendText(peer, t)
            text = ""
        } catch (_: Exception) { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, d?.online(now) == true, size = 38.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(reachText(d, now), style = MaterialTheme.typography.labelMedium,
                                color = if (d?.online(now) == true) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = { IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, "Info") } },
            )
        },
        contentWindowInsets = WindowInsets.statusBars,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().navigationBarsPadding().imePadding()) {
            if (d?.online(now) != true) {
                Text(
                    "$name ist gerade nicht erreichbar. Nachrichten werden gespeichert und automatisch zugestellt, sobald eine Verbindung (auch über andere Handys) besteht.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState, reverseLayout = true,
            ) {
                items(chat.reversed(), key = { it.mid }) { Bubble(it) }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                        Text("🔒 Ende-zu-Ende verschlüsselt", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text, { text = it }, Modifier.weight(1f).testTag("messageField"),
                    placeholder = { Text("Nachricht") }, shape = RoundedCornerShape(24.dp), maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    enabled = text.isNotBlank(), onClick = { send() },
                    modifier = Modifier.size(52.dp).testTag("sendButton"),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Senden") }
            }
        }
    }

    if (showInfo) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text(name) },
        text = {
            Column {
                Text("Sicherheitsnummer:")
                Text(Crypto.fingerprint(peer), fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Vergleiche sie mit dem Handy von $name (dort unter „Ich“). Stimmt sie überein, liest garantiert niemand mit.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } },
    )
}

@Composable
private fun Bubble(m: ChatMessage) {
    val bg = if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (m.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(0.82f).wrapContentWidth(if (m.mine) Alignment.End else Alignment.Start)
                .clip(RoundedCornerShape(18.dp, 18.dp, if (m.mine) 4.dp else 18.dp, if (m.mine) 18.dp else 4.dp))
                .background(bg).padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(m.text, color = fg, fontSize = 16.sp)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(time(m.ts), color = fg.copy(alpha = 0.75f), fontSize = 11.sp)
                if (m.mine) {
                    Text(
                        when (m.status) { Status.WAITING -> "  🕓"; Status.RELAYED -> "  ✓"; Status.DELIVERED -> "  ✓✓" },
                        color = fg.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ====================================================================== SOS

@Composable
private fun SosScreen(actions: UiActions) {
    val all by Mesh.state.messages.collectAsState()
    val sos = all.filter { it.sos }.reversed()
    var confirm by remember { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    val pulse = rememberInfiniteTransition(label = "puls")
        .animateFloat(1f, 1.07f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "puls")

    Scaffold(
        topBar = { TopAppBar(title = { Text("Notruf", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets.statusBars,
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.size(190.dp).scale(pulse.value).clip(CircleShape).background(SosRed)
                        .clickable { confirm = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SOS", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black)
                        Text("Notruf senden", color = Color.White)
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Was ist passiert? (optional)") },
                    modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth())
                Text(
                    "Geht mit GPS-Standort an ALLE Handys mit Notfall-Mesh in Reichweite und wird von ihnen weitergeleitet.\n" +
                        "Versuche immer zuerst die 112 – sie funktioniert auch ohne SIM-Karte über jedes Netz.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 520.dp),
                )
                if (sos.isNotEmpty()) SectionHeader("Notrufe")
            }
            items(sos, key = { it.mid }) { m ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SosRed.copy(alpha = 0.12f)),
                    modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = SosRed)
                            Text(if (m.mine) "Dein Notruf" else (m.senderName ?: "Unbekannt"), fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Text(time(m.ts), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(m.text, Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp))
                            Text(m.position ?: "Standort unbekannt", fontFamily = FontFamily.Monospace)
                        }
                        m.position?.let { pos ->
                            TextButton(onClick = { actions.share("NOTRUF von ${m.senderName ?: "?"}: ${m.text}\nStandort: $pos\nhttps://maps.google.com/?q=${pos.replace(" ", "")}") }) {
                                Text("Notruf weitergeben / teilen")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        icon = { Icon(Icons.Default.Warning, null, tint = SosRed) },
        title = { Text("Notruf wirklich senden?") },
        text = { Text("Alle Handys mit Notfall-Mesh in der Nähe werden alarmiert und sehen deinen Standort.") },
        confirmButton = {
            Button(colors = ButtonDefaults.buttonColors(containerColor = SosRed), onClick = {
                actions.sendSos(note.ifBlank { "Ich brauche Hilfe!" })
                note = ""; confirm = false
            }) { Text("SOS senden") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
    )
}

// ====================================================================== Ich

@Composable
private fun MeScreen(actions: UiActions) {
    val connected by Mesh.state.connected.collectAsState()
    val devices by Mesh.state.devices.collectAsState()
    var name by rememberSaveable { mutableStateOf(Mesh.name) }
    val now = rememberNow()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ich", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets.statusBars,
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Card(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(name, true, size = 72.dp)
                        OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true,
                            modifier = Modifier.padding(top = 12.dp).fillMaxWidth())
                        TextButton(enabled = name.isNotBlank() && name != Mesh.name, onClick = {
                            Mesh.name = name; Mesh.router.announce()
                        }) { Text("Name speichern") }
                        Text("Deine Sicherheitsnummer", style = MaterialTheme.typography.titleSmall)
                        Text(Crypto.fingerprint(Mesh.router.myId), fontFamily = FontFamily.Monospace,
                            fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Card(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Netz-Status", style = MaterialTheme.typography.titleMedium)
                        Text("Direkt verbunden: $connected")
                        Text("Erreichbar (auch über andere): ${devices.values.count { it.online(now) }}")
                        Text("Bekannte Geräte: ${devices.size}")
                        Text("Zwischengespeicherte Pakete: ${Mesh.router.storedCount}")
                    }
                }
            }
            item {
                Card(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("So funktioniert's", style = MaterialTheme.typography.titleMedium)
                        Text("• Kein Server, kein Internet: Dein Handy ist selbst der Server und leitet Nachrichten anderer weiter.")
                        Text("• Flugmodus ist ok – Bluetooth (und am besten WLAN) danach wieder einschalten.")
                        Text("• Reichweite pro Handy: ca. 30–150 m. Jedes weitere Handy verlängert die Strecke (bis 15 Sprünge).")
                        Text("• Ist niemand erreichbar, wird die Nachricht bis zu 24 h gespeichert und später zugestellt.")
                        Text("• 🕓 wartet · ✓ weitergegeben · ✓✓ beim Empfänger angekommen")
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ====================================================================== Bausteine

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
fun Avatar(name: String, online: Boolean, size: androidx.compose.ui.unit.Dp = 46.dp) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(avatarColor(name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        }
        if (online) Box(
            Modifier.size(size * 0.28f).align(Alignment.BottomEnd).clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape).background(OnlineGreen)
        )
    }
}

private fun avatarColor(name: String): Color {
    val palette = listOf(0xFF00796B, 0xFF5E35B1, 0xFF1E88E5, 0xFFD81B60, 0xFFF4511E, 0xFF6D4C41, 0xFF3949AB, 0xFF7CB342)
    return Color(palette[Math.floorMod(name.hashCode(), palette.size)])
}

/** Pulsierende Kreise: "Suche läuft". */
@Composable
fun Radar(active: Boolean, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "radar")
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (active) for (k in 0..1) {
            val f = (p + k * 0.5f) % 1f
            Box(Modifier.fillMaxSize(f.coerceAtLeast(0.01f)).alpha(1f - f).clip(CircleShape).background(color.copy(alpha = 0.35f)))
        }
        Box(Modifier.fillMaxSize(0.3f).clip(CircleShape).background(color))
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Aktuelle Zeit, die sich alle 15 s aktualisiert (für "online"-Anzeige). */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); now = System.currentTimeMillis() }
    }
    return now
}

fun time(ts: Long): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ts))
