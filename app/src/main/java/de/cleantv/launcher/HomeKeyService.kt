package de.cleantv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Home-Taste in einer App: statt sofort zur Startseite gleitet unten eine kleine Leiste
 * über die laufende App hoch. Home noch einmal → volle Startseite. Zurück → Leiste weg.
 * (Bedienhilfe-Dienst, weil nur so die Home-Taste abgefangen werden kann.)
 */
class HomeKeyService : AccessibilityService() {

    private var fgPkg: String? = null
    private var overlay: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var shade: View? = null
    private var row: LinearLayout? = null
    private var label: TextView? = null
    private var hiding = false
    private lateinit var sounds: SoundPool
    private var tickId = 0
    private var lastKey = 0L
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val font by lazy {
        try {
            Typeface.Builder(assets, "inter.ttf").setFontVariationSettings("'wght' 450").build()
        } catch (_: Exception) { Typeface.DEFAULT }
    }

    // die Box hat Animationen auf halbe Länge gestellt, daher die Zeiten doppelt
    private val rise = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val sink = PathInterpolator(0.4f, 0f, 1f, 1f)

    override fun onServiceConnected() {
        sounds = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        tickId = sounds.load(this, R.raw.tick, 1)
        // Fernseher geht an (Box wacht auf): Einschalt-Bildschirm, dann Startseite
        registerReceiver(screenOn, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private val screenOn = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            hide(false)
            startActivity(Intent(this@HomeKeyService, HomeActivity::class.java).putExtra("splash", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenOn) } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        // eigene Leiste und System-Einblendungen nicht als "laufende App" zählen
        if (overlay != null || p == "com.android.systemui") return
        fgPkg = p
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        lastKey = e.eventTime
        when (e.keyCode) {
            KeyEvent.KEYCODE_HOME -> {
                if (overlay != null) {
                    // Home bei offener Leiste: volle Startseite
                    if (e.action == KeyEvent.ACTION_UP) { hide(animate = false); goHome() }
                    return true
                }
                val p = fgPkg
                if (p == null || p == packageName) return false   // auf der Startseite: normal
                if (e.action == KeyEvent.ACTION_UP) show()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (overlay != null) {
                    if (e.action == KeyEvent.ACTION_UP) hide(animate = true)
                    return true
                }
            }
        }
        return false
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------- Leiste ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun show() {
        if (overlay != null) return
        hiding = false
        val root = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val sh = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xA6000000.toInt(), 0xEB000000.toInt()))
            alpha = 0f
        }
        root.addView(sh, FrameLayout.LayoutParams(-1, -1))

        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        p.addView(r, LinearLayout.LayoutParams(-2, -2))
        val l = TextView(this).apply {
            setTextColor(0xE6FFFFFF.toInt())
            typeface = font
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        p.addView(l, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        root.addView(p, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(34)
        })
        row = r; label = l; panel = p; shade = sh

        // Startseite + die oben angehefteten Apps + Alle Apps
        r.addView(tile(homeIcon(), "Startseite", square = true) { hide(false); goHome() }, lp(68))
        for (pkg in Apps.pins(this)) {
            val app = Apps.find(this, pkg) ?: continue
            r.addView(appTile(app), lp(120))
        }
        r.addView(tile(dotsIcon(), "Alle Apps & Einstellungen", square = true) {
            hide(false)
            startActivity(Intent(this, HomeActivity::class.java).putExtra("alleApps", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, lp(68))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, dp(250),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(root, params)
        overlay = root

        // weich hochgleiten, Kacheln leicht versetzt wie eine Welle
        sh.animate().alpha(1f).setDuration(500).start()
        p.translationY = dp(170).toFloat()
        p.alpha = 0f
        p.animate().translationY(0f).alpha(1f).setStartDelay(40).setDuration(760).setInterpolator(rise).start()
        for (i in 0 until r.childCount) {
            val v = r.getChildAt(i)
            v.translationY = dp(44).toFloat()
            v.animate().translationY(0f).setStartDelay(90L + i * 45L).setDuration(700).setInterpolator(rise).start()
        }
        // Auswahl auf der App, aus der man kam (sonst erste App)
        val target = (0 until r.childCount).map { r.getChildAt(it) }
            .firstOrNull { (it.tag as? App)?.pkg == fgPkg } ?: r.getChildAt(minOf(1, r.childCount - 1))
        r.post { target?.requestFocus() }
    }

    private fun hide(animate: Boolean) {
        val o = overlay ?: return
        if (hiding) return
        hiding = true
        val done = Runnable {
            try { wm.removeView(o) } catch (_: Exception) {}
            if (overlay === o) overlay = null
            hiding = false
        }
        if (!animate) { done.run(); return }
        panel?.animate()?.translationY(dp(170).toFloat())?.alpha(0f)?.setStartDelay(0)?.setDuration(420)
            ?.setInterpolator(sink)?.withEndAction(done)?.start()
        shade?.animate()?.alpha(0f)?.setDuration(420)?.start()
    }

    private fun lp(w: Int) = LinearLayout.LayoutParams(dp(w), dp(68)).apply {
        leftMargin = dp(9); rightMargin = dp(9)
    }

    private fun appTile(app: App): View {
        val banner = Apps.banner(this, app)
        val v = if (banner != null) {
            ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(banner) }
        } else null
        return tile(v, app.label, square = false) {
            hide(false)
            Apps.launch(this, app)
        }.also {
            it.tag = app
            if (banner == null) Apps.fillTile(this, app, it as FrameLayout, dp(68))
        }
    }

    private fun tile(content: View?, text: String, square: Boolean,
                     icon: android.graphics.drawable.Drawable? = null, onClick: () -> Unit): View {
        val v = FrameLayout(this)
        v.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x40FFFFFF) }
        if (content != null) {
            val size = if (square) dp(26) else -1
            v.addView(content, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        } else if (icon != null) {
            v.addView(ImageView(this).apply { setImageDrawable(icon) },
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        v.isFocusable = true
        v.isClickable = true
        v.isSoundEffectsEnabled = false
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) =
                o.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
        }
        val ring = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(3), Color.WHITE)
        }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f)
                .translationZ(if (has) dp(10).toFloat() else 0f).setDuration(110).start()
            view.foreground = if (has) ring else null
            if (has) {
                label?.text = text
                if (android.os.SystemClock.uptimeMillis() - lastKey < 400) sounds.play(tickId, 0.35f, 0.35f, 1, 0, 1f)
            }
        }
        v.setOnClickListener { onClick() }
        return v
    }

    private fun dotsIcon() = ImageView(this).apply { setImageDrawable(Icons.Dots()) }
    private fun homeIcon() = ImageView(this).apply { setImageDrawable(Icons.House()) }
}
