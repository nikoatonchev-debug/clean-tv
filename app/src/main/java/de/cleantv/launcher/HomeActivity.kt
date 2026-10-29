package de.cleantv.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class HomeActivity : Activity() {

    private lateinit var scene: SceneView
    private lateinit var bar: LinearLayout
    private lateinit var row: LinearLayout
    private lateinit var focusLabel: TextView
    private lateinit var drawer: FrameLayout
    private lateinit var grid: LinearLayout
    private lateinit var drawerTitle: TextView
    private lateinit var drawerHint: TextView
    private lateinit var textFont: Typeface
    private lateinit var boldFont: Typeface
    private var dirty = true
    private var resumed = false
    private var focusFirstOnStart = true

    // Klick-Ton beim Wechseln
    private lateinit var sounds: SoundPool
    private var tickId = 0
    private var lastKey = 0L

    // Ruhemodus: nach 30 Sekunden ohne Taste nur noch Bild + Uhr
    private val handler = Handler(Looper.getMainLooper())
    private var ambient = false
    private var swallowUp = false
    private val goAmbient = Runnable { enterAmbient() }
    private val allowSleep = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = scene.tick()
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Apps.invalidate()
            dirty = true
            if (resumed) refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DisplayFix.apply(this)
        textFont = font(450)
        boldFont = font(600)
        val clockFont = font(600, 32)

        val root = FrameLayout(this).apply { clipChildren = false }
        scene = SceneView(this, clockFont, textFont)
        scene.show(Walls.current(this))
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))

        sounds = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        tickId = sounds.load(this, R.raw.tick, 1)

        // ---- untere Leiste: 5 Apps + "Alle Apps" ----
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // ---- "Läuft gerade": schlichte Glas-Leiste über den Apps ----
        nowPlaying = buildNowPlaying()
        bar.addView(nowPlaying, LinearLayout.LayoutParams(dp(670), -2).apply { bottomMargin = dp(22) })
        bar.addView(row, LinearLayout.LayoutParams(-2, -2))
        focusLabel = TextView(this).apply {
            setTextColor(0xE6FFFFFF.toInt())
            typeface = textFont
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        bar.addView(focusLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(34)  // Abstand zum Rand, falls der Fernseher etwas abschneidet
        })

        // ---- "Alle Apps" (über allem, erst unsichtbar) ----
        drawer = FrameLayout(this).apply {
            setBackgroundColor(0xC8060818.toInt())
            visibility = View.GONE
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(44), dp(48), 0)
        }
        drawerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = boldFont
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }
        col.addView(drawerTitle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(52); bottomMargin = dp(8) })
        drawerHint = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            typeface = textFont
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        col.addView(drawerHint, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(52); bottomMargin = dp(10) })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12), dp(14), dp(12), dp(40))
            isSmoothScrollingEnabled = true
        }
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(grid, FrameLayout.LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        drawer.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        rootView = root

        // Einschalt-Bildschirm: nach einem Neustart der Box oder wenn der Fernseher angeht
        if (SystemClock.elapsedRealtime() < 5 * 60 * 1000L || intent.getBooleanExtra("splash", false)) showSplash(waitForTv = intent.getBooleanExtra("warten", false))

        Music.attach(this)
        Music.addListener(musicListener)
        updateNowPlaying()

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    private fun font(weight: Int, opsz: Int = 14): Typeface =
        try {
            Typeface.Builder(assets, "inter.ttf")
                .setFontVariationSettings("'wght' $weight, 'opsz' $opsz")
                .build() ?: Typeface.DEFAULT
        } catch (_: Exception) {
            Typeface.DEFAULT
        }

    override fun onResume() {
        super.onResume()
        resumed = true
        scene.tick()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        if (dirty) refresh()
        wake(false)
        backToCleanIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        try { unregisterReceiver(timeReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(goAmbient)
        handler.removeCallbacks(allowSleep)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        Music.removeListener(musicListener)
        sounds.release()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home-Taste: zurück zur ruhigen Startseite, Auswahl auf der ersten App
        closeDrawer()
        wake(false)
        row.getChildAt(0)?.requestFocus()
        if (intent.getBooleanExtra("splash", false)) showSplash(waitForTv = intent.getBooleanExtra("warten", false))
        // zum Ausprobieren ohne Spotify-Anmeldung: --es musiktest "Titel|Interpret|Album|Sekunden|StartSek"
        intent.getStringExtra("musiktest")?.split("|")?.let { p ->
            if (p.size >= 5) Music.fakePlay(p[0], p[1], p[2], p[3].toLong() * 1000, p[4].toLong() * 1000)
            else Music.stopFake()
        }
        // "Alle Apps" aus der Leiste über einer App
        if (intent.getBooleanExtra("alleApps", false)) row.post { openDrawer() }
        // zum Ausprobieren: am start ... --ez ruhe true
        if (intent.getBooleanExtra("ruhe", false)) handler.post { enterAmbient() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // nach dem Einschalten immer auf der ersten App (z. B. YouTube) starten
        if (hasFocus && focusFirstOnStart && drawer.visibility != View.VISIBLE) {
            focusFirstOnStart = false
            row.getChildAt(0)?.requestFocus()
        }
    }

    // ---------------- Läuft gerade ----------------

    private lateinit var nowPlaying: LinearLayout
    private lateinit var npArt: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npProgress: View
    private val musicListener: () -> Unit = { updateNowPlaying() }
    private val progressTick = object : Runnable {
        override fun run() {
            val t = Music.track
            if (t != null && t.durationMs > 0) {
                val f = (Music.position().toFloat() / t.durationMs).coerceIn(0f, 1f)
                npProgress.scaleX = f
            }
            scene.invalidate()
            if (Music.active) handler.postDelayed(this, 500)
        }
    }

    private fun buildNowPlaying(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x38FFFFFF) }
            visibility = View.GONE
        }
        npArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
            }
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
        }
        box.addView(npArt, LinearLayout.LayoutParams(dp(46), dp(46)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        npTitle = TextView(this).apply {
            setTextColor(Color.WHITE); typeface = boldFont; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        npArtist = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt()); typeface = textFont; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(npTitle)
        col.addView(npArtist)
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x33FFFFFF) }
        }
        npProgress = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Color.WHITE) }
            pivotX = 0f
            scaleX = 0f
        }
        track.addView(npProgress, FrameLayout.LayoutParams(-1, -1))
        col.addView(track, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(7) })
        box.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12); rightMargin = dp(14) })

        // Knöpfe: zurück, Pause/Weiter, vor, Zufall, DJ
        npPlay = mediaButton(Icons.Media("pause")) { Music.playPause() }
        npShuffle = mediaButton(Icons.Media("shuffle")) { Music.toggleShuffle() }
        val dj = mediaButton(null, "DJ") { Music.startDj(this) }
        val close = mediaButton(Icons.Media("close")) {
            Music.hide()
            row.getChildAt(0)?.requestFocus()
        }
        listOf(mediaButton(Icons.Media("prev")) { Music.prev() }, npPlay,
            mediaButton(Icons.Media("next")) { Music.next() }, npShuffle, dj, close).forEach {
            box.addView(it, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) })
        }
        return box
    }

    private lateinit var npPlay: FrameLayout
    private lateinit var npShuffle: FrameLayout

    private fun mediaButton(icon: Drawable?, text: String? = null, onClick: () -> Unit): FrameLayout {
        val v = FrameLayout(this)
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
        v.background = bg
        if (icon != null) {
            v.addView(ImageView(this).apply { setImageDrawable(icon); tag = "icon" },
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        } else {
            v.addView(TextView(this).apply {
                this.text = text; setTextColor(Color.WHITE); typeface = boldFont
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        v.isFocusable = true
        v.isClickable = true
        v.isSoundEffectsEnabled = false
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f).setDuration(110).start()
            bg.setColor(if (has) 0x4DFFFFFF else Color.TRANSPARENT)
            if (has && SystemClock.uptimeMillis() - lastKey < 400) sounds.play(tickId, 0.35f, 0.35f, 1, 0, 1f)
        }
        v.setOnClickListener { onClick(); handler.postDelayed({ updateNowPlaying() }, 300) }
        return v
    }

    private fun updateNowPlaying() {
        val t = Music.track
        val show = t != null && Music.active
        if (show) {
            npTitle.text = t!!.title
            npArtist.text = t.artist
            if (t.art != null) npArt.setImageBitmap(t.art) else npArt.setImageDrawable(null)
            (npPlay.findViewWithTag<ImageView>("icon"))?.setImageDrawable(Icons.Media(if (Music.playing) "pause" else "play"))
            (npShuffle.findViewWithTag<ImageView>("icon"))?.alpha = if (Music.shuffle) 1f else 0.45f
            if (nowPlaying.visibility != View.VISIBLE) {
                nowPlaying.alpha = 0f
                nowPlaying.translationY = dp(14).toFloat()
                nowPlaying.visibility = View.VISIBLE
                nowPlaying.animate().alpha(1f).translationY(0f).setDuration(500).start()
            }
            handler.removeCallbacks(progressTick)
            handler.post(progressTick)
        } else {
            nowPlaying.visibility = View.GONE
        }
        scene.invalidate()
    }

    // ---------------- Normales System / Clean TV ----------------

    private var switching = false

    private fun askNormal() {
        choice("Zum normalen System wechseln?", listOf(
            "Ja, normales System" to {
                switching = true
                Toast.makeText(this, "Wechsle zum normalen System …", Toast.LENGTH_SHORT).show()
                SystemSwitch.toNormal(this) { ok ->
                    switching = false
                    if (!ok) failToast()
                }
            },
            "Abbrechen" to {},
        ))
    }

    /** Clean TV wurde im normalen System geöffnet: alles zurück auf Clean TV. */
    private fun backToCleanIfNeeded() {
        if (switching || !SystemSwitch.normalActive(this)) return
        switching = true
        showSplash()
        SystemSwitch.toClean(this) { ok ->
            switching = false
            if (!ok) failToast()
        }
    }

    private fun failToast() = Toast.makeText(this,
        "Umschalten hat nicht geklappt. Bitte auf dem Fernseher \"Debugging zulassen\" mit \"Immer zulassen\" bestätigen und nochmal versuchen.",
        Toast.LENGTH_LONG).show()

    // ---------------- Einschalt-Bildschirm ----------------

    private lateinit var rootView: FrameLayout
    private var splash: SplashView? = null

    /**
     * @param waitForTv true beim Einschalten: Der Fernseher braucht ein paar Sekunden, bis er ein Bild zeigt.
     * Die Animation startet erst, wenn er sich am HDMI-Anschluss neu angemeldet hat (plus kurze Bild-Synchronisation).
     */
    private fun showSplash(waitForTv: Boolean = false) {
        if (splash != null) return
        val s = SplashView(this, font(650, 32)) {
            val v = splash ?: return@SplashView
            v.animate().alpha(0f).setDuration(500).withEndAction {
                rootView.removeView(v)
                if (splash === v) splash = null
            }.start()
        }
        splash = s
        rootView.addView(s, FrameLayout.LayoutParams(-1, -1))
        if (!waitForTv) { s.start(); return }

        val since = System.currentTimeMillis() / 1000 * 1000 - 1000
        var started = false
        val go = Runnable { if (!started && splash === s) { started = true; s.start() } }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val deadline = android.os.SystemClock.uptimeMillis() + 12_000
        fun poll() {
            if (started || splash !== s) return
            if (android.os.SystemClock.uptimeMillis() > deadline) { go.run(); return }
            SystemSwitch.shell(this, "dumpsys hdmi_control | grep 'hotplug port=1 connected=true' | tail -1") { out ->
                val t = out?.let { Regex("""time=(\S+ \S+)""").find(it)?.groupValues?.get(1) }
                    ?.let { runCatching { fmt.parse(it)?.time }.getOrNull() }
                if (t != null && t >= since) handler.postDelayed(go, 1_300)   // Bild braucht noch einen Moment
                else handler.postDelayed({ poll() }, 500)
            }
        }
        poll()
    }

    // ---------------- Ruhemodus ----------------

    // laufende Abdunkel-Animation, damit Aufwachen sie sicher abbricht
    private var dimAnim: ValueAnimator? = null

    private fun enterAmbient() {
        if (!resumed || ambient) return
        ambient = true
        closeDrawer()
        // (Die Box hat Animationen auf halbe Länge gestellt, daher die Zeiten doppelt.)
        // 1. Apps sinken nacheinander nach unten weg und verblassen
        val smooth = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        focusLabel.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(500).start()
        nowPlaying.animate().alpha(0f).setDuration(700).start()
        for (i in 0 until row.childCount) {
            row.getChildAt(i).animate().alpha(0f).translationY(dp(46).toFloat())
                .setStartDelay(i * 90L).setDuration(900).setInterpolator(smooth).start()
        }
        // 2. Bild wird langsam dunkler und zoomt ganz sanft heran
        dimAnim?.cancel()
        dimAnim = ValueAnimator.ofFloat(scene.dim, 1f).apply {
            duration = 3600
            startDelay = 300
            interpolator = smooth
            addUpdateListener { scene.dim = it.animatedValue as Float }
            start()
        }
        scene.animate().scaleX(1.045f).scaleY(1.045f).setStartDelay(0).setDuration(6000)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        // nach 4 Stunden darf der Fernseher wieder normal schlafen gehen
        handler.postDelayed(allowSleep, 4 * 60 * 60 * 1000L)
    }

    /** Taste gedrückt: Ruhemodus beenden und die 30 Sekunden neu starten. */
    private fun wake(animate: Boolean = true) {
        handler.removeCallbacks(goAmbient)
        handler.removeCallbacks(allowSleep)
        handler.postDelayed(goAmbient, 30 * 1000L)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!ambient) return
        ambient = false
        val d = if (animate) 1L else 0L
        val pop = DecelerateInterpolator(2f)
        focusLabel.animate().alpha(1f).translationY(0f).setStartDelay(200 * d).setDuration(400 * d).start()
        nowPlaying.animate().alpha(1f).setStartDelay(0).setDuration(400 * d).start()
        for (i in 0 until row.childCount) {
            row.getChildAt(i).animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 50L * d).setDuration(560 * d).setInterpolator(pop).start()
        }
        scene.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(600 * d).setInterpolator(pop).start()
        dimAnim?.cancel()
        dimAnim = ValueAnimator.ofFloat(scene.dim, 0f).apply {
            duration = 560 * d
            interpolator = pop
            addUpdateListener { scene.dim = it.animatedValue as Float }
            start()
        }
    }

    // ---------------- Startseite ----------------

    private fun refresh() {
        dirty = false
        val focusedPkg = (currentFocus?.tag as? App)?.pkg
        row.removeAllViews()
        val pins = Apps.pins(this)
        for (pkg in pins) {
            val app = Apps.find(this, pkg) ?: continue
            row.addView(tile(app, dp(120), dp(68), home = true), gapLp(dp(120), dp(68)))
        }
        row.addView(allAppsTile(), gapLp(dp(68), dp(68)))
        if (drawer.visibility == View.VISIBLE) buildGrid()
        val target = (0 until row.childCount).map { row.getChildAt(it) }
            .firstOrNull { (it.tag as? App)?.pkg == focusedPkg } ?: row.getChildAt(0)
        if (drawer.visibility != View.VISIBLE) row.post { target?.requestFocus() }
    }

    private fun gapLp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).apply {
        leftMargin = dp(9); rightMargin = dp(9)
    }

    private fun allAppsTile(): View {
        val v = FrameLayout(this)
        v.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(0x2EFFFFFF)
            setStroke(dp(1), 0x33FFFFFF)
        }
        v.addView(ImageView(this).apply { setImageDrawable(DotsDrawable()) },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        makeFocusable(v, "Alle Apps & Einstellungen")
        v.setOnClickListener { openDrawer() }
        return v
    }

    /** Eine App-Kachel: breites TV-Bild, oder das App-Symbol auf mattem Glas. */
    private fun tile(app: App, w: Int, h: Int, home: Boolean): View {
        val v = FrameLayout(this)
        v.tag = app
        v.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(0x2EFFFFFF)
        }
        if (app.pkg == Apps.SETTINGS) {
            v.addView(ImageView(this).apply { setImageDrawable(GearDrawable()) },
                FrameLayout.LayoutParams(h / 2, h / 2, Gravity.CENTER))
        } else if (app.pkg == Apps.WALLS) {
            v.addView(thumbView(Walls.current(this)), FrameLayout.LayoutParams(-1, -1))
        } else if (app.pkg == Apps.NORMAL) {
            v.addView(ImageView(this).apply { setImageDrawable(Icons.Swap()) },
                FrameLayout.LayoutParams(h / 2, h / 2, Gravity.CENTER))
        } else {
            val banner = Apps.banner(this, app)
            if (banner != null) {
                v.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(banner)
                }, FrameLayout.LayoutParams(-1, -1))
            } else {
                Apps.fillTile(this, app, v, h)
            }
        }
        makeFocusable(v, app.label)
        v.setOnClickListener {
            when (app.pkg) {
                Apps.WALLS -> openDrawer(walls = true)
                Apps.NORMAL -> askNormal()
                else -> Apps.launch(this, app)
            }
        }
        v.setOnLongClickListener { showMenu(app, home); true }
        return v
    }

    private fun thumbView(w: Wall) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        try {
            setImageBitmap(assets.open(w.thumb).use { android.graphics.BitmapFactory.decodeStream(it) })
        } catch (_: Exception) {}
    }

    private fun makeFocusable(v: View, label: String) {
        v.isFocusable = true
        v.isClickable = true
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) =
                o.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
        }
        val ring = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), Color.WHITE)
        }
        v.isSoundEffectsEnabled = false
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f)
                .translationZ(if (has) dp(10).toFloat() else 0f).setDuration(110).start()
            view.foreground = if (has) ring else null
            if (has && view.parent === row) focusLabel.text = label
            // Ton nur, wenn mit der Fernbedienung gewechselt wurde
            if (has && SystemClock.uptimeMillis() - lastKey < 400) sounds.play(tickId, 0.35f, 0.35f, 1, 0, 1f)
        }
    }

    // ---------------- Alle Apps ----------------

    private var showingWalls = false

    private fun openDrawer(walls: Boolean = false) {
        showingWalls = walls
        if (walls) {
            drawerTitle.text = "Hintergründe"
            drawerHint.text = "Bild auswählen und OK drücken"
        } else {
            drawerTitle.text = "Alle Apps"
            drawerHint.text = "OK lange drücken: App oben anzeigen oder entfernen"
        }
        buildGrid()
        if (drawer.visibility == View.VISIBLE) {
            grid.post { focusInGrid() }
            return
        }
        drawer.alpha = 0f
        drawer.visibility = View.VISIBLE
        bar.visibility = View.INVISIBLE
        drawer.animate().alpha(1f).setDuration(160).start()
        if (Build.VERSION.SDK_INT >= 31) {
            scene.setRenderEffect(RenderEffect.createBlurEffect(dp(18).toFloat(), dp(18).toFloat(), Shader.TileMode.CLAMP))
        }
        grid.post { focusInGrid() }
    }

    /** Bei den Hintergründen auf dem aktiven Bild starten, sonst oben links. */
    private fun focusInGrid() {
        val cur = Walls.current(this)
        for (r in 0 until grid.childCount) {
            val rowView = grid.getChildAt(r) as ViewGroup
            for (k in 0 until rowView.childCount) {
                val t = (rowView.getChildAt(k) as ViewGroup).getChildAt(0)
                if (!showingWalls || t.tag == cur) { t.requestFocus(); return }
            }
        }
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        drawer.visibility = View.GONE
        bar.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= 31) scene.setRenderEffect(null)
        row.getChildAt(row.childCount - 1)?.requestFocus()
    }

    private fun buildGrid() {
        grid.removeAllViews()
        if (showingWalls) {
            buildWallGrid()
            return
        }
        // "Normales System" ganz unten als letzte Kachel
        val items = listOf(Apps.settingsApp(), Apps.wallsApp()) + Apps.all(this) + Apps.normalApp()
        val cols = 5
        val w = dp(132)
        val h = dp(74)
        for (start in items.indices step cols) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            for (app in items.subList(start, minOf(start + cols, items.size))) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                cell.addView(tile(app, w, h, home = false), LinearLayout.LayoutParams(w, h))
                cell.addView(TextView(this).apply {
                    text = app.label
                    setTextColor(0xD9FFFFFF.toInt())
                    typeface = textFont
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(10) })
                r.addView(cell, LinearLayout.LayoutParams(-2, -2).apply {
                    leftMargin = dp(13); rightMargin = dp(13)
                })
            }
            grid.addView(r, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(22) })
        }
    }

    private fun buildWallGrid() {
        val items = Walls.all(this)
        val cur = Walls.current(this)
        val cols = 4
        val w = dp(170)
        val h = dp(96)
        for (start in items.indices step cols) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            for (wall in items.subList(start, minOf(start + cols, items.size))) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                    clipToPadding = false
                }
                val t = FrameLayout(this)
                t.tag = wall
                t.addView(thumbView(wall), FrameLayout.LayoutParams(-1, -1))
                if (wall == cur) {
                    t.addView(TextView(this).apply {
                        text = "✓"
                        setTextColor(0xFF101225.toInt())
                        typeface = boldFont
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                    }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(7); rightMargin = dp(7)
                    })
                }
                makeFocusable(t, "")
                t.setOnClickListener {
                    Walls.select(this, wall)
                    scene.show(wall)
                    closeDrawer()
                    refresh()
                    row.getChildAt(0)?.requestFocus()
                }
                cell.addView(t, LinearLayout.LayoutParams(w, h))
                cell.addView(TextView(this).apply {
                    text = wall.credit.substringBefore(" · ")
                    setTextColor(0x8CFFFFFF.toInt())
                    typeface = textFont
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(6) })
                r.addView(cell, LinearLayout.LayoutParams(-2, -2).apply {
                    leftMargin = dp(13); rightMargin = dp(13)
                })
            }
            grid.addView(r, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16) })
        }
    }

    // ---------------- Menü (OK lange drücken) ----------------

    private fun showMenu(app: App, home: Boolean) {
        val pins = Apps.pins(this)
        val pinned = app.pkg in pins
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (home) {
            val i = pins.indexOf(app.pkg)
            if (i > 0) actions += "Nach links" to { pins.add(i - 1, pins.removeAt(i)); Apps.savePins(this, pins); dirty = true; refresh() }
            if (i in 0 until pins.size - 1) actions += "Nach rechts" to { pins.add(i + 1, pins.removeAt(i)); Apps.savePins(this, pins); dirty = true; refresh() }
            actions += "Von oben entfernen" to { pins.remove(app.pkg); Apps.savePins(this, pins); refresh() }
        } else if (pinned) {
            actions += "Von oben entfernen" to { pins.remove(app.pkg); Apps.savePins(this, pins); refresh() }
        } else {
            actions += "Oben anzeigen" to {
                if (pins.size >= Apps.MAX_PINS) {
                    Toast.makeText(this, "Oben sind schon ${Apps.MAX_PINS} Apps. Bitte zuerst eine entfernen.", Toast.LENGTH_LONG).show()
                } else {
                    pins.add(app.pkg); Apps.savePins(this, pins); refresh()
                    Toast.makeText(this, "${app.label} ist jetzt oben.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (!app.pkg.startsWith("#")) actions += "App-Infos" to { Apps.openInfo(this, app) }
        actions += "Abbrechen" to {}
        choice(app.label, actions)
    }

    /** Kleines Auswahl-Fenster im Clean-TV-Stil. */
    private fun choice(title: String, actions: List<Pair<String, () -> Unit>>) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xF0181A2E.toInt())
            }
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            typeface = boldFont
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), 0, dp(10), dp(10))
        })
        for ((label, act) in actions) {
            box.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                typeface = textFont
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isFocusable = true
                isClickable = true
                val bg = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT) }
                background = bg
                setOnFocusChangeListener { v, has ->
                    bg.setColor(if (has) Color.WHITE else Color.TRANSPARENT)
                    (v as TextView).setTextColor(if (has) 0xFF101225.toInt() else Color.WHITE)
                }
                setOnClickListener { d.dismiss(); act() }
            }, LinearLayout.LayoutParams(dp(260), -2))
        }
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
    }

    // ---------------- Tasten ----------------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastKey = SystemClock.uptimeMillis()
        // erste Taste im Ruhemodus weckt nur auf
        if (ambient && e.action == KeyEvent.ACTION_DOWN) {
            wake()
            swallowUp = true
            return true
        }
        if (swallowUp) {
            if (e.action == KeyEvent.ACTION_UP) swallowUp = false
            return true
        }
        // Pfeil nach oben, wo es nicht weiter hoch geht: sofort Ruhemodus
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0
            && drawer.visibility != View.VISIBLE && splash == null) {
            val f = currentFocus
            // ganz oben = im Musik-Kasten, oder in der App-Reihe, wenn kein Musik-Kasten da ist
            val inBox = f != null && f.parent === nowPlaying
            val inRow = f != null && f.parent === row && nowPlaying.visibility != View.VISIBLE
            if (inBox || inRow) {
                enterAmbient()
                if (ambient) { swallowUp = true; return true }
            }
        }
        wake()
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (e.action == KeyEvent.ACTION_UP) {
                    if (drawer.visibility == View.VISIBLE) closeDrawer()
                }
                return true // Startseite nie verlassen
            }
            KeyEvent.KEYCODE_MENU -> {
                if (e.action == KeyEvent.ACTION_UP) {
                    val app = currentFocus?.tag as? App
                    if (app != null) showMenu(app, currentFocus?.parent === row)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- kleine Symbole ----------------

    /** 3×3 Punkte für "Alle Apps". */
    private class DotsDrawable : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun draw(c: Canvas) {
            val b = bounds
            val step = b.width() / 3f
            val r = step * 0.17f
            for (i in 0..2) for (j in 0..2) {
                c.drawCircle(b.left + step * (i + 0.5f), b.top + step * (j + 0.5f), r, p)
            }
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Zahnrad für "Einstellungen". */
    private class GearDrawable : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun draw(c: Canvas) {
            val b = bounds
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val R = b.width() / 2f
            p.strokeWidth = R * 0.16f
            c.drawCircle(cx, cy, R * 0.52f, p)
            c.drawCircle(cx, cy, R * 0.18f, f)
            for (k in 0 until 8) {
                c.save()
                c.rotate(k * 45f, cx, cy)
                c.drawRoundRect(cx - R * 0.13f, cy - R * 0.98f, cx + R * 0.13f, cy - R * 0.6f, R * 0.05f, R * 0.05f, f)
                c.restore()
            }
        }
        override fun setAlpha(a: Int) { p.alpha = a; f.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf; f.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
