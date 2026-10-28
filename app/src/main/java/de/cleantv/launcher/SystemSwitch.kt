package de.cleantv.launcher

import android.content.Context
import android.content.pm.PackageManager
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors

/**
 * Umschalten zwischen Clean TV und dem normalen Google-TV-System.
 * Die Box erlaubt einer App nicht, die Startseite selbst zu tauschen. Deshalb verbindet sich
 * Clean TV über das eingeschaltete Netzwerk-Debugging mit der eigenen Box (127.0.0.1:5555)
 * und führt dort dieselben Befehle aus, mit denen Clean TV eingerichtet wurde.
 * Beim allerersten Mal fragt die Box einmal "Debugging zulassen?".
 */
object SystemSwitch {
    /** bekannte Original-Startseiten (Android TV, Google TV, ältere Boxen) – die erste installierte zählt */
    private val LAUNCHERS = listOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
    )
    private const val OWN_HOME = "de.cleantv.launcher/.HomeActivity"
    private const val BAR = "de.cleantv.launcher/de.cleantv.launcher.HomeKeyService"
    private val IO = Executors.newSingleThreadExecutor()

    private fun original(ctx: Context): String? = LAUNCHERS.firstOrNull {
        try { ctx.packageManager.getApplicationEnabledSetting(it); true } catch (_: Exception) { false }
    }

    /** Läuft gerade das normale System (Original-Startseite eingeschaltet)? */
    fun normalActive(ctx: Context): Boolean = try {
        val s = ctx.packageManager.getApplicationEnabledSetting(original(ctx) ?: return false)
        s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (_: Exception) { false }

    fun toNormal(ctx: Context, done: (Boolean) -> Unit) {
        val p = original(ctx) ?: return done(false)
        run(ctx, done,
            "settings put secure enabled_accessibility_services ''",
            "pm enable $p",
            // Startseiten-Bildschirm der Original-App heraussuchen, als Startseite setzen und öffnen
            "C=\$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep '$p/' | head -1 | tr -d ' '); " +
                "cmd package set-home-activity \$C; am start -a android.intent.action.MAIN -c android.intent.category.HOME -n \$C",
        )
    }

    fun toClean(ctx: Context, done: (Boolean) -> Unit) {
        val p = original(ctx)
        run(ctx, done, *listOfNotNull(
            p?.let { "pm disable-user --user 0 $it" },
            "cmd package set-home-activity $OWN_HOME",
            "settings put secure enabled_accessibility_services $BAR",
            "settings put secure accessibility_enabled 1",
        ).toTypedArray())
    }

    private fun run(ctx: Context, done: (Boolean) -> Unit, vararg cmds: String) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        IO.execute {
            val ok = try {
                val dir = ctx.filesDir
                val priv = File(dir, "adbkey")
                val pub = File(dir, "adbkey.pub")
                if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
                Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub)).use { d ->
                    cmds.forEach { d.shell(it) }
                }
                true
            } catch (e: Throwable) {
                android.util.Log.w("CleanTV", "Umschalten fehlgeschlagen: $e")
                false
            }
            main.post { done(ok) }
        }
    }
}
