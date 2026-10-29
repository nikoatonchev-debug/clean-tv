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
        val t = ((now - changedAt) / 260f).coerceIn(0f, 1f)
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
                // Pause / Zwischenspiel: drei Punkte, die ruhig im Takt atmen
                drawDots(c, cx, top + h * 0.13f, h, now, pulse, main, fadeA)
            }
            return true
        }

        // ---- Wörter untereinander: voriges oben, aktuelles groß in der Mitte, nächstes unten ----
        // beim Wechsel rutscht alles eine Zeile nach oben: das alte Wort wird kleiner, das neue größer
        big.textSize = h * 0.17f
        big.getTextBounds("Hg", 0, 2, r)
        val baseY = top - r.top + h * 0.05f
        val gap = h * 0.125f
        val bigSize = h * 0.17f
        val smallSize = h * 0.042f
        val maxW = w * 0.62f
        val slide = (i - 1) + ease          // fließende Position zwischen zwei Wörtern
        big.color = main
        if (!dark) big.setShadowLayer(h * 0.012f, 0f, h * 0.003f, 0x40000000)
        for (k in (i - 2)..(i + 1)) {
            val txt = words.getOrNull(k)?.text.orEmpty()
            if (txt.isEmpty()) continue
            val d = k - slide                  // 0 = Mitte, -1 = oben, +1 = unten
            val ad = kotlin.math.abs(d)
            if (ad >= 2f) continue
            var size = smallSize + (bigSize - smallSize) * (1f - ad.coerceAtMost(1f))
            big.textSize = size
            val tw = big.measureText(txt)
            if (tw > maxW) { size *= maxW / tw; big.textSize = size }
            val alpha = if (ad <= 1f) 1f - 0.5f * ad else 0.5f * (2f - ad)
            big.alpha = (255 * fadeA * alpha).toInt()
            val pop = if (ad < 0.5f) 1f + 0.03f * pulse else 1f
            c.save()
            c.scale(pop, pop, cx, baseY + d * gap)
            c.drawText(txt, cx, baseY + d * gap, big)
            c.restore()
        }
        return true
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawDots(c: Canvas, cx: Float, cy: Float, h: Float, now: Long, pulse: Float, color: Int, fadeA: Float) {
        val r = h * 0.02f * (1f + 0.08f * pulse)
        val gap = h * 0.07f
        dot.color = color
        for (k in 0..2) {
            // weiche Welle von links nach rechts, endlos
            val wave = (0.5f + 0.5f * sin(now / 380.0 - k * 0.9).toFloat())
            dot.alpha = (255 * fadeA * (0.3f + 0.7f * wave)).toInt()
            c.drawCircle(cx + (k - 1) * gap, cy - wave * h * 0.008f, r * (0.8f + 0.25f * wave), dot)
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
