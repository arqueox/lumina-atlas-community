package dev.lumina.atlas.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.lumina.atlas.data.AppEntry
import dev.lumina.atlas.data.Catalog
import dev.lumina.atlas.data.Details
import dev.lumina.atlas.data.Movie
import dev.lumina.atlas.data.Show
import dev.lumina.atlas.data.NowEntry
import dev.lumina.atlas.data.DEFAULT_FAVORITES
import dev.lumina.atlas.data.LauncherPrefs
import dev.lumina.atlas.data.MAX_FAVORITES

enum class Section { INICIO, DIRETO, FILMES, SERIES, APPS, FONTE }

/** A film or series opened from the catalogue; details arrive after it opens. */
data class DetailState(val movie: Movie?, val show: Show?, val details: Details?, val loading: Boolean)

/** One tile of the "A ver agora" row: what was last played in an app, or a pinned app with nothing yet. */
data class RowItem(val app: AppEntry, val now: NowEntry?)

/** Everything the screen shows; the activity feeds it and handles the actions. */
class LauncherState(private val prefs: LauncherPrefs, private val ownPkg: String) {
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var favorites by mutableStateOf<List<String>>(emptyList())
        private set
    var recents by mutableStateOf<List<Pair<String, Long>>>(emptyList())
        private set
    var section by mutableStateOf(Section.INICIO)
    /** The app the hero shows: the focused one, else the last opened. */
    var heroPkg by mutableStateOf<String?>(null)
    /** The options sheet (pin, move, app info), or null. */
    var menuFor by mutableStateOf<AppEntry?>(null)
    var now by mutableLongStateOf(System.currentTimeMillis())
    var toast by mutableStateOf<String?>(null)
    /** Bumped when Home is pressed again, so the screen scrolls back and refocuses the row. */
    var homeTick by mutableIntStateOf(0)
    /** What each app last played (newest first) and the artwork it sent. */
    var nowPlaying by mutableStateOf<List<NowEntry>>(emptyList())
        private set
    var nowArt by mutableStateOf<Map<String, ImageBitmap>>(emptyMap())
        private set
    /** False until the user (or adb) grants notification access to the watcher. */
    var canWatch by mutableStateOf(false)

    // --- IPTV ----------------------------------------------------------------------------------------
    var catalog by mutableStateOf(Catalog.EMPTY)
    var catalogLoading by mutableStateOf(false)
    var catalogError by mutableStateOf<String?>(null)
    /** "host · XTREAM" or "host · LISTA M3U", or null before the user connects a source. */
    var sourceLabel by mutableStateOf<String?>(null)
    var builtIn by mutableStateOf(false)
    /** Selected group per catalogue section (channel group, film or series category). */
    var tabs by mutableStateOf<Map<Section, String>>(emptyMap())
    var detail by mutableStateOf<DetailState?>(null)
    var setupError by mutableStateOf<String?>(null)
    var setupBusy by mutableStateOf(false)
    /** The public edition opens the source page by itself once per launch. */
    var askedSource = false

    fun setNow(entries: List<NowEntry>, art: Map<String, ImageBitmap>) {
        nowPlaying = entries
        nowArt = art
    }

    fun now(pkg: String?) = nowPlaying.firstOrNull { it.pkg == pkg }

    /** The row of six: apps with something playing or paused first (newest first), then the pinned apps. */
    val row: List<RowItem> get() {
        val watched = nowPlaying.mapNotNull { n -> app(n.pkg)?.let { RowItem(it, n) } }.take(MAX_FAVORITES)
        val pinned = favoriteApps.filter { a -> watched.none { it.app.pkg == a.pkg } }.map { RowItem(it, null) }
        return (watched + pinned).take(MAX_FAVORITES)
    }

    fun load(list: List<AppEntry>) {
        apps = list
        val installed = list.map { it.pkg }.toSet()
        favorites = (prefs.favorites() ?: DEFAULT_FAVORITES).filter { it in installed }.take(MAX_FAVORITES)
        recents = prefs.recents().filter { it.first in installed }
        if (heroPkg == null || heroPkg !in installed) heroPkg = recents.firstOrNull()?.first ?: favorites.firstOrNull()
    }

    fun app(pkg: String?): AppEntry? = apps.firstOrNull { it.pkg == pkg } ?: if (pkg == ownPkg) self else null

    /** The launcher's own IPTV, as a tile of the "A ver agora" row. */
    private val self get() = AppEntry(
        pkg = ownPkg, activity = "", label = "Televisão", name = "TELEVISÃO",
        kind = when (now(ownPkg)?.kind) { "live" -> "DIRETO"; "episode" -> "SÉRIES"; else -> "FILMES" },
        version = "—", art = null, artIsBanner = false,
    )
    val favoriteApps get() = favorites.mapNotNull(::app)
    val hero get() = app(heroPkg) ?: favoriteApps.firstOrNull() ?: apps.firstOrNull()
    fun lastOpened(pkg: String) = recents.firstOrNull { it.first == pkg }?.second

    fun opened(pkg: String) {
        prefs.touch(pkg)
        recents = prefs.recents()
    }

    fun isFavorite(pkg: String) = pkg in favorites

    /** False when the row is full. */
    fun toggleFavorite(pkg: String): Boolean {
        val next = if (pkg in favorites) favorites - pkg else {
            if (favorites.size >= MAX_FAVORITES) return false
            favorites + pkg
        }
        favorites = next
        prefs.setFavorites(next)
        return true
    }

    fun move(pkg: String, by: Int) {
        val i = favorites.indexOf(pkg)
        val j = i + by
        if (i < 0 || j !in favorites.indices) return
        val next = favorites.toMutableList().apply { add(j, removeAt(i)) }
        favorites = next
        prefs.setFavorites(next)
    }
}
