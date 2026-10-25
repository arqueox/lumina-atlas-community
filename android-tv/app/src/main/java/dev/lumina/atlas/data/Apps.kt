package dev.lumina.atlas.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** One TV app as the launcher shows it. */
data class AppEntry(
    val pkg: String,
    val activity: String,
    /** The app's own label, for the menu and app info. */
    val label: String,
    /** Upper-case display name for the Anton titles ("PRIME VIDEO", "DIGI TV"). */
    val name: String,
    /** Short category in the canvas's mono caps ("SÉRIES", "HI-RES"). */
    val kind: String,
    val version: String,
    /** The app's 16:9 TV banner, or its icon when it has none. */
    val art: ImageBitmap?,
    val artIsBanner: Boolean,
)

// The six apps of the canvas's "As tuas aplicações" row, in its order. Only the installed ones are used.
val DEFAULT_FAVORITES = listOf(
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus",
    "com.qobuz.music",
    "ro.digionline.tv",
    "com.dazn",
)

const val MAX_FAVORITES = 6

private val NAMES = mapOf(
    "com.amazon.amazonvideo.livingroom" to "PRIME VIDEO",
    "ro.digionline.tv" to "DIGI TV",
    "com.google.android.youtube.tv" to "YOUTUBE",
    "com.android.vending" to "PLAY STORE",
    "com.android.tv.settings" to "DEFINIÇÕES",
    "com.apple.atve.androidtv.appletv" to "APPLE TV",
)

private val KINDS = mapOf(
    "com.netflix.ninja" to "SÉRIES",
    "com.amazon.amazonvideo.livingroom" to "FILMES",
    "com.disney.disneyplus" to "FAMÍLIA",
    "com.qobuz.music" to "HI-RES",
    "ro.digionline.tv" to "DIRETO",
    "com.dazn" to "DESPORTO",
    "com.google.android.youtube.tv" to "VÍDEO",
    "com.plexapp.android" to "BIBLIOTECA",
    "com.apple.atve.androidtv.appletv" to "FILMES",
    "com.skyshowtime.skyshowtime.google" to "SÉRIES",
    "com.wbd.stream" to "SÉRIES",
    "com.google.android.videos" to "FILMES",
    "com.android.vending" to "LOJA",
    "com.google.android.play.games" to "JOGOS",
    "com.android.tv.settings" to "SISTEMA",
)

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    /** Every app with a TV launcher entry, A–Z, without this launcher itself. */
    fun load(): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map(::toEntry)
            .sortedBy { it.name }
    }

    private fun toEntry(info: ResolveInfo): AppEntry {
        val a = info.activityInfo
        val label = info.loadLabel(pm).toString().trim()
        val banner = runCatching { a.loadBanner(pm) ?: a.applicationInfo.loadBanner(pm) }.getOrNull()
        val art = banner ?: runCatching { a.loadIcon(pm) }.getOrNull()
        val bitmap = art?.let {
            runCatching {
                if (banner != null) it.toBitmap(640, 360) else it.toBitmap(256, 256)
            }.getOrNull()?.asImageBitmap()
        }
        val version = runCatching { pm.getPackageInfo(a.packageName, 0).versionName }.getOrNull().orEmpty()
        return AppEntry(
            pkg = a.packageName,
            activity = a.name,
            label = label,
            name = NAMES[a.packageName] ?: label.uppercase(),
            kind = KINDS[a.packageName] ?: kindOf(a.applicationInfo),
            version = version.substringBefore(' ').take(12).ifEmpty { "—" },
            art = bitmap,
            artIsBanner = banner != null,
        )
    }

    private fun kindOf(info: ApplicationInfo) = when (info.category) {
        ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> "VÍDEO"
        ApplicationInfo.CATEGORY_AUDIO -> "MÚSICA"
        ApplicationInfo.CATEGORY_GAME -> "JOGOS"
        ApplicationInfo.CATEGORY_NEWS -> "NOTÍCIAS"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "UTILIDADES"
        else -> "APP"
    }

    fun launchIntent(pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Pinned apps (the row of six) and the recently opened ones, in SharedPreferences. */
class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("atlas_noir", Context.MODE_PRIVATE)

    /** Null until the user changes the row for the first time, so the canvas's six are used. */
    fun favorites(): List<String>? =
        prefs.getString("favorites", null)?.split(',')?.filter { it.isNotBlank() }

    fun setFavorites(list: List<String>) = prefs.edit().putString("favorites", list.joinToString(",")).apply()

    fun recents(): List<Pair<String, Long>> =
        prefs.getString("recents", "").orEmpty().split(',').mapNotNull { item ->
            val pkg = item.substringBefore('@')
            val at = item.substringAfter('@', "").toLongOrNull()
            if (pkg.isBlank() || at == null) null else pkg to at
        }

    fun touch(pkg: String) {
        val list = listOf(pkg to System.currentTimeMillis()) + recents().filter { it.first != pkg }
        prefs.edit().putString("recents", list.take(12).joinToString(",") { "${it.first}@${it.second}" }).apply()
    }
}
