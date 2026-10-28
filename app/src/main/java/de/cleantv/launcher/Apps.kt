package de.cleantv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings

/** Eine startbare App. [tv] = hat eine echte Fernseh-Oberfläche. */
data class App(val pkg: String, val label: String, val cmp: ComponentName?, val tv: Boolean)

object Apps {
    const val SETTINGS = "#einstellungen"
    const val WALLS = "#hintergruende"
    const val NORMAL = "#normal"
    const val SPOTIFY = "com.spotify.tv.android"

    /** Reihenfolge, aus der beim ersten Start die oberen 5 gewählt werden (nur installierte). */
    private val PREFERRED = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja", "com.netflix.mediaclient",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.spotify.tv.android",
        "de.swr.avp.ard.tv",
        "com.zdf.android.mediathek",
        "de.rtl.tvnow",
        "com.android.vending",
    )

    /** Alte Startseiten und die Einstellungen (die bekommen eine eigene Kachel). */
    private val HIDDEN = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.google.android.apps.tv.launcherx",
        "com.android.tv.settings",
    )

    private var cache: List<App>? = null
    private val icons = HashMap<String, Drawable?>()

    fun settingsApp() = App(SETTINGS, "Einstellungen", null, true)
    fun wallsApp() = App(WALLS, "Hintergründe", null, true)
    fun normalApp() = App(NORMAL, "Normales System", null, true)

    fun invalidate() {
        cache = null
        icons.clear()
    }

    fun all(ctx: Context): List<App> {
        cache?.let { return it }
        val pm = ctx.packageManager
        val map = LinkedHashMap<String, App>()
        for ((cat, tv) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val p = r.activityInfo.packageName
                if (p == ctx.packageName || p in HIDDEN || map.containsKey(p)) continue
                map[p] = App(p, r.loadLabel(pm).toString().trim(), ComponentName(p, r.activityInfo.name), tv)
            }
        }
        return map.values.sortedBy { it.label.lowercase() }.also { cache = it }
    }

    fun find(ctx: Context, pkg: String): App? =
        when (pkg) {
            SETTINGS -> settingsApp()
            WALLS -> wallsApp()
            else -> all(ctx).firstOrNull { it.pkg == pkg }
        }

    /** Breites TV-Bild (Banner) der App, sonst null. */
    fun banner(ctx: Context, app: App): Drawable? {
        val key = "b:" + app.pkg
        if (icons.containsKey(key)) return icons[key]
        val pm = ctx.packageManager
        val d = try {
            app.cmp?.let { pm.getActivityBanner(it) } ?: pm.getApplicationBanner(app.pkg)
        } catch (_: Exception) { null }
        icons[key] = d
        return d
    }

    fun icon(ctx: Context, app: App): Drawable? {
        val key = "i:" + app.pkg
        if (icons.containsKey(key)) return icons[key]
        val d = try {
            app.cmp?.let { ctx.packageManager.getActivityIcon(it) }
        } catch (_: Exception) { null }
        icons[key] = d
        return d
    }

    /**
     * Kachel-Inhalt für Apps ohne breites Fernseh-Bild (z. B. Netflix als Handy-App):
     * die Hintergrundfarbe des App-Symbols füllt die ganze Kachel, das Logo sitzt groß in der Mitte.
     */
    fun fillTile(ctx: Context, app: App, tile: android.widget.FrameLayout, h: Int) {
        val d = icon(ctx, app) ?: return
        if (d is android.graphics.drawable.AdaptiveIconDrawable && d.background != null && d.foreground != null) {
            tile.background = d.background.constantState?.newDrawable()?.mutate() ?: d.background
            // Vordergrund eines adaptiven Symbols hat viel Rand, daher größer zeichnen
            val s = (h * 1.45f).toInt()
            tile.addView(android.widget.ImageView(ctx).apply { setImageDrawable(d.foreground) },
                android.widget.FrameLayout.LayoutParams(s, s, android.view.Gravity.CENTER))
            return
        }
        // klassisches Symbol: Farbe am Rand abnehmen und als Fläche nehmen
        val b = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 64, 64)
        d.draw(android.graphics.Canvas(b))
        val edge = b.getPixel(3, 32)
        b.recycle()
        if (android.graphics.Color.alpha(edge) > 200) {
            tile.background = android.graphics.drawable.ColorDrawable(edge)
        }
        val s = (h * 0.72f).toInt()
        tile.addView(android.widget.ImageView(ctx).apply { setImageDrawable(d) },
            android.widget.FrameLayout.LayoutParams(s, s, android.view.Gravity.CENTER))
    }

    fun launch(ctx: Context, app: App) {
        val i = if (app.pkg == SETTINGS) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            Intent(Intent.ACTION_MAIN)
                .addCategory(if (app.tv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
                .setComponent(app.cmp)
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun openInfo(ctx: Context, app: App) {
        if (app.pkg == SETTINGS) return
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:" + app.pkg))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }

    // ---------- Die Apps oben auf der Startseite ----------

    const val MAX_PINS = 5

    fun pins(ctx: Context): MutableList<String> {
        val sp = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        val saved = sp.getString("pins", null)
        val installed = all(ctx).map { it.pkg }.toSet()
        // einmalig: ARD oben durch Spotify ersetzen (Wunsch vom 28.10.)
        if (saved != null && !sp.getBoolean("spotify_swap", false) && SPOTIFY in installed) {
            val list = saved.split(",").filter { it.isNotEmpty() }.toMutableList()
            val i = list.indexOf("de.swr.avp.ard.tv")
            if (SPOTIFY !in list) { if (i >= 0) list[i] = SPOTIFY else if (list.size < MAX_PINS) list += SPOTIFY }
            sp.edit().putBoolean("spotify_swap", true).apply()
            savePins(ctx, list)
            return list.filter { it in installed || it.startsWith("#") }.toMutableList()
        }
        if (saved != null) {
            return saved.split(",").filter { it.isNotEmpty() && (it in installed || it.startsWith("#")) }.toMutableList()
        }
        val list = PREFERRED.filter { it in installed }.toMutableList()
        // zwei Netflix-Varianten nicht doppelt
        if ("com.netflix.ninja" in list) list.remove("com.netflix.mediaclient")
        return list.take(MAX_PINS).toMutableList().also { savePins(ctx, it) }
    }

    fun savePins(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
            .putString("pins", list.joinToString(",")).apply()
    }
}
