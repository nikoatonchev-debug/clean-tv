package de.cleantv.launcher

import android.content.Context
import org.json.JSONArray

/** Ein Hintergrund: Foto (back), freigestellter Berg (front) und wo die Uhr sitzt. */
data class Wall(
    val id: String,
    val back: String,
    val front: String,
    val clockTop: Float,
    val dark: Boolean,
    val credit: String,
    val mask: Boolean,
) {
    val thumb get() = "walls/${id}_thumb.jpg"
}

object Walls {
    const val DEFAULT = "p058"
    private var list: List<Wall>? = null

    fun all(ctx: Context): List<Wall> {
        list?.let { return it }
        val json = ctx.assets.open("walls/walls.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Wall(
                o.getString("id"), "walls/" + o.getString("back"), "walls/" + o.getString("front"),
                o.getDouble("clockTop").toFloat(), o.getBoolean("dark"), o.optString("credit"), o.optBoolean("mask"),
            )
        }.also { list = it }
    }

    fun current(ctx: Context): Wall {
        val id = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString("wall", DEFAULT)
        val all = all(ctx)
        return all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }
    }

    fun select(ctx: Context, w: Wall) {
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("wall", w.id).apply()
    }
}
