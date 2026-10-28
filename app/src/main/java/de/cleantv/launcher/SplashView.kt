package de.cleantv.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Einschalt-Bildschirm: Das Clean-TV-Logo (Fernseher-Umriss mit Berglinie und Sonne)
 * zeichnet sich wie mit einem Lichtstrich, der Schriftzug gleitet hoch, dann blendet alles aus.
 */
class SplashView(ctx: Context, private val font: Typeface, private val onDone: () -> Unit) : View(ctx) {

    private var t = 0f   // vergangene Sekunden
    private val total = 3.1f

    private val bg = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0x66BFD4FF
    }
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD9A8.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = font
        letterSpacing = 0.02f
    }

    private val frame = Path()
    private val ridge = Path()
    private val stand = Path()
    private val seg = Path()
    private var frameLen = 0f
    private var ridgeLen = 0f
    private var standLen = 0f
    private var sunX = 0f
    private var sunY = 0f
    private var sunR = 0f
    private var textY = 0f

    fun start() {
        // Die Box hat Animationen ggf. verkürzt – hier soll die echte Zeit gelten
        val scale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (_: Exception) { 1f }.let { if (it <= 0f) 1f else it }
        ValueAnimator.ofFloat(0f, total).apply {
            duration = (total * 1000 / scale).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
        postDelayed({ onDone() }, (total * 1000).toLong())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val d = resources.displayMetrics.density
        bg.shader = RadialGradient(w / 2f, h * 0.42f, w * 0.75f,
            intArrayOf(0xFF1B2152.toInt(), 0xFF0A0D24.toInt(), 0xFF05060F.toInt()), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP)
        val fw = 176 * d
        val fh = 110 * d
        val cx = w / 2f
        val top = h * 0.40f - fh / 2
        val r = RectF(cx - fw / 2, top, cx + fw / 2, top + fh)
        line.strokeWidth = 6.5f * d
        glow.strokeWidth = 16 * d

        frame.reset()
        frame.addRoundRect(r, 24 * d, 24 * d, Path.Direction.CW)
        frameLen = PathMeasure(frame, false).length

        fun px(f: Float) = r.left + r.width() * f
        fun py(f: Float) = r.top + r.height() * f
        ridge.reset()
        ridge.moveTo(px(0.13f), py(0.76f))
        ridge.lineTo(px(0.38f), py(0.40f))
        ridge.lineTo(px(0.50f), py(0.56f))
        ridge.lineTo(px(0.66f), py(0.28f))
        ridge.lineTo(px(0.87f), py(0.76f))
        ridgeLen = PathMeasure(ridge, false).length

        stand.reset()
        stand.moveTo(px(0.36f), r.bottom + 16 * d)
        stand.lineTo(px(0.64f), r.bottom + 16 * d)
        standLen = PathMeasure(stand, false).length

        sunX = px(0.27f); sunY = py(0.30f); sunR = 8.5f * d
        text.textSize = 40 * d
        textY = r.bottom + 72 * d
    }

    private fun prog(from: Float, len: Float) = ((t - from) / len).coerceIn(0f, 1f)
    private fun ease(x: Float) = 1 - (1 - x) * (1 - x) * (1 - x)

    private fun drawTrim(c: Canvas, p: Path, len: Float, f: Float) {
        if (f <= 0f) return
        seg.reset()
        PathMeasure(p, false).getSegment(0f, len * ease(f), seg, true)
        c.drawPath(seg, glow)
        c.drawPath(seg, line)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        // Ausblenden am Ende: alles wird leicht größer und verschwindet
        val out = prog(2.45f, 0.65f)
        val a = (255 * (1 - out)).toInt()
        line.alpha = a; text.alpha = a; sun.alpha = a
        glow.alpha = (0x66 * (1 - out) * (1 - prog(1.0f, 1.2f) * 0.6f)).toInt()
        c.save()
        val s = 1f + 0.05f * ease(out)
        c.scale(s, s, width / 2f, height * 0.45f)

        drawTrim(c, frame, frameLen, prog(0.15f, 0.95f))
        drawTrim(c, stand, standLen, prog(0.85f, 0.35f))
        drawTrim(c, ridge, ridgeLen, prog(0.75f, 0.75f))

        // Sonne "ploppt" auf
        val sp = prog(1.35f, 0.45f)
        if (sp > 0f) {
            val pop = if (sp < 0.7f) sp / 0.7f * 1.15f else 1.15f - (sp - 0.7f) / 0.3f * 0.15f
            c.drawCircle(sunX, sunY, sunR * pop, sun)
        }

        // Schriftzug gleitet hoch
        val tp = ease(prog(1.45f, 0.6f))
        if (tp > 0f) {
            text.alpha = (a * tp).toInt()
            c.drawText("Clean TV", width / 2f, textY + (1 - tp) * 18 * resources.displayMetrics.density, text)
        }
        c.restore()
    }
}
