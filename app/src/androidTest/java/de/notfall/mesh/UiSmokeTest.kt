package de.notfall.mesh

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Startet die echte App, gibt einen Namen ein, fügt einen Kontakt hinzu, schreibt Nachrichten,
 * öffnet alle Reiter und prüft, dass nichts abstürzt. Macht dabei Screenshots.
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    private val perms = GrantPermissionRule.grant(*(MeshService.requiredPermissions() +
        if (android.os.Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        ).toTypedArray())

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(perms).around(compose)

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val ctx = compose.activity
        val cfg = ctx.resources.configuration
        val mode = if ((cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES) "dunkel" else "hell"
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "${cfg.screenWidthDp}dp_${mode}_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    @Test
    fun kompletterDurchlauf() {
        if (Mesh.name.isBlank()) {
            shot("01_start")
            compose.onNodeWithTag("nameField").performTextInput("Testerin")
            // Der eingegebene Name muss sichtbar sein
            compose.onNodeWithTag("nameField").assertTextContains("Testerin")
            compose.onAllNodesWithText("Testerin").assertCountEquals(2)   // Feld + Vorschau
            shot("02_name_eingegeben")
            compose.onNodeWithTag("startButton").performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("peerList").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(3000)   // Funk starten lassen
        shot("03_liste_leer")

        // Kontakt von Hand hinzufügen (Code einfügen)
        val other = Crypto(compose.activity, "test_ui_peer")
        compose.onNodeWithTag("addContact").performClick()
        shot("04_kontakt_hinzufuegen")
        compose.onNodeWithTag("codeField").performScrollTo().performTextInput(Invite.create("Bergwacht", other.myId))
        compose.onNodeWithTag("saveContact").performScrollTo()
        shot("04b_kontakt_code_eingefuegt")
        compose.onNodeWithTag("saveContact").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("messageField").fetchSemanticsNodes().isNotEmpty() }

        // Chat ist offen -> Nachricht senden
        compose.onNodeWithTag("messageField").performTextInput("Hallo, hört mich jemand?")
        compose.onNodeWithTag("sendButton").performClick()
        compose.onNodeWithText("Hallo, hört mich jemand?").assertExists()
        // Antwort simulieren
        compose.runOnUiThread {
            Mesh.state.addMessage(ChatMessage("x1", other.myId, "Ja! Wir sind 200 m nördlich von dir.", System.currentTimeMillis(), mine = false))
            Mesh.state.putDevice(Mesh.state.devices.value[other.myId]!!.copy(lastSeen = System.currentTimeMillis(), hops = 1))
        }
        shot("05_chat")

        // Zurück, Kanal "Alle in der Nähe"
        if (compose.onAllNodesWithContentDescription("Zurück").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Zurück").performClick()
        }
        compose.onNodeWithTag("channel").performClick()
        compose.onNodeWithTag("messageField").performTextInput("Hallo an alle!")
        compose.onNodeWithTag("sendButton").performClick()
        compose.runOnUiThread {
            Mesh.state.addMessage(ChatMessage("x2", PUBLIC_CHANNEL, "Hier Gruppe Süd, alles ok.", System.currentTimeMillis(),
                mine = false, senderName = "Jonas"))
        }
        shot("06_kanal")
        if (compose.onAllNodesWithContentDescription("Zurück").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Zurück").performClick()
        }
        shot("07_liste")

        // SOS und Ich
        compose.onNodeWithText("SOS", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Notruf senden").assertExists()
        shot("08_sos")
        compose.onNodeWithText("Ich", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Mein Code").assertExists()
        shot("09_ich")

        // Hintergrunddienst + Funk dürfen nicht abstürzen
        Thread.sleep(4000)
        compose.waitForIdle()
        assertTrue(!File(compose.activity.filesDir, App.CRASH_FILE).exists())
    }
}
