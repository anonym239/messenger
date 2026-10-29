@file:OptIn(ExperimentalMaterial3Api::class)

package de.notfall.mesh

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val SosRed = Color(0xFFD32F2F)
val OnlineGreen = Color(0xFF2E9D4F)
val Amber = Color(0xFFFFB300)

/** Probleme, die das Funken verhindern – werden oben als Hinweis angezeigt. */
data class RadioIssues(
    val missingPermissions: Boolean = false,
    val bluetoothOff: Boolean = false,
    val locationOff: Boolean = false,
)

class UiActions(
    val requestPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val enableBluetooth: () -> Unit,
    val openLocationSettings: () -> Unit,
    val startMesh: () -> Unit,
    val retryRadio: () -> Unit,
    val sendSos: (String) -> Unit,
    val share: (String) -> Unit,
    val copy: (String) -> Unit,
    val scanQr: ((String) -> Unit) -> Unit,
)

/** Einladungscode: enthält Name und öffentliche ID. Wird als QR-Code oder Text geteilt. */
object Invite {
    private const val PREFIX = "NMESH1|"
    fun create(name: String, id: String) = "$PREFIX${name.replace("|", " ")}|$id"

    /** Liest Einladungscode oder nackte ID. Liefert (Name, ID). */
    fun parse(text: String): Pair<String, String> {
        val t = text.trim()
        val start = t.indexOf(PREFIX)
        if (start >= 0) {
            val parts = t.substring(start + PREFIX.length).split("|", limit = 2)
            if (parts.size == 2) return parts[0].trim() to parts[1].trim().split(Regex("\\s"))[0]
        }
        return "" to t
    }
}

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
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { delay(300); runCatching { focus.requestFocus() } }
    // Tastatur offen -> nach unten scrollen, damit Eingabefeld und Knopf sichtbar bleiben
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeOpen, scroll.maxValue) { if (imeOpen) scroll.animateScrollTo(scroll.maxValue) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppLogo(96.dp)
            Spacer(Modifier.height(16.dp))
            Text("Notfall-Mesh", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Chatten ohne Internet, ohne Mobilfunk und ohne Server. Handys in der Nähe verbinden sich direkt und leiten Nachrichten füreinander weiter.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp).widthIn(max = 440.dp),
            )
            Spacer(Modifier.height(8.dp))
            // Vorschau: so sehen dich andere
            Card(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(input.ifBlank { "?" }, online = true, size = 52.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(input.ifBlank { "Dein Name" }, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("So sehen dich andere in der Nähe", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 30) input = it },
                label = { Text("Dein Name") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) onDone(input.trim()) }),
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().focusRequester(focus).testTag("nameField"),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = input.isNotBlank(),
                onClick = { onDone(input.trim()) },
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().height(52.dp).testTag("startButton"),
            ) { Text("Los geht's", fontSize = 17.sp) }
        }
    }
}

// ====================================================================== Grundgerüst (Handy & Tablet)

@Composable
private fun MainLayout(issues: RadioIssues, actions: UiActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // "Neues Gerät in der Nähe" als Hinweis mit direktem Chat-Knopf
    LaunchedEffect(Unit) {
        Mesh.state.newDevices.collect { d ->
            val r = snackbar.showSnackbar("${d.display} ist in der Nähe", actionLabel = "Chatten",
                duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) { tab = 0; openPeer = d.id }
        }
    }

    val tabs = listOf(
        Triple("Chats", Icons.Default.Email, 0),
        Triple("SOS", Icons.Default.Warning, 1),
        Triple("Ich", Icons.Default.Person, 2),
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 600.dp
        val listWidth = if (maxWidth > 900.dp) 380.dp else 320.dp
        val inChat = !wide && tab == 0 && openPeer != null

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(header = { Spacer(Modifier.height(8.dp)); AppLogo(40.dp) }) {
                    Spacer(Modifier.height(12.dp))
                    tabs.forEach { (label, icon, i) ->
                        NavigationRailItem(tab == i, { tab = i }, { Icon(icon, label) }, label = { Text(label) })
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(listWidth)) {
                                PeerListScreen(issues, actions, selected = openPeer, onOpen = { openPeer = it }, onAdd = { showAdd = true })
                            }
                            VerticalDivider()
                            Box(Modifier.weight(1f)) {
                                val p = openPeer
                                if (p != null) ChatScreen(p, showBack = false, onBack = { openPeer = null })
                                else EmptyState(Icons.Default.Email, "Wähle einen Chat",
                                    "Geräte in der Nähe erscheinen automatisch links. Mit + fügst du Kontakte von Hand hinzu.")
                            }
                        }
                        1 -> SosScreen(actions)
                        else -> MeScreen(actions)
                    }
                }
            }
        } else {
            val p = openPeer
            if (inChat && p != null) {
                BackHandler { openPeer = null }
                ChatScreen(p, showBack = true, onBack = { openPeer = null })
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEach { (label, icon, i) ->
                                NavigationBarItem(tab == i, { tab = i }, { Icon(icon, label) }, label = { Text(label) },
                                    modifier = Modifier.testTag("tab_$label"))
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets(0),
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> PeerListScreen(issues, actions, selected = null, onOpen = { openPeer = it }, onAdd = { showAdd = true })
                            1 -> SosScreen(actions)
                            else -> MeScreen(actions)
                        }
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (wide) 16.dp else 88.dp),
        )
    }

    if (showAdd) AddContactDialog(actions, onDismiss = { showAdd = false }, onAdded = { id ->
        showAdd = false; tab = 0; openPeer = id
    })
}

// ====================================================================== Geräte- & Chatliste

private data class PeerRow(val id: String, val name: String, val device: Device?, val last: ChatMessage?, val unread: Int)

@Composable
private fun PeerListScreen(
    issues: RadioIssues, actions: UiActions, selected: String?,
    onOpen: (String) -> Unit, onAdd: () -> Unit,
) {
    val devices by Mesh.state.devices.collectAsState()
    val messages by Mesh.state.messages.collectAsState()
    val connected by Mesh.state.connected.collectAsState()
    val radio by MeshService.radio.collectAsState()
    Mesh.state.lastRead.collectAsState().value
    val now = rememberNow()

    val ids = (devices.keys + messages.filter { !it.sos && it.peer != PUBLIC_CHANNEL }.map { it.peer }).distinct()
    val rows = ids.map { id ->
        PeerRow(id, devices[id]?.display ?: "Unbekannt · ${Crypto.fingerprint(id)}", devices[id],
            messages.lastOrNull { it.peer == id && !it.sos }, Mesh.state.unread(id))
    }
    val online = rows.filter { it.device?.online(now) == true }.sortedBy { it.device!!.hops }
    val offline = rows.filter { it.device?.online(now) != true }
        .sortedByDescending { it.last?.ts ?: it.device?.lastSeen ?: 0 }
    val lastPublic = messages.lastOrNull { it.peer == PUBLIC_CHANNEL }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Notfall-Mesh", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                connected > 0 -> "$connected direkt verbunden · ${online.size} erreichbar"
                                radio.advertising && radio.discovering -> "Funk aktiv · suche Geräte …"
                                else -> "Funk startet …"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connected > 0) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Radar(active = radio.discovering, size = 34.dp)
                    IconButton(onClick = onAdd, modifier = Modifier.testTag("addContact")) {
                        Icon(Icons.Default.Add, "Kontakt hinzufügen")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Kontakt") },
            )
        },
        contentWindowInsets = WindowInsets.statusBars,
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().testTag("peerList"), contentPadding = PaddingValues(bottom = 88.dp)) {
            item { IssueBanners(issues, radio, actions) }
            item {
                ChannelItem(lastPublic, Mesh.state.unread(PUBLIC_CHANNEL), selected == PUBLIC_CHANNEL) { onOpen(PUBLIC_CHANNEL) }
            }
            if (online.isNotEmpty()) item { SectionHeader("In der Nähe · erreichbar (${online.size})") }
            items(online, key = { "on" + it.id }) { PeerItem(it, now, it.id == selected, onOpen) }
            if (offline.isNotEmpty()) item { SectionHeader("Kontakte & früher gesehen") }
            items(offline, key = { "off" + it.id }) { PeerItem(it, now, it.id == selected, onOpen) }
            if (rows.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Radar(active = true, size = 140.dp)
                        Text("Suche nach Geräten in der Nähe …", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
                        Text(
                            "Andere müssen Notfall-Mesh ebenfalls geöffnet haben. Gefundene Handys erscheinen hier automatisch. " +
                                "Du kannst Kontakte auch mit + per QR-Code hinzufügen.",
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelItem(last: ChatMessage?, unread: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).testTag("channel"),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        leadingContent = {
            Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
        headlineContent = { Text("Alle in der Nähe", fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(last?.let { "${if (it.mine) "Du" else it.senderName}: ${it.text}" } ?: "Öffentlicher Kanal für alle Handys im Netz",
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = { TrailingInfo(last, unread) },
    )
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
        trailingContent = { TrailingInfo(r.last, r.unread) },
    )
}

@Composable
private fun TrailingInfo(last: ChatMessage?, unread: Int) {
    Column(horizontalAlignment = Alignment.End) {
        last?.let { Text(shortTime(it.ts), style = MaterialTheme.typography.labelSmall) }
        if (unread > 0) {
            Box(
                Modifier.padding(top = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) { Text("$unread", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp) }
        }
    }
}

private fun reachText(d: Device?, now: Long): String = when {
    d == null -> "Noch nicht gesehen"
    d.lastSeen == 0L -> "Noch nicht in Reichweite gewesen"
    !d.online(now) -> "Zuletzt da: ${shortTime(d.lastSeen)}"
    d.hops == 0 -> "Direkt verbunden"
    d.hops == 1 -> "Erreichbar über 1 anderes Handy"
    else -> "Erreichbar über ${d.hops} andere Handys"
}

@Composable
private fun IssueBanners(issues: RadioIssues, radio: RadioState, actions: UiActions) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        when {
            issues.missingPermissions -> Banner(Icons.Default.Lock, "Berechtigungen fehlen",
                "Ohne „Geräte in der Nähe“ und „Standort“ kann die App nicht funken.",
                "Erlauben" to actions.requestPermissions, "Einstellungen" to actions.openAppSettings)
            issues.bluetoothOff -> Banner(Icons.Default.Settings, "Bluetooth ist aus",
                "Auch im Flugmodus kannst du Bluetooth und WLAN wieder einschalten. Internet ist nicht nötig.",
                "Einschalten" to actions.enableBluetooth)
            radio.error != null -> Banner(Icons.Default.Warning, "Funk noch nicht bereit", radio.error,
                "Neu starten" to actions.retryRadio, "Einstellungen" to actions.openAppSettings)
        }
        if (issues.locationOff && !issues.missingPermissions) Banner(Icons.Default.LocationOn, "Standort ist aus",
            "Viele Handys finden andere Geräte nur mit eingeschaltetem Standort. GPS braucht kein Internet.",
            "Einschalten" to actions.openLocationSettings)
    }
}

@Composable
private fun Banner(icon: ImageVector, title: String, text: String, vararg buttons: Pair<String, () -> Unit>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null)
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, end = 6.dp))
            Row(Modifier.align(Alignment.End)) {
                buttons.forEach { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
            }
        }
    }
}

// ====================================================================== Kontakt hinzufügen

@Composable
private fun AddContactDialog(actions: UiActions, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val myInvite = remember { Invite.create(Mesh.name, Mesh.router.myId) }

    fun apply(text: String) {
        val (n, id) = Invite.parse(text)
        code = id
        if (n.isNotBlank() && name.isBlank()) name = n
        error = null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp,
            modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("Kontakt hinzufügen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Funktioniert auch, wenn das andere Handy gerade nicht in Reichweite ist. Nachrichten werden zugestellt, sobald ihr euch erreicht.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

                Text("1. Dein Code: vom anderen Handy scannen lassen", style = MaterialTheme.typography.titleSmall)
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    QrCode(myInvite, 180.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { actions.copy(myInvite) }) { Text("Kopieren") }
                    TextButton(onClick = { actions.share(myInvite) }) { Text("Teilen") }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("2. Code des anderen scannen oder einfügen", style = MaterialTheme.typography.titleSmall)
                Button(
                    onClick = { actions.scanQr { apply(it) } },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("QR-Code scannen")
                }
                OutlinedTextField(name, { if (it.length <= 30) name = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { apply(it) }, label = { Text("Code / ID einfügen") }, maxLines = 3,
                    isError = error != null, supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("codeField"))
                if (code.isNotBlank() && error == null) {
                    Text("Sicherheitsnummer: ${Crypto.fingerprint(code.trim())}", style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    Button(enabled = code.isNotBlank(), modifier = Modifier.testTag("saveContact"), onClick = {
                        val id = code.trim()
                        when {
                            id == Mesh.router.myId -> error = "Das ist dein eigener Code."
                            Mesh.router.addContact(id, name) -> onAdded(id)
                            else -> error = "Ungültiger Code. Bitte vollständig kopieren oder QR-Code scannen."
                        }
                    }) { Text("Speichern") }
                }
            }
        }
    }
}

@Composable
fun QrCode(text: String, size: Dp) {
    val bmp = remember(text) {
        runCatching {
            val px = 512
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 1))
            val pixels = IntArray(px * px) { i -> if (m.get(i % px, i / px)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(bmp, "QR-Code", filterQuality = FilterQuality.None,
            modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
    }
}

// ====================================================================== Chat

@Composable
private fun ChatScreen(peer: String, showBack: Boolean, onBack: () -> Unit) {
    val devices by Mesh.state.devices.collectAsState()
    val all by Mesh.state.messages.collectAsState()
    val now = rememberNow()
    val isChannel = peer == PUBLIC_CHANNEL
    val d = devices[peer]
    val name = if (isChannel) "Alle in der Nähe" else d?.display ?: "Unbekannt"
    val chat = all.filter { it.peer == peer && !it.sos }
    var text by rememberSaveable(peer) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }   // "info", "rename", "delete"
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
                        if (isChannel) {
                            Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                            }
                        } else Avatar(name, d?.online(now) == true, size = 38.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (isChannel) "Öffentlich · jeder in der Nähe liest mit" else reachText(d, now),
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (!isChannel && d?.online(now) == true) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (!isChannel) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Sicherheitsnummer") }, { menu = false; dialog = "info" },
                                leadingIcon = { Icon(Icons.Default.Lock, null) })
                            DropdownMenuItem({ Text("Umbenennen") }, { menu = false; dialog = "rename" },
                                leadingIcon = { Icon(Icons.Default.Edit, null) })
                            DropdownMenuItem({ Text("Chat leeren") }, { menu = false; Mesh.state.deleteChat(peer) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) })
                            DropdownMenuItem({ Text("Kontakt entfernen") }, { menu = false; dialog = "delete" },
                                leadingIcon = { Icon(Icons.Default.Close, null) })
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.statusBars,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().navigationBarsPadding().imePadding()) {
            if (!isChannel && d?.online(now) != true) {
                Text(
                    "$name ist gerade nicht erreichbar. Nachrichten werden gespeichert und automatisch zugestellt, sobald eine Verbindung besteht, auch über andere Handys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            val reversed = chat.reversed()
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState, reverseLayout = true,
            ) {
                items(reversed.size, key = { reversed[it].mid }) { i ->
                    val m = reversed[i]
                    Bubble(m, showSender = isChannel && !m.mine)
                    // Datumstrenner (Liste ist umgedreht: nächstes Element = älter)
                    val older = reversed.getOrNull(i + 1)
                    if (older == null || dayOf(older.ts) != dayOf(m.ts)) DayChip(m.ts)
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                        Text(
                            if (isChannel) "📢 Öffentlich: nicht verschlüsselt, aber fälschungssicher signiert" else "🔒 Ende-zu-Ende-verschlüsselt",
                            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        text, { if (it.length <= MeshRouter.MAX_TEXT) text = it }, Modifier.weight(1f).testTag("messageField"),
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
    }

    when (dialog) {
        "info" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Sicherheitsnummer") },
            text = {
                Column {
                    Text(Crypto.fingerprint(peer), fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Vergleiche sie mit dem Handy von $name (dort unter „Ich“). Stimmt sie überein, kann garantiert niemand mitlesen.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK") } },
        )
        "rename" -> {
            var alias by remember { mutableStateOf(d?.display ?: "") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Umbenennen") },
                text = { OutlinedTextField(alias, { if (it.length <= 30) alias = it }, singleLine = true, label = { Text("Name") }) },
                confirmButton = { TextButton(onClick = { Mesh.router.rename(peer, alias); dialog = null }) { Text("Speichern") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
            )
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Kontakt entfernen?") },
            text = { Text("Chat und Kontakt werden gelöscht. Taucht das Gerät wieder auf, erscheint es neu in der Liste.") },
            confirmButton = { TextButton(onClick = { dialog = null; Mesh.router.forget(peer); onBack() }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun DayChip(ts: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        Text(dayLabel(ts), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
private fun Bubble(m: ChatMessage, showSender: Boolean) {
    val bg = if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (m.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        val maxBubble = (maxWidth * 0.82f).coerceAtMost(520.dp)
        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Column(
                Modifier.widthIn(max = maxBubble)
                    .clip(RoundedCornerShape(18.dp, 18.dp, if (m.mine) 4.dp else 18.dp, if (m.mine) 18.dp else 4.dp))
                    .background(bg).padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                if (showSender) Text(m.senderName ?: "Unbekannt", color = avatarColor(m.senderName ?: "?"),
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                    Modifier.size(180.dp).scale(pulse.value).clip(CircleShape).background(SosRed)
                        .clickable { confirm = true }.testTag("sosButton"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SOS", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black)
                        Text("Notruf senden", color = Color.White)
                    }
                }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text("Was ist passiert? (optional)") },
                    modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth())
                Text(
                    "Geht mit GPS-Standort an ALLE Handys mit Notfall-Mesh in Reichweite und wird von ihnen weitergeleitet.\n" +
                        "Versuche immer zuerst die 112. Sie funktioniert auch ohne SIM-Karte über jedes Netz.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            Text(shortTime(m.ts), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(m.text, Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp))
                            Text(m.position ?: "Standort unbekannt", fontFamily = FontFamily.Monospace)
                        }
                        m.position?.let { pos ->
                            Row {
                                TextButton(onClick = { actions.copy(pos) }) { Text("Koordinaten kopieren") }
                                TextButton(onClick = {
                                    actions.share("NOTRUF von ${m.senderName ?: "?"}: ${m.text}\nStandort: $pos\ngeo:${pos.replace(" ", "")}")
                                }) { Text("Teilen") }
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
            Button(colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White), onClick = {
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
    val radio by MeshService.radio.collectAsState()
    var name by rememberSaveable { mutableStateOf(Mesh.name) }
    var saved by remember { mutableStateOf(Mesh.name) }
    val now = rememberNow()
    val invite = Invite.create(saved, Mesh.router.myId)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ich", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets.statusBars,
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Card(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(name.ifBlank { "?" }, true, size = 72.dp)
                        OutlinedTextField(name, { if (it.length <= 30) name = it }, label = { Text("Mein Name") }, singleLine = true,
                            modifier = Modifier.padding(top = 12.dp).fillMaxWidth())
                        TextButton(enabled = name.isNotBlank() && name.trim() != saved, onClick = {
                            Mesh.name = name.trim(); saved = name.trim(); Mesh.router.announce()
                        }) { Text("Name speichern") }
                    }
                }
            }
            item {
                Card(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Mein Code", style = MaterialTheme.typography.titleMedium)
                        Text("Andere scannen ihn unter Chats → + Kontakt", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        QrCode(invite, 220.dp)
                        Row {
                            TextButton(onClick = { actions.copy(invite) }) { Text("Kopieren") }
                            TextButton(onClick = { actions.share(invite) }) { Text("Teilen") }
                        }
                        Text("Sicherheitsnummer", style = MaterialTheme.typography.labelLarge)
                        Text(Crypto.fingerprint(Mesh.router.myId), fontFamily = FontFamily.Monospace,
                            fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Card(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Netz-Status", style = MaterialTheme.typography.titleMedium)
                        StatusLine("Andere können mich finden", radio.advertising)
                        StatusLine("Ich suche nach anderen", radio.discovering)
                        Text("Direkt verbunden: $connected")
                        Text("Erreichbar (auch über andere): ${devices.values.count { it.online(now) }}")
                        Text("Bekannte Geräte: ${devices.size}")
                        Text("Zwischengespeicherte Pakete: ${Mesh.router.storedCount}")
                        TextButton(onClick = actions.retryRadio) { Text("Funk neu starten") }
                    }
                }
            }
            item {
                Card(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("So funktioniert's", style = MaterialTheme.typography.titleMedium)
                        Text("• Kein Server, kein Internet: Dein Handy ist selbst der Server und leitet Nachrichten anderer weiter.")
                        Text("• Flugmodus ist ok. Bluetooth (und am besten WLAN) danach wieder einschalten.")
                        Text("• Reichweite pro Handy: ca. 30–150 m. Jedes weitere Handy verlängert die Strecke (bis 15 Sprünge).")
                        Text("• Ist niemand erreichbar, wird die Nachricht bis zu 24 h gespeichert und später zugestellt.")
                        Text("• 🕓 wartet · ✓ weitergegeben · ✓✓ beim Empfänger angekommen")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) OnlineGreen else SosRed))
        Text(label, Modifier.padding(start = 8.dp))
    }
}

// ====================================================================== Bausteine

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** App-Logo: Warndreieck (wie das App-Icon). */
@Composable
fun AppLogo(size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(Color(0xFF263238)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Warning, "Notfall-Mesh", tint = Amber, modifier = Modifier.size(size * 0.7f))
    }
}

@Composable
fun Avatar(name: String, online: Boolean, size: Dp = 46.dp) {
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

fun avatarColor(name: String): Color {
    val palette = listOf(0xFF00796B, 0xFF5E35B1, 0xFF1E88E5, 0xFFD81B60, 0xFFF4511E, 0xFF6D4C41, 0xFF3949AB, 0xFF7CB342)
    return Color(palette[Math.floorMod(name.trim().lowercase().hashCode(), palette.size)])
}

/** Pulsierende Kreise: "Suche läuft". */
@Composable
fun Radar(active: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "radar")
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
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
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun dayOf(ts: Long): Int = Calendar.getInstance().apply { timeInMillis = ts }.let {
    it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(ts: Long): String {
    val today = dayOf(System.currentTimeMillis())
    val yesterday = dayOf(System.currentTimeMillis() - 86_400_000L)
    return when (dayOf(ts)) {
        today -> "Heute"
        yesterday -> "Gestern"
        else -> SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY).format(Date(ts))
    }
}

/** Uhrzeit für heute, sonst Datum. */
fun shortTime(ts: Long): String =
    if (dayOf(ts) == dayOf(System.currentTimeMillis())) time(ts)
    else SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(ts))
