package de.cleantv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Der Hersteller zwingt die Oberfläche per /vendor/etc/display_settings.xml bei jedem Start
 * auf 1280×720 (alles wirkt dann verwaschen). Wir heben das nach jedem Start wieder auf,
 * damit in echtem 1920×1080 gezeichnet wird. Braucht WRITE_SECURE_SETTINGS (per adb erteilt).
 */
object DisplayFix {
    fun apply(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null) ?: return
            val c = wm.javaClass
            c.getMethod("clearForcedDisplaySize", Int::class.javaPrimitiveType).invoke(wm, 0)
            c.getMethod("clearForcedDisplayDensityForUser", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, 0, 0)
        } catch (e: Throwable) {
            Log.w("CleanTV", "Bildgröße nicht geändert: $e")
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) = DisplayFix.apply(ctx)
}
