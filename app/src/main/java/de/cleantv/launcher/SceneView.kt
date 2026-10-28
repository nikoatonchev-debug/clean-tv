package de.cleantv.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Hintergrund wie beim iPhone: Foto, dann die Uhr, dann der freigestellte Berg davor.
 * Alle Maße beziehen sich auf das 1920×1080-Bild und werden auf den Bildschirm umgerechnet.
 */
class SceneView(ctx: Context, clockFont: Typeface, textFont: Typeface) : View(ctx) {

    private var wall: Wall? = null
    private var back: Bitmap? = null
    private var front: Bitmap? = null
    private var fade = 1f
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = clockFont
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = textFont
    }
    private val shadePaint = Paint()
    private val bounds = Rect()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
    private val dateFmt = SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY)
    private var time = ""
    private var date = ""

    private val lyrics = LyricsPainter(ctx)

    /** 0 = normal, 1 = Ruhemodus (nur Bild + Uhr, etwas dunkler). */
    var dim = 0f
        set(v) { field = v; invalidate() }

    init {
        setBackgroundColor(0xFF0B0E22.toInt())
        tick()
    }

    fun tick() {
        val now = Date()
        time = timeFmt.format(now)
        date = dateFmt.format(now)
        invalidate()
    }

    fun show(w: Wall) {
        if (w == wall && back != null) return
        wall = w
        load()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        clockPaint.textSize = h * 300f / 1080f
        datePaint.textSize = h * 34f / 1080f
        shadePaint.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(),
            0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
        load()
    }

    private fun load() {
        val w = wall ?: return
        val vw = width
        val vh = height
        if (vw == 0 || vh == 0) return
        IO.execute {
            val b = decode(w.back, vw, vh)
            // ohne Vordergrund (Wall.front leer): Schrift liegt einfach über dem Bild
            if (w.front.endsWith("/")) {
                post {
                    if (wall != w) return@post
                    back = b
                    front = null
                    applyColors(w)
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 260
                        addUpdateListener { fade = it.animatedValue as Float; invalidate() }
                        start()
                    }
                }
                return@execute
            }
            val f = decode(w.front, vw, vh)
            if (w.mask) {
                // front ist nur die Maske des Bergs: Foto damit ausschneiden
                val cut = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
                val cc = Canvas(cut)
                cc.drawBitmap(f, 0f, 0f, null)
                cc.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                })
                f.recycle()
                post { if (wall == w) front = cut }
            }
            post {
                if (wall != w) return@post
                back = b
                if (!w.mask) front = f
                applyColors(w)
                // weich einblenden
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 260
                    addUpdateListener { fade = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
    }

    private fun applyColors(w: Wall) {
        val r = clockPaint.textSize
        if (w.dark) {
            clockPaint.color = 0xE61A1D2E.toInt()
            datePaint.color = 0xD91A1D2E.toInt()
            clockPaint.clearShadowLayer()
            datePaint.clearShadowLayer()
        } else {
            clockPaint.color = 0xF0FFFFFF.toInt()
            datePaint.color = 0xE6FFFFFF.toInt()
            clockPaint.setShadowLayer(r * 0.05f, 0f, r * 0.01f, 0x33000000)
            datePaint.setShadowLayer(datePaint.textSize * 0.3f, 0f, 0f, 0x55000000)
        }
    }

    /** 4K-Bild laden: der Decoder verkleinert gleich passend zum Bildschirm (spart Zeit und Speicher). */
    private fun decode(path: String, w: Int, h: Int): Bitmap {
        val assets = context.assets
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= w && opts.outHeight / (sample * 2) >= h) sample *= 2
        val src = assets.open(path).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }!!
        if (src.width == w && src.height == h) return src
        return Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val cx = width / 2f
        val w = wall ?: return
        val alpha = (fade * 255).toInt()
        bmpPaint.alpha = alpha
        // Ruhemodus: das Bild wird deutlich dunkler, die Uhr nur etwas gedämpft
        val g = (255 * (1 - 0.72f * dim)).toInt()
        bmpPaint.colorFilter = if (dim > 0f) LightingColorFilter(Color.rgb(g, g, g), 0) else null
        back?.let { c.drawBitmap(it, 0f, 0f, bmpPaint) }

        val top = h * w.clockTop
        clockPaint.getTextBounds("0123456789", 0, 10, bounds)
        val clockBase = top - bounds.top
        datePaint.getTextBounds("Dg", 0, 2, bounds)
        val dateBase = top - h * 0.045f

        // Musik läuft: Liedtext statt großer Uhr (ebenfalls hinter dem Berg)
        if (lyrics.draw(c, width.toFloat(), h, top, dim, w.dark)) {
            datePaint.alpha = (230 * (1 - 0.3f * dim)).toInt()
            c.drawText("$time  ·  $date", cx, dateBase, datePaint)
            front?.let { c.drawBitmap(it, 0f, 0f, bmpPaint) }
            shadePaint.alpha = ((1 - dim) * 255).toInt()
            c.drawRect(0f, h * 0.62f, width.toFloat(), h, shadePaint)
            postInvalidateOnAnimation()
            return
        }

        val clockA = clockPaint.alpha
        val dateA = datePaint.alpha
        clockPaint.alpha = (clockA * (1 - 0.3f * dim)).toInt()
        datePaint.alpha = (dateA * (1 - 0.3f * dim)).toInt()
        c.drawText(date, cx, dateBase, datePaint)
        c.drawText(time, cx, clockBase, clockPaint)
        clockPaint.alpha = clockA
        datePaint.alpha = dateA

        front?.let { c.drawBitmap(it, 0f, 0f, bmpPaint) }
        // unten etwas dunkler, damit die Apps gut lesbar sind (im Ruhemodus nicht nötig)
        shadePaint.alpha = ((1 - dim) * 255).toInt()
        c.drawRect(0f, h * 0.62f, width.toFloat(), h, shadePaint)
    }

    companion object {
        private val IO = Executors.newSingleThreadExecutor()
    }
}
