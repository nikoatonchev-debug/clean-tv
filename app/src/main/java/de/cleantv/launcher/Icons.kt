package de.cleantv.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Kleine weiße Symbole für die Leiste. */
object Icons {

    /** 3×3 Punkte für "Alle Apps". */
    class Dots : Drawable() {
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

    /** Symbole für die Musik-Knöpfe: "prev", "next", "play", "pause", "shuffle" */
    class Media(private val kind: String) : Drawable() {
        private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun draw(c: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            fun x(v: Float) = b.left + w * v
            fun y(v: Float) = b.top + w * v
            fun tri(x0: Float, x1: Float) = Path().apply {
                moveTo(x(x0), y(0.2f)); lineTo(x(x1), y(0.5f)); lineTo(x(x0), y(0.8f)); close()
            }
            when (kind) {
                "play" -> c.drawPath(tri(0.28f, 0.82f), f)
                "pause" -> {
                    c.drawRoundRect(x(0.26f), y(0.2f), x(0.42f), y(0.8f), w * 0.04f, w * 0.04f, f)
                    c.drawRoundRect(x(0.58f), y(0.2f), x(0.74f), y(0.8f), w * 0.04f, w * 0.04f, f)
                }
                "next" -> {
                    c.drawPath(tri(0.2f, 0.66f), f)
                    c.drawRoundRect(x(0.68f), y(0.2f), x(0.8f), y(0.8f), w * 0.03f, w * 0.03f, f)
                }
                "prev" -> {
                    c.save(); c.scale(-1f, 1f, b.exactCenterX(), b.exactCenterY())
                    c.drawPath(tri(0.2f, 0.66f), f)
                    c.drawRoundRect(x(0.68f), y(0.2f), x(0.8f), y(0.8f), w * 0.03f, w * 0.03f, f)
                    c.restore()
                }
                "close" -> {
                    s.strokeWidth = w * 0.09f
                    c.drawLine(x(0.28f), y(0.28f), x(0.72f), y(0.72f), s)
                    c.drawLine(x(0.72f), y(0.28f), x(0.28f), y(0.72f), s)
                }
                "shuffle" -> {
                    s.strokeWidth = w * 0.08f
                    val p = Path().apply {
                        moveTo(x(0.12f), y(0.3f)); cubicTo(x(0.45f), y(0.3f), x(0.5f), y(0.7f), x(0.84f), y(0.7f))
                        moveTo(x(0.12f), y(0.7f)); cubicTo(x(0.45f), y(0.7f), x(0.5f), y(0.3f), x(0.84f), y(0.3f))
                        moveTo(x(0.72f), y(0.18f)); lineTo(x(0.86f), y(0.3f)); lineTo(x(0.72f), y(0.42f))
                        moveTo(x(0.72f), y(0.58f)); lineTo(x(0.86f), y(0.7f)); lineTo(x(0.72f), y(0.82f))
                    }
                    c.drawPath(p, s)
                }
            }
        }
        override fun setAlpha(a: Int) { f.alpha = a; s.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { f.colorFilter = cf; s.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Zwei Pfeile für "Normales System". */
    class Swap : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        override fun draw(c: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val x = b.left.toFloat()
            val y = b.top.toFloat()
            p.strokeWidth = w * 0.09f
            val path = Path().apply {
                moveTo(x + w * 0.15f, y + w * 0.35f); lineTo(x + w * 0.85f, y + w * 0.35f)
                moveTo(x + w * 0.68f, y + w * 0.18f); lineTo(x + w * 0.85f, y + w * 0.35f); lineTo(x + w * 0.68f, y + w * 0.52f)
                moveTo(x + w * 0.85f, y + w * 0.67f); lineTo(x + w * 0.15f, y + w * 0.67f)
                moveTo(x + w * 0.32f, y + w * 0.5f); lineTo(x + w * 0.15f, y + w * 0.67f); lineTo(x + w * 0.32f, y + w * 0.84f)
            }
            c.drawPath(path, p)
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Haus für "Startseite". */
    class House : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        override fun draw(c: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val x = b.left.toFloat()
            val y = b.top.toFloat()
            p.strokeWidth = w * 0.1f
            val path = Path().apply {
                moveTo(x + w * 0.08f, y + w * 0.48f)
                lineTo(x + w * 0.5f, y + w * 0.1f)
                lineTo(x + w * 0.92f, y + w * 0.48f)
                moveTo(x + w * 0.2f, y + w * 0.38f)
                lineTo(x + w * 0.2f, y + w * 0.9f)
                lineTo(x + w * 0.8f, y + w * 0.9f)
                lineTo(x + w * 0.8f, y + w * 0.38f)
            }
            c.drawPath(path, p)
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
