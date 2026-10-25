package dev.lumina.atlas.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import dev.lumina.atlas.MediaWatcher

/** The last thing played in one app, as its media session described it. */
data class NowEntry(
    val pkg: String,
    val title: String,
    val subtitle: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val at: Long,
    /** Artwork saved by the watcher, or null when the app sent none. */
    val artFile: File?,
    /** For the launcher's own IPTV entry: what to play again ("live", "movie", "episode"), else empty. */
    val kind: String = "",
    val url: String = "",
    val image: String = "",
)

/**
 * What each app last played, kept after the app closes. Fed by [MediaWatcher] (and by the launcher when it comes to
 * the front); one SharedPreferences entry per package plus its artwork as a file.
 */
object NowPlayingStore {
    const val PREFS = "now_playing"
    private const val MAX_ART = 1280
    // Session owners that are not something to "continue watching".
    private val IGNORED = setOf("com.google.android.bluetooth", "android", "com.android.systemui")

    private fun artDir(context: Context) = File(context.filesDir, "now-art").apply { mkdirs() }

    fun all(context: Context): List<NowEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (pkg, raw) ->
            runCatching {
                val o = JSONObject(raw as String)
                val art = File(artDir(context), "$pkg.png").takeIf { o.optBoolean("art") && it.exists() }
                NowEntry(
                    pkg, o.getString("title"), o.optString("subtitle"), o.optBoolean("playing"),
                    o.optLong("position"), o.optLong("duration"), o.getLong("at"), art,
                    o.optString("kind"), o.optString("url"), o.optString("image"),
                )
            }.getOrNull()
        }.sortedByDescending { it.at }
    }

    /** Reads every active session now. Needs notification access for [MediaWatcher]; quietly does nothing without it. */
    fun refresh(context: Context) {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return
        val sessions = runCatching { manager.getActiveSessions(ComponentName(context, MediaWatcher::class.java)) }
            .getOrNull() ?: return
        sessions.forEach { record(context, it) }
    }

    /** Saves what [controller] is showing, if it names something. Blocking (artwork may be downloaded). */
    fun record(context: Context, controller: MediaController) {
        val pkg = controller.packageName
        if (pkg == context.packageName || pkg in IGNORED) return
        val meta = controller.metadata ?: return
        val title = meta.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: meta.text(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val subtitle = meta.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: meta.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.text(MediaMetadata.METADATA_KEY_ALBUM)
            ?: ""
        val state = controller.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(pkg, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val sameItem = previous?.optString("title") == title

        val artFile = File(artDir(context), "$pkg.png")
        val hasArt = if (sameItem && previous?.optBoolean("art") == true && artFile.exists()) true else {
            val bitmap = artwork(context, meta)
            if (bitmap != null) {
                runCatching { artFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } }.isSuccess
            } else {
                artFile.delete()
                false
            }
        }
        val entry = JSONObject()
            .put("title", title)
            .put("subtitle", subtitle)
            .put("playing", playing)
            .put("position", state?.position?.coerceAtLeast(0) ?: 0)
            .put("duration", meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0))
            // Paused or stopped items keep the time they were last played, so the row's order stays honest.
            .put("at", if (playing || !sameItem) System.currentTimeMillis() else previous?.optLong("at") ?: System.currentTimeMillis())
            .put("art", hasArt)
        prefs.edit().putString(pkg, entry.toString()).apply()
    }

    /** The launcher's own player: what it plays, with enough to start it again. Blocking (downloads the image once). */
    fun recordOwn(context: Context, item: dev.lumina.atlas.player.PlayItem, playing: Boolean, positionMs: Long, durationMs: Long) {
        val pkg = context.packageName
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(pkg, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val artFile = File(artDir(context), "$pkg.png")
        val sameImage = previous?.optString("image") == item.image && artFile.exists()
        val hasArt = sameImage || (item.image.isNotBlank() && load(context, item.image)?.let { bitmap ->
            runCatching { artFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } }.isSuccess
        } == true)
        if (!hasArt) artFile.delete()
        val entry = JSONObject()
            .put("title", item.title).put("subtitle", item.subtitle).put("playing", playing)
            .put("position", positionMs).put("duration", durationMs).put("at", System.currentTimeMillis())
            .put("art", hasArt).put("kind", item.kind).put("url", item.url).put("image", item.image)
        prefs.edit().putString(pkg, entry.toString()).apply()
    }

    fun forget(context: Context, pkg: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(pkg).apply()
        File(artDir(context), "$pkg.png").delete()
    }

    private fun MediaMetadata.text(key: String) = getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun artwork(context: Context, meta: MediaMetadata): Bitmap? {
        val bitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                .firstNotNullOfOrNull { key -> meta.getString(key)?.let { load(context, it) } }
            ?: return null
        val scale = MAX_ART.toFloat() / maxOf(bitmap.width, bitmap.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    }

    private fun load(context: Context, uri: String): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "http", "https" -> {
                val conn = URL(uri).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                try { conn.inputStream.use { BitmapFactory.decodeStream(it) } } finally { conn.disconnect() }
            }
            "content", "android.resource", "file" -> context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it) }
            else -> null
        }
    }.getOrNull()
}
