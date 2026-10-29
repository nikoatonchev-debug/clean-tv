package de.cleantv.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Sicherung für das gemeinsame Ein- und Ausschalten über HDMI (CEC).
 * Manche Fernseher (z. B. Panasonic) brauchen 6–10 s und verpassen dabei Befehle der Box.
 * - Box geht an: nach ein paar Sekunden prüfen, ob der Fernseher wirklich an ist, sonst erneut einschalten.
 * - Box geht aus: prüfen, ob der Fernseher den Aus-Befehl bekommen hat, sonst einmal wiederholen.
 */
object TvPower {
    private val main = Handler(Looper.getMainLooper())
    private var offTries = 0
    private var onToken = Any()
    private var retryAt = -60_000L

    fun isRetrying() = android.os.SystemClock.elapsedRealtime() - retryAt < 8_000

    /** Box ist angegangen */
    fun onScreenOn(ctx: Context) {
        // eigenes kurzes Aufwachen zum Wiederholen des Aus-Befehls: nichts tun
        if (android.os.SystemClock.elapsedRealtime() - retryAt < 8_000) return
        offTries = 0
        val token = Any().also { onToken = it }
        for (delay in longArrayOf(9_000, 18_000, 30_000)) {
            main.postDelayed({
                if (onToken !== token) return@postDelayed
                SystemSwitch.shell(ctx, "dumpsys hdmi_control | grep 'logical_address: 0x00' | head -1") { out ->
                    val status = out?.let { Regex("power_status: (\\d)").find(it)?.groupValues?.get(1) }
                    // 0 = an; alles andere (aus, fährt hoch, unbekannt) → noch einmal einschalten
                    if (onToken === token && status != null && status != "0") {
                        SystemSwitch.shell(ctx, "cmd hdmi_control onetouchplay") {}
                    }
                }
            }, delay)
        }
    }

    /** Box ist ausgegangen */
    fun onScreenOff(ctx: Context) {
        onToken = Any()   // laufende Einschalt-Prüfungen abbrechen
        val pm = ctx.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanTV:tvaus").apply { acquire(20_000) }
        main.postDelayed({
            SystemSwitch.shell(ctx, "dumpsys hdmi_control | grep 'message=<Standby>' | tail -1") { out ->
                val failed = out != null && !out.contains("(ACK)")
                if (failed && offTries < 2 && !pm.isInteractive) {
                    offTries++
                    retryAt = android.os.SystemClock.elapsedRealtime()
                    // kurz aufwachen und wieder einschlafen: dabei schickt die Box den Aus-Befehl erneut
                    SystemSwitch.shell(ctx, "input keyevent 224; sleep 2; input keyevent 223") {}
                }
                if (wl.isHeld) wl.release()
            }
        }, 2_500)
    }
}
