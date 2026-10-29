package de.notfall.mesh

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Zentrale Objekte, die Oberfläche und Hintergrunddienst gemeinsam nutzen. */
object Mesh {
    lateinit var state: MeshState
        private set
    lateinit var router: MeshRouter
        private set
    private lateinit var prefs: SharedPreferences

    /** Wird vom MeshService gesetzt, solange Funk aktiv ist. */
    var transport: ((List<String>, ByteArray) -> Unit)? = null

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(v) = prefs.edit().putString("name", v.trim()).apply()

    fun init(app: Application) {
        prefs = app.getSharedPreferences("profile", Context.MODE_PRIVATE)
        state = MeshState(File(app.filesDir, "mesh.json"))
        router = MeshRouter(
            crypto = Crypto(app),
            state = state,
            myName = { name },
            send = { eps, data -> transport?.invoke(eps, data) },
            onIncoming = { Notifier.message(app, it) },
        )
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Abstürze in eine Datei schreiben, damit sie beim nächsten Start angezeigt werden können
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
                File(filesDir, CRASH_FILE).writeText("Version ${BuildConfigCompat.version(this)}\n$sw")
            } catch (_: Exception) { }
            previous?.uncaughtException(t, e)
        }
        Notifier.createChannels(this)
        Mesh.init(this)
    }

    companion object {
        const val CRASH_FILE = "crash.txt"
    }
}

object BuildConfigCompat {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}

object Notifier {
    const val CH_STATUS = "status"
    const val CH_MSG = "nachrichten"
    const val CH_DEVICE = "geraete"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Mesh-Status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Nachrichten & SOS", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_DEVICE, "Neue Geräte in der Nähe", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun message(ctx: Context, m: ChatMessage) {
        val who = m.senderName ?: Mesh.state.devices.value[m.peer]?.name ?: "Unbekannt"
        val title = if (m.sos) "SOS-NOTRUF von $who!" else who
        val text = if (m.sos) "${m.text}\nStandort: ${m.position ?: "unbekannt"}" else m.text
        post(ctx, CH_MSG, m.mid.hashCode(), title, text, NotificationCompat.PRIORITY_MAX)
    }

    fun device(ctx: Context, d: Device) {
        post(ctx, CH_DEVICE, d.id.hashCode(), "Neues Gerät in der Nähe", "${d.name} ist jetzt erreichbar – tippe zum Chatten",
            NotificationCompat.PRIORITY_DEFAULT)
    }

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String, prio: Int) {
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(prio)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: Exception) { }
    }
}
