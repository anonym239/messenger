package de.notfall.mesh

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Startet die echte App, gibt einen Namen ein, schreibt eine Nachricht und prüft, dass nichts abstürzt. */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    private val perms = GrantPermissionRule.grant(*buildList {
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
    }.toTypedArray())

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(perms).around(compose)

    @Test
    fun appStartet_nameEingeben_nachrichtSenden() {
        if (Mesh.name.isBlank()) {
            compose.onNodeWithTag("nameField").performTextInput("Testerin")
            compose.onNodeWithTag("startButton").performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("peerList").fetchSemanticsNodes().isNotEmpty() }

        // Ein Gerät "taucht auf" (wie per HELLO) -> erscheint in der Liste -> Chat öffnen -> senden
        val other = Crypto(compose.activity, "test_ui_peer")
        compose.runOnUiThread { Mesh.state.putDevice(Device(other.myId, "Bergwacht", System.currentTimeMillis(), 1)) }
        compose.onNodeWithTag("peer_Bergwacht").performClick()
        compose.onNodeWithTag("messageField").performTextInput("Hallo, hört mich jemand?")
        compose.onNodeWithTag("sendButton").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hallo, hört mich jemand?").assertExists()

        // Andere Tabs öffnen
        compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("SOS").performClick()
        compose.onNodeWithText("Notruf senden").assertExists()
        compose.onNodeWithText("Ich").performClick()
        compose.onNodeWithText("Deine Sicherheitsnummer").assertExists()

        // Ein paar Sekunden laufen lassen: Hintergrunddienst + Funk dürfen nicht abstürzen
        Thread.sleep(5000)
        compose.waitForIdle()
    }
}
