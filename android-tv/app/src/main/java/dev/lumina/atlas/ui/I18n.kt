package dev.lumina.atlas.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The language setting: follow the TV's language (Portuguese or, for any other language, English), or force one. */
enum class Lang { SYSTEM, EN, PT }

object I18n {
    private const val PREFS = "settings"
    var choice by mutableStateOf(Lang.SYSTEM)
        private set

    val portuguese: Boolean get() = when (choice) {
        Lang.PT -> true
        Lang.EN -> false
        Lang.SYSTEM -> Locale.getDefault().language == "pt"
    }

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lang", null)
        choice = Lang.entries.firstOrNull { it.name == saved } ?: Lang.SYSTEM
    }

    fun set(context: Context, lang: Lang) {
        choice = lang
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lang", lang.name).apply()
    }
}

/** Every visible text: Portuguese first, English second. Reading it inside a composable follows language changes. */
fun tr(pt: String, en: String): String = if (I18n.portuguese) pt else en

/** App categories are kept as Portuguese keys (they also pick the hero's tagline); shown in the current language. */
fun kindLabel(kind: String): String = if (I18n.portuguese) kind else when (kind) {
    "SÉRIES" -> "SERIES"
    "FILMES" -> "MOVIES"
    "FAMÍLIA" -> "FAMILY"
    "DIRETO" -> "LIVE"
    "DESPORTO" -> "SPORTS"
    "VÍDEO" -> "VIDEO"
    "MÚSICA" -> "MUSIC"
    "JOGOS" -> "GAMES"
    "NOTÍCIAS" -> "NEWS"
    "UTILIDADES" -> "UTILITIES"
    "BIBLIOTECA" -> "LIBRARY"
    "LOJA" -> "STORE"
    "SISTEMA" -> "SYSTEM"
    else -> kind
}

/** Fallback group names for channels and titles without one. */
fun noChannelGroup() = tr("Canais", "Channels")
fun noCategory() = tr("Outros", "Other")
