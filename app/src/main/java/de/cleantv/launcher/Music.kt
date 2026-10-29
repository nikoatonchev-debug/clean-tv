package de.cleantv.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** Nur dafür da, dass Clean TV die laufende Musik (z. B. Spotify) sehen darf. */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() = Music.attach(applicationContext)
}

/**
 * Was gerade läuft: Titel, Cover, Spielposition, zeitgenaue Liedtexte (von LRCLIB)
 * und die Lautstärke der Musik für den pulsierenden Schimmer.
 */
object Music {

    class Track(val title: String, val artist: String, val album: String, val durationMs: Long, val art: Bitmap?)
    class Word(val text: String, val start: Long)

    var track: Track? = null; private set
    var playing = false; private set
    /** Wörter mit Startzeit, null = (noch) keine Liedtexte */
    var words: List<Word>? = null; private set
    /** Lautstärke 0..1, geglättet */
    var level = 0f; private set
    var tint = 0xFF6C7BFF.toInt(); private set

    private var posMs = 0L
    private var posAt = 0L
    private var speed = 1f
    private var controller: MediaController? = null
    private var msm: MediaSessionManager? = null
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()
    private var lyricsFor = ""
    private var visualizer: Visualizer? = null
    private var fake = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    /** aktuelle Spielposition in ms (läuft zwischen den Meldungen von Spotify weiter) */
    fun position(): Long {
        // immer den neuesten Stand von Spotify nehmen (nach Vor-/Zurückspulen sofort richtig)
        if (!fake) controller?.playbackState?.let { st ->
            if (st.lastPositionUpdateTime > 0 && st.lastPositionUpdateTime != posAt) {
                posMs = st.position; posAt = st.lastPositionUpdateTime
                speed = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
                playing = st.state == PlaybackState.STATE_PLAYING
            }
        }
        if (!playing) return posMs
        return posMs + ((SystemClock.elapsedRealtime() - posAt) * speed).toLong()
    }

    val active get() = track != null && !hiddenNow() &&
        (playing || controller?.playbackState?.state == PlaybackState.STATE_PAUSED || fake)

    /** mit dem X ausgeblendet – gilt bis zum nächsten Lied */
    private var hiddenFor: String? = null
    private fun hiddenNow() = track?.let { hiddenFor == "${it.artist}|${it.title}" } == true
    fun hide() { track?.let { hiddenFor = "${it.artist}|${it.title}" }; changed() }

    // ---------------- Spotify & Co. beobachten ----------------

    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
        if (msm != null) { pick(); return }
        try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            val comp = ComponentName(ctx, MediaListener::class.java)
            m.addOnActiveSessionsChangedListener({ pick() }, comp, main)
            msm = m
            pick()
        } catch (_: SecurityException) {
            // Zugriff noch nicht erteilt (cmd notification allow_listener …)
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onPlaybackStateChanged(state: PlaybackState?) = read()
        override fun onSessionDestroyed() = pick()
    }

    private fun pick() {
        if (fake) return
        val m = msm ?: return
        val list = try {
            m.getActiveSessions(ComponentName(appCtx!!, MediaListener::class.java))
        } catch (_: Exception) { emptyList() }
        // spielende Sitzung zuerst, Bluetooth-Reste ignorieren
        val best = list.filter { it.packageName != "com.android.bluetooth" }
            .sortedByDescending { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 2 else if (it.metadata != null) 1 else 0 }
            .firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        read()
    }

    private fun read() {
        if (fake) return
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState
        if (c == null || md == null) {
            track = null; playing = false; words = null; stopVisualizer(); changed(); return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val old = track
        if (old == null || old.title != title || old.artist != artist || old.art !== art) {
            track = Track(title, artist, album, dur, art)
            tint = tintOf(art)
        }
        playing = st?.state == PlaybackState.STATE_PLAYING
        if (st != null) {
            posMs = st.position
            posAt = if (st.lastPositionUpdateTime > 0) st.lastPositionUpdateTime else SystemClock.elapsedRealtime()
            speed = if (st.playbackSpeed > 0f) st.playbackSpeed else 1f
        }
        loadLyrics(title, artist, album, dur)
        if (playing) startVisualizer() else stopVisualizer()
        changed()
    }

    // ---------------- Liedtexte (LRCLIB) ----------------

    private fun loadLyrics(title: String, artist: String, album: String, durMs: Long) {
        // Länge gehört zum Schlüssel: Spotify meldet beim Liedwechsel oft erst 0 und dann die echte Länge
        val key = "$artist|$title|${durMs / 1000}"
        if (key == lyricsFor) return
        lyricsFor = key
        words = null
        if (title.isBlank()) return
        // ohne Liedlänge lässt sich die richtige Version (Remix, Extended …) nicht erkennen: abwarten
        if (durMs <= 0 && !fake) return
        io.execute {
            val lrc = try { fetchLrc(title, artist, album, durMs) } catch (_: Exception) { null }
            // Sicherheit: Text, der deutlich länger als das Lied ist, gehört zu einer anderen Version
            val w = lrc?.let { toWords(it) }?.takeIf { ws -> durMs <= 0 || (ws.lastOrNull()?.start ?: 0) < durMs + 2_000 }
            main.post { if (lyricsFor == key) { words = w; changed() } }
        }
    }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "CleanTV/1.0 (Android TV home screen)")
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetchLrc(title: String, artist: String, album: String, durMs: Long): String? {
        // "Song (feat. X) - Remastered" → "Song"
        val clean = title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), "").trim()
        // Nur Liedtexte nehmen, deren Länge zur gespielten Version passt – sonst laufen z. B.
        // bei "Extended Mix" die Wörter der kurzen Radio-Version eine Minute zu früh.
        val durS = durMs / 1000.0
        fun fits(o: JSONObject) = durMs <= 0 || kotlin.math.abs(o.optDouble("duration", -99.0) - durS) <= 2.0
        fun synced(o: JSONObject) = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        // alle passenden Versionen sammeln (direkter Treffer + Suche) und die beste nehmen
        val cands = ArrayList<JSONObject>()
        val seen = HashSet<Int>()
        fun add(o: JSONObject) { if (fits(o) && synced(o) != null && seen.add(o.optInt("id", cands.size))) cands += o }
        for (name in listOf(title, clean).distinct()) {
            var q = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(name)}"
            if (album.isNotBlank()) q += "&album_name=${enc(album)}"
            if (durMs > 0) q += "&duration=${durMs / 1000}"
            get(q)?.let { runCatching { add(JSONObject(it)) } }
            val s = get("https://lrclib.net/api/search?track_name=${enc(name)}&artist_name=${enc(artist)}") ?: continue
            val arr = org.json.JSONArray(s)
            for (i in 0 until arr.length()) add(arr.getJSONObject(i))
        }
        return cands.minByOrNull { score(it, durS, album, title) }?.let { synced(it) }
    }

    /**
     * Wie gut passt eine Version zum gespielten Lied? Kleiner = besser.
     * Zählt: Längenabweichung, ob der Text sinnvoll vor dem Liedende aufhört, Album und Titel genau gleich.
     */
    private fun score(o: JSONObject, durS: Double, album: String, title: String): Double {
        var sc = kotlin.math.abs(o.optDouble("duration", durS) - durS) * 3.0
        val times = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""").findAll(o.optString("syncedLyrics"))
            .map { it.groupValues[1].toDouble() * 60 + it.groupValues[2].toDouble() }.toList()
        val last = times.maxOrNull() ?: 0.0
        if (durS > 0) {
            if (last > durS) sc += 50.0                                   // Text länger als das Lied
            else if (last < durS * 0.5) sc += 10.0                        // Text hört viel zu früh auf
        }
        if (times.size < 8) sc += 5.0                                      // kaum Zeitmarken
        if (album.isNotBlank() && !o.optString("albumName").equals(album, ignoreCase = true)) sc += 1.5
        if (!o.optString("trackName").equals(title, ignoreCase = true)) sc += 1.0
        return sc
    }


    /** LRC-Zeilen → einzelne Wörter; die Zeit innerhalb einer Zeile wird nach Wortlänge verteilt. */
    private fun toWords(lrc: String): List<Word> {
        val re = Regex("""\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)""")
        val lines = lrc.lines().mapNotNull { l ->
            re.find(l)?.let { m ->
                val t = (m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong())
                t to m.groupValues[3].trim()
            }
        }
        val out = ArrayList<Word>()
        for ((i, line) in lines.withIndex()) {
            val (t, text) = line
            if (text.isEmpty()) { out += Word("", t); continue }
            val next = lines.getOrNull(i + 1)?.first ?: (t + 4000)
            val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            // gesungen wird in Silben: jede Silbe ~0,23 s, plus kurzer Atem zwischen Wörtern
            val syl = parts.map { syllables(it) }
            val sung = syl.sum() * 230.0 + parts.size * 60.0
            val span = minOf((next - t) * 0.9, sung)
            val weights = syl.map { it + 0.3 }
            val total = weights.sum()
            var acc = 0.0
            for ((k, p) in parts.withIndex()) {
                out += Word(p, t + (span * acc / total).toLong())
                acc += weights[k]
            }
            // längere Pause bis zur nächsten Zeile: Wort ausblenden statt stehen lassen
            val end = t + span.toLong() + 900
            if (next - end > 1200) out += Word("", end)
        }
        return out
    }

    /** Silben grob zählen: Vokalgruppen (inkl. Umlaute), stummes End-e abziehen. */
    private fun syllables(word: String): Int {
        val w = word.lowercase().filter { it.isLetter() }
        if (w.isEmpty()) return 1
        var n = Regex("[aeiouyäöü]+").findAll(w).count()
        if (w.length > 3 && w.endsWith("e") && !w.endsWith("le") && n > 1) n--
        return n.coerceAtLeast(1)
    }

    // ---------------- Lautstärke für den Schimmer ----------------

    private fun startVisualizer() {
        if (visualizer != null) return
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer, wave: ByteArray, rate: Int) {
                    var sum = 0.0
                    for (b in wave) { val x = ((b.toInt() and 0xFF) - 128) / 128.0; sum += x * x }
                    val rms = sqrt(sum / wave.size).toFloat()
                    val target = (rms * 3.2f).coerceIn(0f, 1f)
                    level += (target - level) * (if (target > level) 0.6f else 0.15f)
                }
                override fun onFftDataCapture(vz: Visualizer, fft: ByteArray, rate: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            v.enabled = true
            visualizer = v
        } catch (_: Throwable) {
            visualizer = null   // ohne Aufnahme-Erlaubnis: sanftes Pulsieren statt echter Lautstärke
        }
    }

    private fun stopVisualizer() {
        try { visualizer?.release() } catch (_: Throwable) {}
        visualizer = null
        level = 0f
    }

    val hasRealLevel get() = visualizer != null

    private fun tintOf(art: Bitmap?): Int {
        if (art == null) return 0xFF6C7BFF.toInt()
        val s = Bitmap.createScaledBitmap(art, 8, 8, true)
        // kräftigste Farbe des Covers nehmen, nicht den Durchschnitt (der wird grau)
        var best = 0xFF6C7BFF.toInt(); var bestSat = -1f
        val hsv = FloatArray(3)
        for (x in 0 until 8) for (y in 0 until 8) {
            val c = s.getPixel(x, y)
            android.graphics.Color.colorToHSV(c, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestSat) { bestSat = score; best = c }
        }
        android.graphics.Color.colorToHSV(best, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.8f); hsv[2] = 0.95f
        return android.graphics.Color.HSVToColor(hsv)
    }

    // ---------------- Bedienung ----------------

    /** Zufallswiedergabe: Spotify meldet den Zustand nicht zurück, daher merken wir ihn selbst. */
    var shuffle = false; private set

    fun prev() { controller?.transportControls?.skipToPrevious() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun playPause() {
        val tc = controller?.transportControls ?: return
        if (playing) tc.pause() else tc.play()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        shuffle = !shuffle
        // so schalten auch Spotify-eigene Fernbedienungen die Zufallswiedergabe
        c.transportControls.sendCustomAction("android.support.v4.media.session.action.SET_SHUFFLE_MODE",
            android.os.Bundle().apply {
                putInt("android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE", if (shuffle) 1 else 0)
            })
        changed()
    }

    /**
     * Spotify-DJ starten. Erst unauffällig über die Wiedergabe-Steuerung; springt das Lied
     * nach 4 s nicht um (z. B. wenn Spotify gerade über das Handy läuft), öffnet sich der DJ direkt in Spotify.
     */
    fun startDj(ctx: Context) {
        val uri = android.net.Uri.parse("spotify:playlist:37i9dQZF1EYkqdzj48dyYq")
        val openApp = {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .setPackage(Apps.SPOTIFY).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val c = controller
        if (c == null || c.packageName != Apps.SPOTIFY) { openApp(); return }
        val before = track?.let { "${it.artist}|${it.title}" }
        try { c.transportControls.playFromUri(uri, null) } catch (_: Exception) { openApp(); return }
        main.postDelayed({
            val now = track?.let { "${it.artist}|${it.title}" }
            if (now == before || !playing) openApp()
        }, 4_000)
    }

    // ---------------- Test ohne Spotify-Anmeldung ----------------

    /** Spielt "zum Schein" einen Titel ab (Cover + Liedtexte echt geladen), um die Anzeige zu prüfen. */
    fun fakePlay(title: String, artist: String, album: String, durMs: Long, startMs: Long) {
        fake = true
        track = Track(title, artist, album, durMs, null)
        playing = true; posMs = startMs; posAt = SystemClock.elapsedRealtime(); speed = 1f
        lyricsFor = ""
        loadLyrics(title, artist, album, durMs)
        io.execute {
            val art = try {
                val js = get("https://itunes.apple.com/search?term=${enc("$artist $title")}&entity=song&limit=1")
                val url = JSONObject(js!!).getJSONArray("results").getJSONObject(0).getString("artworkUrl100")
                    .replace("100x100", "600x600")
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            main.post { track = Track(title, artist, album, durMs, art); tint = tintOf(art); changed() }
        }
        changed()
    }

    fun stopFake() { if (fake) { fake = false; track = null; playing = false; words = null; lyricsFor = ""; changed(); pick() } }
}
