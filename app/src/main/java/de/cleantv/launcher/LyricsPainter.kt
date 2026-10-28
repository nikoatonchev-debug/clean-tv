package de.cleantv.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Liedtext wie im Video: das gesungene Wort groß und fett in der Mitte,
 * das vorige klein links darüber, das nächste klein rechts darunter.
 * Dahinter ein weicher Schimmer in der Farbe des Covers, der zur Musik pulsiert.
 */
class LyricsPainter(private val ctx: android.content.Context) {

    private fun inter(wght: Int) = try {
        Typeface.Builder(ctx.assets, "inter.ttf").setFontVariationSettings("'wght' $wght, 'opsz' 32").build()
    } catch (_: Exception) { Typeface.DEFAULT_BOLD }

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = inter(800)
        textAlign = Paint.Align.CENTER
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = inter(650)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = Rect()

    private var shownIdx = -1
    private var changedAt = 0L

    /** zeichnet und gibt true zurück, wenn gerade Liedtext angezeigt wird */
    fun draw(c: Canvas, w: Float, h: Float, top: Float, dim: Float, dark: Boolean): Boolean {
        if (!Music.active) return false
        val words = Music.words.orEmpty()
        val pos = Music.position() + 220   // Wort minimal früher zeigen, damit es mit dem Ton erscheint

        var i = -1
        // binäre Suche nach dem letzten Wort, das schon begonnen hat
        var lo = 0; var hi = words.size - 1
        while (lo <= hi) { val m = (lo + hi) / 2; if (words[m].start <= pos) { i = m; lo = m + 1 } else hi = m - 1 }
        if (i != shownIdx) { shownIdx = i; changedAt = SystemClock.uptimeMillis() }

        val cur = words.getOrNull(i)?.text.orEmpty()
        // in Pausen (Zwischenspiel) nur der Schimmer
        val now = SystemClock.uptimeMillis()
        val t = ((now - changedAt) / 140f).coerceIn(0f, 1f)
        val ease = 1 - (1 - t) * (1 - t) * (1 - t)

        // ---- Schimmer ----
        val pulse = if (Music.hasRealLevel) Music.level
            else if (Music.playing) (0.35f + 0.25f * sin(now / 260.0).toFloat()) else 0.2f
        val cx = w / 2
        val cy = top + h * 0.11f
        val rad = h * (0.30f + 0.10f * pulse)
        val tint = Music.tint
        val a = ((70 + 110 * pulse) * (1 - 0.5f * dim)).toInt().coerceIn(0, 255)
        glow.shader = RadialGradient(cx, cy, rad,
            intArrayOf(Color.argb(a, Color.red(tint), Color.green(tint), Color.blue(tint)), 0x00000000),
            null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, glow)

        val main = if (dark) 0xFF1A1D2E.toInt() else Color.WHITE
        val fadeA = (1 - 0.3f * dim)

        // vor dem ersten Wort, in Pausen oder ohne passenden Liedtext: Melodie wie bei Spotify
        if (cur.isEmpty()) {
            if (!Music.playing) return false
            if (words.none { it.text.isNotEmpty() }) {
                // kein Liedtext gefunden: Songtitel groß und schlicht
                drawTitle(c, cx, top, w, h, main, fadeA, dark)
            } else {
                // Pause: drei Punkte füllen sich bis zum nächsten Gesang (wie bei Spotify)
                val from = words.getOrNull(i)?.start ?: 0L
                val to = words.drop(maxOf(i + 1, 0)).firstOrNull { it.text.isNotEmpty() }?.start ?: (from + 5000)
                val p = ((pos - from).toFloat() / maxOf(1L, to - from)).coerceIn(0f, 1f)
                drawDots(c, cx, top + h * 0.13f, h, p, pulse, main, fadeA)
            }
            return true
        }

        // ---- großes Wort: passt sich der Breite an ----
        big.textSize = h * 0.20f
        val maxW = w * 0.62f
        val tw = big.measureText(cur)
        if (tw > maxW) big.textSize *= maxW / tw
        big.getTextBounds("Hg", 0, 2, r)
        val capTop = r.top
        val baseY = top - capTop + h * 0.05f
        val scale = 0.86f + 0.14f * ease + 0.03f * pulse
        big.color = main
        big.alpha = (255 * fadeA * (0.35f + 0.65f * ease)).toInt()
        if (!dark) big.setShadowLayer(h * 0.012f, 0f, h * 0.003f, 0x40000000)
        c.save()
        c.scale(scale, scale, cx, baseY + capTop / 2)
        c.drawText(cur, cx, baseY, big)
        c.restore()
        val curW = big.measureText(cur) * scale

        // ---- vorheriges / nächstes Wort klein daneben ----
        small.textSize = h * 0.045f
        small.color = main
        if (!dark) small.setShadowLayer(h * 0.008f, 0f, 0f, 0x40000000)
        val prev = words.getOrNull(i - 1)?.text.orEmpty()
        val next = words.getOrNull(i + 1)?.text.orEmpty()
        val left = cx - curW / 2
        val right = cx + curW / 2
        if (prev.isNotEmpty()) {
            small.textAlign = Paint.Align.LEFT
            small.alpha = (170 * fadeA * (1 - 0.4f * ease)).toInt()
            c.drawText("– $prev", max(w * 0.12f, left), baseY + capTop * 1.08f - (1 - ease) * h * 0.03f, small)
        }
        if (next.isNotEmpty()) {
            small.textAlign = Paint.Align.RIGHT
            small.alpha = (150 * fadeA * ease).toInt()
            c.drawText(next, min(w * 0.88f, right), baseY + h * 0.065f + (1 - ease) * h * 0.02f, small)
        }
        return true
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawDots(c: Canvas, cx: Float, cy: Float, h: Float, p: Float, pulse: Float, color: Int, fadeA: Float) {
        val r = h * 0.022f * (1f + 0.1f * pulse)
        val gap = h * 0.075f
        dot.color = color
        for (k in 0..2) {
            // jeder Punkt füllt sich in seinem Drittel weich auf
            val f = ((p * 3f) - k).coerceIn(0f, 1f)
            dot.alpha = (255 * fadeA * (0.16f + 0.84f * f)).toInt()
            c.drawCircle(cx + (k - 1) * gap, cy, r * (0.7f + 0.3f * f), dot)
        }
    }

    private fun drawTitle(c: Canvas, cx: Float, top: Float, w: Float, h: Float, color: Int, fadeA: Float, dark: Boolean) {
        val t = Music.track ?: return
        big.textSize = h * 0.13f
        val maxW = w * 0.62f
        val tw = big.measureText(t.title)
        if (tw > maxW) big.textSize *= maxW / tw
        big.color = color
        big.alpha = (255 * fadeA).toInt()
        if (!dark) big.setShadowLayer(h * 0.012f, 0f, h * 0.003f, 0x40000000)
        big.getTextBounds("Hg", 0, 2, r)
        val base = top - r.top + h * 0.05f
        c.drawText(t.title, cx, base, big)
        small.textAlign = Paint.Align.CENTER
        small.textSize = h * 0.04f
        small.color = color
        small.alpha = (190 * fadeA).toInt()
        c.drawText(t.artist, cx, base + h * 0.07f, small)
    }
}
