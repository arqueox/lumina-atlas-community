package dev.lumina.atlas.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * IPTV from the source the user enters on the device: an Xtream Codes account (player_api.php) or any M3U playlist,
 * by address or as a file. The app talks straight to the provider; nothing goes through a server.
 */

data class Channel(val id: Long, val name: String, val logo: String, val group: String, val url: String)
data class Movie(val id: Long, val name: String, val poster: String, val category: String, val url: String, val rating: Double)
data class Show(val id: Long, val name: String, val poster: String, val category: String)
data class Episode(val id: Long, val season: Int, val num: Int, val title: String, val url: String)
data class Details(val plot: String, val backdrop: String, val year: String, val duration: String, val genre: String, val episodes: List<Episode>)

data class Catalog(val channels: List<Channel>, val movies: List<Movie>, val shows: List<Show>) {
    /** Channel groups in playlist order. */
    val groups: List<String> get() = channels.map { it.group.ifBlank { "Canais" } }.distinct()
    companion object { val EMPTY = Catalog(emptyList(), emptyList(), emptyList()) }
}

/** Where the catalogue comes from. */
sealed class IptvSource {
    data class Xtream(val base: String, val username: String, val password: String) : IptvSource()
    data class M3u(val url: String?, val file: String?) : IptvSource()

    /** For the "Fonte atual" line: host and kind, never the credentials. */
    val label: String get() = when (this) {
        is Xtream -> (Uri.parse(base).host ?: base) + " · XTREAM"
        is M3u -> (url?.let { Uri.parse(it).host } ?: "ficheiro") + " · LISTA M3U"
    }

    companion object {
        /**
         * The same rules as the web server (server/provider-config.js): get.php / player_api.php links, user:pass@host
         * and Xtream stream paths are Xtream accounts; any other http(s) address is an M3U playlist.
         */
        fun parse(input: String, savedFile: String? = null): IptvSource {
            val lines = input.removePrefix("﻿").lines().map { it.trim() }
            val shortcut = lines.firstOrNull { it.startsWith("URL=", ignoreCase = true) }
            val isPlaylist = lines.firstOrNull()?.startsWith("#EXTM3U", true) == true || lines.any { it.startsWith("#EXTINF", true) }
            if (isPlaylist && shortcut == null) {
                val first = lines.firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?: throw IllegalArgumentException("A lista M3U não contém endereços HTTP")
                return xtreamFrom(first) ?: savedFile?.let { M3u(null, it) }
                    ?: throw IllegalArgumentException("Escolhe a lista como ficheiro ou indica o seu URL")
            }
            val raw = shortcut?.substring(4)?.trim()
                ?: lines.firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?: throw IllegalArgumentException("Indica um endereço http:// ou https://")
            return xtreamFrom(raw) ?: M3u(raw, null)
        }

        private fun xtreamFrom(raw: String): Xtream? {
            val uri = Uri.parse(raw)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw IllegalArgumentException("Endereço inválido")
            val path = (uri.path ?: "").trimEnd('/')
            val endpoint = Regex("/(get|player_api)\\.php$", RegexOption.IGNORE_CASE).containsMatchIn(path)
            val stream = Regex("^(.*)/(live|movie|series)/([^/]+)/([^/]+)/[^/]+$", RegexOption.IGNORE_CASE).find(path)
            val info = uri.userInfo
            if (!endpoint && stream == null && !(info != null && path.isEmpty())) return null
            val user = uri.getQueryParameter("username") ?: info?.substringBefore(':')?.let(Uri::decode) ?: stream?.groupValues?.get(3)?.let(Uri::decode)
            val pass = uri.getQueryParameter("password") ?: info?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }?.let(Uri::decode) ?: stream?.groupValues?.get(4)?.let(Uri::decode)
            if (user.isNullOrBlank() || pass.isNullOrBlank()) throw IllegalArgumentException("O endereço Xtream não tem utilizador e palavra-passe")
            val prefix = if (endpoint) path.replace(Regex("/(get|player_api)\\.php$", RegexOption.IGNORE_CASE), "") else stream?.groupValues?.get(1).orEmpty()
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return Xtream("${uri.scheme}://${uri.host}$port$prefix", user, pass)
        }
    }
}

private const val UA = "VLC/3.0.20 LibVLC/3.0.20"

/*
 * Some Portuguese providers mark their sections with fake channels such as "█ GENERALISTAS █" (the web app's
 * organisePortugueseChannels does the same). With such rows, only the channels under Generalistas, Desporto and
 * Filmes are kept, grouped by those names; without them, every channel stays under its provider category.
 */
private val BLOCKS = Regex("[█■▓▒░▉▊▌]")

private fun normalise(s: String) =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

private fun organiseChannels(channels: List<Channel>): List<Channel> {
    if (channels.none { BLOCKS.containsMatchIn(it.name) }) return channels
    val out = mutableListOf<Channel>()
    var section: String? = null
    var previousGroup: String? = null
    for (c in channels) {
        val group = normalise(c.group)
        if (previousGroup != null && group != previousGroup) section = null
        previousGroup = group
        if (BLOCKS.containsMatchIn(c.name)) {
            val v = normalise(c.name).replace(BLOCKS, " ").trim()
            section = when {
                "generalista" in v -> "Generalistas"
                "desporto" in v || "sport" in v -> "Desporto"
                "cinema" in v || "filme" in v || "serie" in v -> "Filmes"
                else -> null
            }
            continue
        }
        section?.let { out += c.copy(group = it) }
    }
    return out.ifEmpty { channels.filterNot { BLOCKS.containsMatchIn(it.name) } }
}

private fun httpGet(url: String, timeoutMs: Int = 30_000): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", UA)
    try {
        if (conn.responseCode !in 200..299) throw IllegalStateException("O fornecedor respondeu HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

/** Xtream Codes player_api.php. */
private class XtreamClient(private val x: IptvSource.Xtream) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun api(action: String, extra: String = "") =
        httpGet("${x.base}/player_api.php?username=${enc(x.username)}&password=${enc(x.password)}&action=$action$extra", 60_000)

    private fun categories(action: String): Map<String, String> = runCatching {
        val arr = JSONArray(api(action))
        (0 until arr.length()).associate { i -> arr.getJSONObject(i).let { it.optString("category_id") to it.optString("category_name") } }
    }.getOrDefault(emptyMap())

    private fun path(kind: String, id: Long, ext: String) = "${x.base}/$kind/${enc(x.username)}/${enc(x.password)}/$id.$ext"

    fun catalog(): Catalog {
        val liveCats = categories("get_live_categories")
        val live = JSONArray(api("get_live_streams"))
        val channels = (0 until live.length()).map { live.getJSONObject(it) }.map { o ->
            val id = o.optLong("stream_id")
            Channel(id, o.optString("name").ifBlank { "Canal $id" }, o.optString("stream_icon"), liveCats[o.optString("category_id")].orEmpty(), path("live", id, "ts"))
        }
        val vodCats = categories("get_vod_categories")
        val vod = runCatching { JSONArray(api("get_vod_streams")) }.getOrDefault(JSONArray())
        val movies = (0 until vod.length()).map { vod.getJSONObject(it) }
            .filter { it.optInt("is_adult") != 1 && !vodCats[it.optString("category_id")].orEmpty().contains("adult", true) }
            .map { o ->
                val id = o.optLong("stream_id")
                Movie(id, o.optString("name"), o.optString("stream_icon"), vodCats[o.optString("category_id")].orEmpty(),
                    path("movie", id, o.optString("container_extension").ifBlank { "mp4" }), o.optDouble("rating_5based", 0.0))
            }
        val seriesCats = categories("get_series_categories")
        val series = runCatching { JSONArray(api("get_series")) }.getOrDefault(JSONArray())
        val shows = (0 until series.length()).map { series.getJSONObject(it) }.map { o ->
            Show(o.optLong("series_id"), o.optString("name"), o.optString("cover"), seriesCats[o.optString("category_id")].orEmpty())
        }
        return Catalog(organiseChannels(channels), movies, shows)
    }

    private fun info(o: JSONObject, episodes: List<Episode>): Details {
        val backdrop = o.optJSONArray("backdrop_path")?.optString(0) ?: o.optString("backdrop_path").ifBlank { o.optString("cover_big") }
        val date = o.optString("releasedate").ifBlank { o.optString("releaseDate").ifBlank { o.optString("release_date") } }
        return Details(o.optString("plot").ifBlank { o.optString("description") }, backdrop, date.take(4), o.optString("duration"), o.optString("genre"), episodes)
    }

    fun movie(id: Long): Details = info(JSONObject(api("get_vod_info", "&vod_id=$id")).optJSONObject("info") ?: JSONObject(), emptyList())

    fun show(id: Long): Details {
        val root = JSONObject(api("get_series_info", "&series_id=$id"))
        val episodes = mutableListOf<Episode>()
        root.optJSONObject("episodes")?.let { seasons ->
            seasons.keys().forEach { season ->
                val list = seasons.optJSONArray(season) ?: return@forEach
                for (i in 0 until list.length()) {
                    val e = list.getJSONObject(i)
                    val eid = e.optLong("id")
                    episodes += Episode(eid, season.toIntOrNull() ?: 1, e.optInt("episode_num", i + 1), e.optString("title"),
                        path("series", eid, e.optString("container_extension").ifBlank { "mp4" }))
                }
            }
        }
        return info(root.optJSONObject("info") ?: JSONObject(), episodes.sortedWith(compareBy({ it.season }, { it.num })))
    }
}

/** Generic M3U, with the same rules as the web server's server/m3u.js. */
private object M3uParser {
    private val VOD = Regex("\\.(mp4|mkv|avi|mov|m4v|wmv|mpg|mpeg|webm)$", RegexOption.IGNORE_CASE)
    private val EPISODE = Regex("^(.*?)[\\s._-]*S(\\d{1,2})\\s*E(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([\\w-]+)=\"([^\"]*)\"")

    class Parsed(val catalog: Catalog, val episodes: Map<Long, List<Episode>>)

    fun parse(text: String): Parsed {
        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<Movie>()
        val shows = mutableListOf<Show>()
        val episodes = mutableMapOf<Long, MutableList<Episode>>()
        val byName = mutableMapOf<String, Show>()
        var attrs = emptyMap<String, String>()
        var name = ""
        var group = ""
        var episodeId = 0L
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", true)) {
                attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
                val bare = line.replace(ATTR, "")
                name = bare.substringAfter(',', "").trim().ifEmpty { attrs["tvg-name"].orEmpty() }
                continue
            }
            if (line.startsWith("#EXTGRP:", true)) { group = line.substring(8).trim(); continue }
            if (line.startsWith("#") || !(line.startsWith("http://", true) || line.startsWith("https://", true))) continue
            val title = name.ifBlank { attrs["tvg-name"] ?: line }
            val category = attrs["group-title"] ?: group
            val logo = attrs["tvg-logo"].orEmpty()
            val path = Uri.parse(line).path.orEmpty()
            val ep = EPISODE.find(title)
            val vodFile = VOD.containsMatchIn(path)
            when {
                path.contains("/series/", true) || (vodFile && ep != null) -> {
                    val showName = (ep?.groupValues?.get(1) ?: category.ifBlank { title }).trimEnd(' ', '.', '_', '-').ifBlank { title }
                    val show = byName.getOrPut(showName.lowercase()) {
                        Show(shows.size + 1L, showName, logo, category).also { shows += it }
                    }
                    val rest = ep?.let { title.substring(it.range.last + 1).trimStart(' ', ':', '-', '–') }.orEmpty()
                    episodes.getOrPut(show.id) { mutableListOf() } +=
                        Episode(++episodeId, ep?.groupValues?.get(2)?.toInt() ?: 1, ep?.groupValues?.get(3)?.toInt() ?: (episodes[show.id]?.size ?: 0) + 1, rest.ifBlank { title }, line)
                }
                path.contains("/movie/", true) || vodFile -> movies += Movie(movies.size + 1L, title, logo, category, line, 0.0)
                else -> channels += Channel(channels.size + 1L, title, logo, category, line)
            }
            attrs = emptyMap(); name = ""; group = ""
        }
        return Parsed(Catalog(organiseChannels(channels), movies, shows), episodes.mapValues { (_, l) -> l.sortedWith(compareBy({ it.season }, { it.num })) })
    }
}

/**
 * The catalogue of the configured source, kept in memory and as a JSON file so the launcher shows the last catalogue
 * at once and refreshes in the background.
 */
class IptvRepository(private val context: Context) {
    private companion object { const val SAVED_FILE = "@playlist-file" }
    private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "catalog.json")
    private var m3uEpisodes: Map<Long, List<Episode>> = emptyMap()

    /** This edition never has an account built in: every user enters their own source. */
    val builtIn: Boolean get() = false

    private val playlistFile get() = File(context.filesDir, "playlist.m3u")

    /** The source the public edition saved, or null before setup. A whole playlist is kept as a file. */
    fun saved(): IptvSource? {
        val raw = prefs.getString("source", null) ?: return null
        return runCatching {
            if (raw == SAVED_FILE) IptvSource.parse(playlistFile.readText(), playlistFile.path) else IptvSource.parse(raw)
        }.getOrNull()
    }

    /** Saves a link, or a playlist file's text, entered by the user. Throws with a readable message when invalid. */
    fun save(input: String): IptvSource {
        val isPlaylist = input.contains("#EXTINF", true) && input.lines().none { it.trim().startsWith("URL=", true) }
        val source = IptvSource.parse(input, if (isPlaylist) playlistFile.path else null)
        if (isPlaylist) {
            playlistFile.writeText(input)
            prefs.edit().putString("source", SAVED_FILE).apply()
        } else {
            playlistFile.delete()
            prefs.edit().putString("source", input.trim()).apply()
        }
        cacheFile.delete()
        return source
    }

    fun forget() {
        prefs.edit().remove("source").apply()
        playlistFile.delete()
        cacheFile.delete()
    }

    private suspend fun source(): IptvSource? = withContext(Dispatchers.IO) { saved() }

    fun cached(): Catalog? = runCatching { if (cacheFile.exists()) decode(JSONObject(cacheFile.readText())) else null }.getOrNull()

    suspend fun refresh(): Catalog = withContext(Dispatchers.IO) {
        val catalog = when (val s = source() ?: return@withContext Catalog.EMPTY) {
            is IptvSource.Xtream -> XtreamClient(s).catalog()
            is IptvSource.M3u -> {
                val text = if (s.file != null) File(s.file).readText() else httpGet(s.url!!, 120_000)
                M3uParser.parse(text).also { m3uEpisodes = it.episodes }.catalog
            }
        }
        runCatching { cacheFile.writeText(encode(catalog).toString()) }
        catalog
    }

    suspend fun details(movie: Movie): Details = withContext(Dispatchers.IO) {
        when (val s = source()) {
            is IptvSource.Xtream -> runCatching { XtreamClient(s).movie(movie.id) }.getOrNull()
            else -> null
        } ?: Details("", "", "", "", movie.category, emptyList())
    }

    suspend fun details(show: Show): Details = withContext(Dispatchers.IO) {
        when (val s = source()) {
            is IptvSource.Xtream -> XtreamClient(s).show(show.id)
            is IptvSource.M3u -> {
                if (m3uEpisodes.isEmpty()) refresh()
                Details("", "", "", "", show.category, m3uEpisodes[show.id].orEmpty())
            }
            null -> Details("", "", "", "", "", emptyList())
        }
    }

    private fun encode(c: Catalog) = JSONObject()
        .put("channels", JSONArray(c.channels.map { JSONObject().put("i", it.id).put("n", it.name).put("l", it.logo).put("g", it.group).put("u", it.url) }))
        .put("movies", JSONArray(c.movies.map { JSONObject().put("i", it.id).put("n", it.name).put("p", it.poster).put("c", it.category).put("u", it.url).put("r", it.rating) }))
        .put("shows", JSONArray(c.shows.map { JSONObject().put("i", it.id).put("n", it.name).put("p", it.poster).put("c", it.category) }))

    private fun decode(o: JSONObject): Catalog {
        fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
        return Catalog(
            o.getJSONArray("channels").objs().map { Channel(it.getLong("i"), it.getString("n"), it.optString("l"), it.optString("g"), it.getString("u")) },
            o.getJSONArray("movies").objs().map { Movie(it.getLong("i"), it.getString("n"), it.optString("p"), it.optString("c"), it.getString("u"), it.optDouble("r", 0.0)) },
            o.getJSONArray("shows").objs().map { Show(it.getLong("i"), it.getString("n"), it.optString("p"), it.optString("c")) },
        )
    }
}
