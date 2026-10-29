package de.notfall.mesh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/**
 * Simuliert mehrere Handys in einer Kette:  A <-> B <-> C
 * A und C sind NICHT direkt verbunden – alles muss über B laufen.
 */
@RunWith(AndroidJUnit4::class)
class MeshRouterTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** Kleines Funk-Simulationsnetz mit Warteschlange (wie echte Funkübertragung). */
    private class FakeNet {
        val nodes = HashMap<String, MeshRouter>()
        val links = HashMap<String, MutableSet<String>>()
        val queue = ArrayDeque<Triple<String, String, ByteArray>>()   // von, an, Daten
        var delivered = 0

        fun node(name: String, crypto: Crypto, incoming: MutableList<ChatMessage> = mutableListOf()): MeshRouter {
            val r = MeshRouter(crypto, MeshState(), { name.uppercase() },
                send = { eps, data -> eps.forEach { queue.add(Triple(name, it, data)) } },
                onIncoming = { incoming += it })
            nodes[name] = r
            return r
        }

        fun link(a: String, b: String) {
            links.getOrPut(a) { mutableSetOf() } += b
            links.getOrPut(b) { mutableSetOf() } += a
            nodes[a]!!.onConnected(b)
            nodes[b]!!.onConnected(a)
            run()
        }

        fun run() {
            while (queue.isNotEmpty()) {
                val (from, to, data) = queue.removeFirst()
                if (links[from]?.contains(to) == true) {
                    delivered++
                    nodes[to]!!.onReceive(from, data)
                }
            }
        }
    }

    private fun crypto(name: String) = Crypto(ctx, "test_$name")

    @Test
    fun verschluesselung_nurEmpfaengerKannLesen() {
        val a = crypto("a"); val b = crypto("b"); val c = crypto("c")
        val blob = a.seal(b.myId, JSONObject().put("t", "text").put("text", "geheim"))
        val opened = b.open(blob)
        assertNotNull(opened)
        assertEquals(a.myId, opened!!.first)
        assertEquals("geheim", opened.second.getString("text"))
        assertNull("Dritte dürfen nichts lesen", c.open(blob))
        assertFalse("Klartext darf nicht im Paket stehen", String(blob, Charsets.ISO_8859_1).contains("geheim"))
        blob[blob.size / 2] = (blob[blob.size / 2].toInt() xor 1).toByte()
        assertNull("Veränderte Nachricht muss abgelehnt werden", b.open(blob))
    }

    @Test
    fun signatur_gefaelschtesHelloWirdAbgelehnt() {
        val a = crypto("a")
        val signed = a.signPublic("HELLO", JSONObject().put("name", "Anna"))
        assertNotNull(a.verifyPublic("HELLO", signed))
        val fake = String(signed).replace("Anna", "Mallory").toByteArray()
        assertNull(a.verifyPublic("HELLO", fake))
    }

    @Test
    fun nachrichtWirdUeberZwischenhandyWeitergeleitetUndBestaetigt() {
        val net = FakeNet()
        val inboxC = mutableListOf<ChatMessage>()
        val a = net.node("a", crypto("a"))
        val b = net.node("b", crypto("b"))
        val c = net.node("c", crypto("c"), inboxC)
        net.link("a", "b")
        net.link("b", "c")

        // C sieht A als Gerät (über 1 Zwischenhandy), A sieht C
        a.announce(); c.announce(); net.run()
        assertEquals("A", c.state.devices.value[a.myId]?.name)
        assertEquals(1, c.state.devices.value[a.myId]?.hops)
        assertEquals(0, c.state.devices.value[b.myId]?.hops)
        assertEquals("C", a.state.devices.value[c.myId]?.name)

        // A schreibt C
        val sent = a.sendText(c.myId, "Hallo C, ich bin im Wald!")
        net.run()
        assertEquals(1, inboxC.size)
        assertEquals("Hallo C, ich bin im Wald!", inboxC[0].text)
        assertEquals(a.myId, inboxC[0].peer)

        // B hat weitergeleitet, aber nichts lesen können
        assertTrue(b.state.messages.value.none { it.text.contains("Wald") })

        // A bekommt ✓✓
        assertEquals(Status.DELIVERED, a.state.messages.value.first { it.mid == sent.mid }.status)
    }

    @Test
    fun storeAndForward_nachrichtWirdSpaeterZugestellt() {
        val net = FakeNet()
        val inboxC = mutableListOf<ChatMessage>()
        val a = net.node("a", crypto("a"))
        val b = net.node("b", crypto("b"))
        val c = net.node("c", crypto("c"), inboxC)

        // A und B treffen sich, C ist noch weit weg
        net.link("a", "b")
        val sent = a.sendText(c.myId, "Später ankommen")
        net.run()
        assertEquals(Status.RELAYED, a.state.messages.value.first { it.mid == sent.mid }.status)
        assertTrue(inboxC.isEmpty())

        // Später trifft B auf C -> B gibt die gespeicherte Nachricht weiter
        net.link("b", "c")
        assertEquals(1, inboxC.size)
        assertEquals("Später ankommen", inboxC[0].text)
        assertEquals(Status.DELIVERED, a.state.messages.value.first { it.mid == sent.mid }.status)
    }

    @Test
    fun sos_erreichtAlleUndKeineEndlosschleife() {
        val net = FakeNet()
        val names = listOf("a", "b", "c", "d")
        val inboxes = names.associateWith { mutableListOf<ChatMessage>() }
        names.forEach { net.node(it, crypto(it), inboxes[it]!!) }
        // Ring: a-b-c-d-a (Schleife!)
        net.link("a", "b"); net.link("b", "c"); net.link("c", "d"); net.link("d", "a")
        net.delivered = 0
        net.nodes["a"]!!.sendSos("Hilfe", 47.1, 11.2)
        net.run()
        listOf("b", "c", "d").forEach { n ->
            assertEquals("SOS muss bei $n ankommen", 1, inboxes[n]!!.count { it.sos })
            assertEquals("47.10000, 11.20000", inboxes[n]!!.first { it.sos }.position)
        }
        assertTrue("Keine Endlosschleife (${net.delivered} Übertragungen)", net.delivered < 20)
    }
}
