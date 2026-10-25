package dev.lumina.atlas.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.lumina.atlas.R

// Noir (designed on a 1920×1080 grid): near-black ground, warm off-white text, hairlines, one accent.
val Noir = Color(0xFF050505)
val Ink = Color(0xFF0A0A09)
val Card = Color(0xFF0E0E0D)
val Raised = Color(0xFF151513)
val Paper = Color(0xFFF2F2EE)
val Soft = Color(0xFFC8C8C2)
val Dim = Color(0xFF9A9A94)
val Rule = Color(0xFF6A6A66)
val Line = Color(0xFF2A2A27)
val Line2 = Color(0xFF3A3A36)
val Edge = Color(0xFF1E1E1C)
val Outline = Color(0xFF4A4A46)

/** The canvas's default accent (its other options: #FF5A1F, #E8E2D0, #7CF2C4). */
val Accent = Color(0xFFFFE600)

/** Anton: the big titles and app names. */
val Anton = FontFamily(Font(R.font.anton))

/** Instrument Serif italic: the one-line tagline under the hero title. */
val Serif = FontFamily(Font(R.font.instrument_serif_italic, style = FontStyle.Italic))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun mono(weight: Int) =
    Font(R.font.jetbrains_mono, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** JetBrains Mono: every label, letter-spaced caps. */
val Mono = FontFamily(mono(400), mono(500), mono(700))
