package dev.lumina.atlas.ui

import android.view.KeyEvent as NativeKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlinx.coroutines.delay
import dev.lumina.atlas.R
import dev.lumina.atlas.data.AppEntry
import dev.lumina.atlas.data.MAX_FAVORITES
import dev.lumina.atlas.data.NowEntry

interface LauncherActions {
    fun open(pkg: String)
    fun appInfo(pkg: String)
    fun search()
    fun settings()
    fun homeSetup()
    fun watchSetup()
    fun forget(pkg: String)
}

/*
 * The canvas is drawn on a 1920×1080 grid. The Streamer renders at 1920×1080 px but with density 2 (960 dp wide),
 * so the whole screen runs under a density where 1 dp = 1 canvas px: every size below is the canvas's own number.
 */
@Composable
fun LauncherScreen(state: LauncherState, actions: LauncherActions, iptv: IptvActions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Noir)) {
        val canvas = Density(density = constraints.maxWidth / 1920f, fontScale = 1f)
        CompositionLocalProvider(LocalDensity provides canvas) {
            Box(Modifier.size(1920.dp, 1080.dp).background(Noir)) {
                Screen(state, actions, iptv)
            }
        }
    }
}

@Composable
private fun Screen(state: LauncherState, actions: LauncherActions, iptv: IptvActions) {
    // Clock: tick on the minute.
    LaunchedEffect(Unit) {
        while (true) {
            state.now = System.currentTimeMillis()
            delay(60_000 - state.now % 60_000 + 50)
        }
    }
    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(2600)
            state.toast = null
        }
    }
    // A launcher never leaves: Back closes the sheet, then returns to Início, then does nothing.
    BackHandler {
        when {
            state.menuFor != null -> state.menuFor = null
            state.detail != null -> state.detail = null
            state.section != Section.INICIO -> state.section = Section.INICIO
        }
    }
    // Public edition, first run: ask for the IPTV source once.
    LaunchedEffect(state.builtIn, state.sourceLabel, state.apps.isNotEmpty()) {
        if (!state.builtIn && state.sourceLabel == null && state.apps.isNotEmpty() && !state.askedSource) {
            state.askedSource = true
            state.section = Section.FONTE
        }
    }

    when (state.section) {
        Section.INICIO -> Home(state, actions, iptv)
        Section.DIRETO -> LivePage(state, iptv)
        Section.FILMES -> PosterPage(state, iptv, series = false)
        Section.SERIES -> PosterPage(state, iptv, series = true)
        Section.APPS -> AllApps(state, actions)
        Section.FONTE -> SourcePage(state, iptv)
    }
    Rail(state, actions)
    TopBar(state)
    state.detail?.let { DetailOverlay(it, state, iptv) }
    Grain()
    state.menuFor?.let { Options(it, state, actions) }
    state.toast?.let { Toast(it) }
}

// --- Small building blocks --------------------------------------------------------------------------

@Composable
internal fun T(
    text: String,
    size: TextUnit,
    color: Color = Paper,
    font: FontFamily = Mono,
    weight: FontWeight = FontWeight.Normal,
    spacing: TextUnit = 0.em,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    ellipsis: Boolean = false,
) = BasicText(
    text,
    modifier,
    style = TextStyle(
        color = color, fontSize = size, fontFamily = font, fontWeight = weight,
        letterSpacing = spacing, lineHeight = lineHeight,
    ),
    maxLines = maxLines,
    overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
)

/** A 24×24 line icon from SVG path data, drawn like the canvas's stroke icons. */
@Composable
internal fun LineIcon(paths: List<String>, color: Color, size: Int, stroke: Float = 1.6f) {
    val parsed = remember(paths) { paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(Modifier.size(size.dp)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            parsed.forEach { drawPath(it, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

internal object Icons {
    val home = listOf("M3 11.5 12 4l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z")
    val apps = listOf("M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z")
    val search = listOf("M11 18a7 7 0 1 1 0-14 7 7 0 0 1 0 14zM21 21l-5-5")
    val gear = listOf(
        "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
        "M12 3.5v2.2M12 18.3v2.2M3.5 12h2.2M18.3 12h2.2M6 6l1.6 1.6M16.4 16.4 18 18M6 18l1.6-1.6M16.4 7.6 18 6",
    )
    val live = listOf("M3 7h18v12H3zM8 3l4 4 4-4")
    val movie = listOf("M4 4h16v16H4zM8 4v16M16 4v16M4 9h4M4 15h4M16 9h4M16 15h4")
    val series = listOf("M3.5 8h13v12h-13zM7.5 5h13v11")
    val link = listOf("M10 14a4 4 0 0 0 5.7 0l3.6-3.6a4 4 0 0 0-5.7-5.7L12 6.3M14 10a4 4 0 0 0-5.7 0l-3.6 3.6a4 4 0 0 0 5.7 5.7l1.6-1.6")
    val nowList = listOf("M4 6h11M4 11h11M4 16h7", "M16 14v6l5-3z")
    val remote = listOf("M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M12 7v.01M10 12h4M10 15.5h4")
    val plus = listOf("M12 5v14M5 12h14")
    val minus = listOf("M5 12h14")
}

/** The kicker of every block: "02 —— AS TUAS APLICAÇÕES". */
@Composable
internal fun Kicker(index: String, label: String, size: Int = 13, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        T(index, size.sp, Accent, weight = FontWeight.Bold, spacing = 0.3.em)
        Box(Modifier.padding(horizontal = 18.dp).width(48.dp).height(1.dp).background(Rule))
        T(label, size.sp, Soft, spacing = 0.3.em)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            T(trailing, size.sp, Dim, spacing = 0.3.em)
        }
    }
}

/**
 * Click with the remote's OK, plus the options sheet on Menu or on a long OK press (the release that ends a long
 * press is swallowed so it does not also open the app).
 */
internal fun Modifier.remote(onClick: () -> Unit, onMenu: (() -> Unit)? = null): Modifier = composed {
    var longPress by remember { mutableStateOf(false) }
    onPreviewKeyEvent { e ->
        val code = e.nativeKeyEvent.keyCode
        val ok = code == NativeKey.KEYCODE_DPAD_CENTER || code == NativeKey.KEYCODE_ENTER ||
            code == NativeKey.KEYCODE_NUMPAD_ENTER
        when {
            onMenu != null && code == NativeKey.KEYCODE_MENU && e.type == KeyEventType.KeyUp -> { onMenu(); true }
            onMenu != null && ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                if (!longPress) { longPress = true; onMenu() }
                true
            }
            ok && e.type == KeyEventType.KeyUp && longPress -> { longPress = false; true }
            else -> false
        }
    }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

private val Monochrome = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
internal val MonochromeFilter get() = Monochrome

internal fun two(n: Int) = n.toString().padStart(2, '0')

// --- Rail and top bar -----------------------------------------------------------------------------------

@Composable
private fun Rail(state: LauncherState, actions: LauncherActions) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(112.dp).fillMaxHeight()
            .background(Noir)
            .drawBehind { drawRect(Edge, Offset(size.width - 1, 0f), Size(1f, size.height)) }
            .padding(top = 48.dp),
    ) {
        // The canvas's A mark.
        Canvas(Modifier.size(44.dp, 40.dp)) {
            val a = Path().apply { moveTo(22f, 2f); lineTo(42f, 38f); lineTo(31f, 38f); lineTo(22f, 21f); lineTo(13f, 38f); lineTo(2f, 38f); close() }
            drawPath(a, Accent)
            drawRect(Noir, Offset(17f, 30f), Size(10f, 3f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 72.dp)) {
            RailItem(tr("Início", "Home"), Icons.home, state.section == Section.INICIO) { state.section = Section.INICIO; state.homeTick++ }
            RailItem(tr("Direto", "Live"), Icons.live, state.section == Section.DIRETO) { state.section = Section.DIRETO }
            RailItem(tr("Filmes", "Movies"), Icons.movie, state.section == Section.FILMES) { state.section = Section.FILMES }
            RailItem(tr("Séries", "Series"), Icons.series, state.section == Section.SERIES) { state.section = Section.SERIES }
            RailItem(tr("Aplicações", "Apps"), Icons.apps, state.section == Section.APPS) { state.section = Section.APPS }
            RailItem(tr("Pesquisar", "Search"), Icons.search, false, actions::search)
            if (!state.builtIn) RailItem(tr("Fonte IPTV", "IPTV source"), Icons.link, state.section == Section.FONTE) { state.section = Section.FONTE }
            RailItem(tr("Definições", "Settings"), Icons.gear, false, actions::settings)
            RailItem(tr("A ver agora (acesso às sessões de media)", "Now playing (media session access)"), Icons.nowList, false, actions::watchSetup)
            RailItem(tr("Botão Home", "Home button"), Icons.remote, false, actions::homeSetup)
        }
    }
}

@Composable
private fun RailItem(label: String, icon: List<String>, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = when {
        focused -> if (selected) Accent else Paper
        selected -> Accent
        else -> Dim
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(112.dp, 54.dp)
            .semantics { contentDescription = label }
            .onFocusChanged { focused = it.isFocused }
            .remote(onClick)
            .background(if (focused) Raised else Color.Transparent)
            .drawBehind { if (selected || focused) drawRect(if (selected) Accent else Paper, Offset.Zero, Size(4f, size.height)) },
    ) { LineIcon(icon, color, 26) }
}

private val DAYS get() = if (I18n.portuguese) listOf("DOM", "SEG", "TER", "QUA", "QUI", "SEX", "SÁB") else listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
private val MONTHS get() = if (I18n.portuguese) listOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO", "SET", "OUT", "NOV", "DEZ")
    else listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

private fun clock(ms: Long): Pair<String, String> {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "${DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}" to
        "${two(c.get(Calendar.HOUR_OF_DAY))}:${two(c.get(Calendar.MINUTE))}"
}

@Composable
private fun TopBar(state: LauncherState) {
    val (date, time) = clock(state.now)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(72.dp),
        modifier = Modifier.offset(x = 112.dp).width(1808.dp).height(112.dp).padding(start = 80.dp, end = 64.dp),
    ) {
        T("ATLAS", 20.sp, weight = FontWeight.Bold, spacing = 0.9.em)
        Row(horizontalArrangement = Arrangement.spacedBy(44.dp)) {
            Tab(tr("INÍCIO", "HOME"), state.section == Section.INICIO)
            Tab(tr("DIRETO", "LIVE"), state.section == Section.DIRETO)
            Tab(tr("FILMES", "MOVIES"), state.section == Section.FILMES)
            Tab(tr("SÉRIES", "SERIES"), state.section == Section.SERIES)
            Tab(tr("APLICAÇÕES", "APPS"), state.section == Section.APPS)
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            T(date, 14.sp, Soft, spacing = 0.2.em)
            T(time, 14.sp, Paper, weight = FontWeight.Bold, spacing = 0.2.em)
        }
    }
}

@Composable
private fun Tab(label: String, on: Boolean) {
    Column {
        T(label, 14.sp, if (on) Paper else Dim, spacing = 0.24.em)
        Box(Modifier.padding(top = 8.dp).height(2.dp).width(if (on) (label.length * 12).dp else 0.dp).background(Accent))
    }
}

/** The canvas's faint scan lines. */
@Composable
private fun Grain() {
    Canvas(Modifier.fillMaxSize()) {
        var y = 0f
        while (y < size.height) {
            drawRect(Color.White.copy(alpha = 0.022f), Offset(0f, y), Size(size.width, 1f))
            y += 3f
        }
    }
}

// --- Início -----------------------------------------------------------------------------------------------

private val TAGLINES get() = if (I18n.portuguese) mapOf(
    "SÉRIES" to "Uma temporada de cada vez.",
    "FILMES" to "A sala escura, em casa.",
    "FAMÍLIA" to "Para ver todos juntos.",
    "HI-RES" to "Música como foi gravada.",
    "DIRETO" to "O que está a passar agora.",
    "DESPORTO" to "Ao vivo, sem intervalo.",
    "VÍDEO" to "Tudo o que há para ver.",
    "MÚSICA" to "Para ouvir alto.",
    "JOGOS" to "Comando na mão.",
    "BIBLIOTECA" to "A tua coleção, em todo o lado.",
) else mapOf(
    "SÉRIES" to "One season at a time.",
    "FILMES" to "The dark room, at home.",
    "FAMÍLIA" to "For watching together.",
    "HI-RES" to "Music as it was recorded.",
    "DIRETO" to "What's on right now.",
    "DESPORTO" to "Live, no half-time.",
    "VÍDEO" to "Everything there is to watch.",
    "MÚSICA" to "Made to be played loud.",
    "JOGOS" to "Controller in hand.",
    "BIBLIOTECA" to "Your collection, everywhere.",
)

internal fun whenOpened(ms: Long?, now: Long): String {
    if (ms == null) return tr("NUNCA", "NEVER")
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val days = (today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR)) - (c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR))
    val hm = "${two(c.get(Calendar.HOUR_OF_DAY))}:${two(c.get(Calendar.MINUTE))}"
    return when (days) {
        0 -> tr("HOJE", "TODAY") + " $hm"
        1 -> tr("ONTEM", "YESTERDAY") + " $hm"
        else -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
    }
}

@Composable
private fun Home(state: LauncherState, actions: LauncherActions, iptv: IptvActions) {
    val firstTile = remember { FocusRequester() }
    val primary = remember { FocusRequester() }
    val ready = state.apps.isNotEmpty()
    LaunchedEffect(ready, state.homeTick) {
        if (!ready) return@LaunchedEffect
        runCatching { if (state.row.isNotEmpty()) firstTile.requestFocus() else primary.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        // Hero photo, black and white, faded into the ground on the left and at the bottom. It follows the focused
        // tile: the artwork the app sent with what it plays, else the app's banner, else a plain panel.
        val heroApp = state.hero
        val cover = heroApp?.let { a -> state.nowArt[a.pkg]?.let { it to (state.now(a.pkg)?.kind == "live") } ?: a.art?.takeIf { a.artIsBanner }?.let { it to true } }
        Box(Modifier.align(Alignment.TopEnd).size(1240.dp, 760.dp)) {
            Crossfade(targetState = cover, animationSpec = tween(450), label = "cover") { art ->
                if (art == null) Box(Modifier.fillMaxSize().background(Card)) else Image(
                    art.first, null,
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(0.2f, -0.3f),
                    colorFilter = Monochrome,
                    // Banners are 320×180 at best: soften them rather than show the pixels.
                    modifier = Modifier.fillMaxSize().then(if (art.second) Modifier.blur(10.dp) else Modifier),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Noir, 0.22f to Noir.copy(alpha = 0.86f), 0.55f to Noir.copy(alpha = 0.15f), 1f to Color.Transparent,
                    )
                )
            )
        }
        Box(
            Modifier.offset(y = 440.dp).fillMaxWidth().height(320.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.92f to Noir))
        )

        val hero = state.hero
        Pager(state, hero)
        if (hero != null) HeroCopy(state, hero, actions, primary)

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.offset(x = 192.dp, y = 772.dp).width(1664.dp),
        ) {
            val row = state.row
            val at = row.indexOfFirst { it.app.pkg == hero?.pkg }
            Kicker(
                "02", tr("A VER AGORA", "NOW PLAYING"),
                trailing = if (state.canWatch) "${two(if (at >= 0) at + 1 else 0)} / ${two(row.size)}" else tr("PARA ATIVAR: RAIL › A VER AGORA", "TO TURN ON: RAIL › NOW PLAYING"),
            )
            Row(Modifier.fillMaxWidth().border(1.dp, Line)) {
                for (i in 0 until MAX_FAVORITES) {
                    val item = row.getOrNull(i)
                    val mod = Modifier.weight(1f).height(88.dp)
                    if (item == null) EmptySlot(i, mod) else FavoriteTile(
                        item, i, state, actions,
                        if (i == 0) mod.focusRequester(firstTile) else mod,
                    )
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.offset(y = 916.dp).fillMaxWidth(),
        ) {
            val channels = remember(state.catalog) { state.catalog.channels.filter { it.group.ifBlank { noChannelGroup() } == state.catalog.groups.firstOrNull() }.take(40) }
            if (channels.isNotEmpty()) {
                Box(Modifier.padding(start = 192.dp, end = 64.dp)) {
                    Kicker("03", tr("EM DIRETO · ", "LIVE · ") + state.catalog.groups.first().uppercase(), trailing = tr("VER TUDO NO RAIL › DIRETO", "SEE ALL: RAIL › LIVE"))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 192.dp, end = 64.dp)) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { _, ch ->
                        LiveStrip(ch, Modifier.width(320.dp).height(120.dp)) { iptv.playChannel(ch, channels) }
                    }
                }
                return@Column
            }
            Box(Modifier.padding(start = 192.dp, end = 64.dp)) {
                Kicker("03", tr("TODAS AS APLICAÇÕES", "ALL APPS"), trailing = two(state.apps.size))
            }
            // Recently opened first, then A–Z.
            val order = remember(state.apps, state.recents) {
                val rank = state.recents.mapIndexed { i, r -> r.first to i }.toMap()
                state.apps.sortedWith(compareBy({ rank[it.pkg] ?: Int.MAX_VALUE }, { it.name }))
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 192.dp, end = 64.dp),
            ) {
                itemsIndexed(order, key = { _, a -> a.pkg }) { _, app ->
                    WideTile(app, state, actions, Modifier.width(404.dp).height(120.dp))
                }
            }
        }
    }
}

@Composable
private fun Pager(state: LauncherState, hero: AppEntry?) {
    val favs = state.row.map { it.app.pkg }
    val at = favs.indexOf(hero?.pkg)
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.offset(x = 1920.dp - 64.dp - 200.dp, y = 236.dp).width(200.dp),
    ) {
        T(if (at >= 0) two(at + 1) else "—", 64.sp, font = Anton, lineHeight = 64.sp)
        T("/ ${two(favs.size)}", 13.sp, Dim, spacing = 0.3.em)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 18.dp),
        ) {
            favs.indices.forEach { i ->
                if (i == at) Box(Modifier.size(40.dp, 3.dp).background(Accent))
                else Box(Modifier.size(20.dp, 1.dp).background(Rule))
            }
        }
    }
}

@Composable
private fun HeroCopy(state: LauncherState, hero: AppEntry, actions: LauncherActions, primary: FocusRequester) {
    val now = state.now(hero.pkg)
    if (now != null) return NowCopy(state, hero, now, actions, primary)
    val latest = state.recents.firstOrNull()?.first == hero.pkg
    val pinned = state.isFavorite(hero.pkg)
    val favs = state.favorites
    val at = favs.indexOf(hero.pkg)
    val titleSize = when {
        hero.name.length <= 12 -> 140
        hero.name.length <= 20 -> 112
        else -> 84
    }
    Column(Modifier.offset(x = 192.dp, y = 140.dp).width(900.dp)) {
        Kicker("01", if (latest) tr("CONTINUAR", "CONTINUE") else tr("SELECIONADA", "SELECTED"), size = 14)
        T(
            hero.name, titleSize.sp, Accent, Anton,
            lineHeight = (titleSize * 0.88f).sp, maxLines = 2,
            modifier = Modifier.padding(top = 26.dp),
        )
        T(
            TAGLINES[hero.kind] ?: hero.label, 34.sp, Color(0xFFE6E6E0), Serif,
            modifier = Modifier.padding(top = 16.dp),
        )

        // Facts strip.
        Row(
            Modifier.padding(top = 22.dp).width(760.dp)
                .drawBehind {
                    drawRect(Line, Offset.Zero, Size(size.width, 1f))
                    drawRect(Line, Offset(0f, size.height - 1), Size(size.width, 1f))
                }
        ) {
            Fact(tr("CATEGORIA", "CATEGORY"), kindLabel(hero.kind), first = true)
            Fact(tr("ÚLTIMA VEZ", "LAST USED"), whenOpened(state.lastOpened(hero.pkg), state.now))
            Fact(tr("VERSÃO", "VERSION"), hero.version)
            Fact(tr("FILA", "ROW"), if (at >= 0) "${two(at + 1)} / ${two(favs.size)}" else "—")
        }

        // Position in the row, as the canvas's segmented progress bar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.padding(top = 20.dp).width(760.dp),
        ) {
            Row(Modifier.weight(1f).height(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val n = maxOf(favs.size, 1)
                for (i in 0 until n) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (i <= at) Accent else Line2))
                }
            }
            T(hero.label.uppercase().take(18), 14.sp, Paper, weight = FontWeight.Bold, spacing = 0.14.em)
            T("${state.apps.size} APPS", 14.sp, Dim, spacing = 0.14.em)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 26.dp)) {
            HeroButton(if (latest) tr("RETOMAR", "RESUME") else tr("ABRIR", "OPEN"), primary = true, modifier = Modifier.focusRequester(primary)) {
                actions.open(hero.pkg)
            }
            HeroButton(tr("INFORMAÇÕES", "INFO")) { actions.appInfo(hero.pkg) }
            SquareButton(if (pinned) Icons.minus else Icons.plus, if (pinned) tr("Tirar da fila", "Remove from row") else tr("Pôr na fila", "Add to row")) {
                if (!state.toggleFavorite(hero.pkg)) state.toast = tr("A FILA JÁ TEM $MAX_FAVORITES APPS", "THE ROW ALREADY HAS $MAX_FAVORITES APPS")
            }
        }
    }
}

private fun clockOf(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "${s / 3600}:${two(((s % 3600) / 60).toInt())}:${two((s % 60).toInt())}" else "${s / 60}:${two((s % 60).toInt())}"
}

/** The hero for an app with something playing or paused: the title it reported, its progress, and Retomar. */
@Composable
private fun NowCopy(state: LauncherState, app: AppEntry, now: NowEntry, actions: LauncherActions, primary: FocusRequester) {
    val pinned = state.isFavorite(app.pkg)
    val title = now.title.uppercase()
    val titleSize = when {
        title.length <= 14 -> 132
        title.length <= 24 -> 104
        else -> 80
    }
    val fraction = if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else null
    Column(Modifier.offset(x = 192.dp, y = 140.dp).width(900.dp)) {
        Kicker("01", (if (now.playing) tr("A REPRODUZIR", "PLAYING") else tr("CONTINUAR", "CONTINUE")) + " · " + app.name, size = 14)
        T(
            title, titleSize.sp, Accent, Anton,
            lineHeight = (titleSize * 0.88f).sp, maxLines = 2, ellipsis = true,
            modifier = Modifier.padding(top = 26.dp),
        )
        T(
            now.subtitle.ifEmpty { TAGLINES[app.kind] ?: app.label }, 34.sp, Color(0xFFE6E6E0), Serif, ellipsis = true,
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            Modifier.padding(top = 22.dp).width(760.dp)
                .drawBehind {
                    drawRect(Line, Offset.Zero, Size(size.width, 1f))
                    drawRect(Line, Offset(0f, size.height - 1), Size(size.width, 1f))
                }
        ) {
            Fact(tr("SERVIÇO", "SERVICE"), app.name, first = true)
            Fact(tr("ÚLTIMA VEZ", "LAST USED"), whenOpened(now.at, state.now))
            Fact(tr("ESTADO", "STATUS"), if (now.playing) tr("A REPRODUZIR", "PLAYING") else tr("EM PAUSA", "PAUSED"))
            Fact(tr("CATEGORIA", "CATEGORY"), kindLabel(app.kind))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.padding(top = 20.dp).width(760.dp),
        ) {
            Box(Modifier.weight(1f).height(4.dp).background(Line2)) {
                if (fraction != null) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Accent))
            }
            if (fraction != null) {
                T(clockOf(now.positionMs), 14.sp, Paper, weight = FontWeight.Bold, spacing = 0.14.em)
                T("−" + clockOf(now.durationMs - now.positionMs), 14.sp, Dim, spacing = 0.14.em)
            } else {
                T(tr("SEM DURAÇÃO", "NO DURATION"), 14.sp, Dim, spacing = 0.14.em)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 26.dp)) {
            HeroButton(tr("RETOMAR", "RESUME"), primary = true, modifier = Modifier.focusRequester(primary)) { actions.open(app.pkg) }
            HeroButton(tr("INFORMAÇÕES", "INFO")) { actions.appInfo(app.pkg) }
            SquareButton(if (pinned) Icons.minus else Icons.plus, if (pinned) tr("Tirar da fila", "Remove from row") else tr("Pôr na fila", "Add to row")) {
                if (!state.toggleFavorite(app.pkg)) state.toast = tr("A FILA JÁ TEM $MAX_FAVORITES APPS", "THE ROW ALREADY HAS $MAX_FAVORITES APPS")
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Fact(label: String, value: String, first: Boolean = false) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.weight(1f)
            .drawBehind { if (!first) drawRect(Line, Offset.Zero, Size(1f, size.height)) }
            .padding(start = if (first) 0.dp else 20.dp, top = 14.dp, bottom = 14.dp),
    ) {
        T(label, 11.sp, Dim, spacing = 0.3.em)
        T(value, 15.sp, Paper, weight = FontWeight.Bold, spacing = 0.12.em)
    }
}

@Composable
internal fun HeroButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .remote(onClick)
            // Focus: the canvas's double ring (ground gap, then accent).
            .border(2.dp, if (focused) Accent else Color.Transparent)
            .padding(4.dp)
            .height(68.dp)
            .background(if (primary) Accent else if (focused) Raised else Color.Transparent)
            .border(1.dp, if (primary) Accent else if (focused) Paper else Outline)
            .padding(start = if (primary) 32.dp else 30.dp, end = if (primary) 40.dp else 30.dp),
    ) {
        if (primary) Canvas(Modifier.size(18.dp, 20.dp)) {
            drawPath(Path().apply { moveTo(1f, 1f); lineTo(17f, 10f); lineTo(1f, 19f); close() }, Noir)
        }
        T(
            label, if (primary) 18.sp else 15.sp, if (primary) Noir else Paper,
            weight = if (primary) FontWeight.Bold else FontWeight.Medium, spacing = 0.24.em,
        )
    }
}

@Composable
private fun SquareButton(icon: List<String>, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .semantics { contentDescription = label }
            .onFocusChanged { focused = it.isFocused }
            .remote(onClick)
            .border(2.dp, if (focused) Accent else Color.Transparent)
            .padding(4.dp)
            .size(68.dp)
            .background(if (focused) Raised else Color.Transparent)
            .border(1.dp, if (focused) Paper else Outline),
    ) { LineIcon(icon, Paper, 22) }
}

@Composable
private fun FavoriteTile(item: RowItem, index: Int, state: LauncherState, actions: LauncherActions, modifier: Modifier) {
    val app = item.app
    val now = item.now
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) Noir else Paper
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) state.heroPkg = app.pkg
            }
            .remote(onClick = { actions.open(app.pkg) }, onMenu = { state.menuFor = app })
            .drawBehind { if (index > 0) drawRect(Line, Offset.Zero, Size(1f, size.height)) }
            .background(if (focused) Accent else Ink)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().alpha(0.72f)) {
            T(two(index + 1), 11.sp, fg, spacing = 0.24.em)
            Spacer(Modifier.weight(1f))
            T(if (now == null) kindLabel(app.kind) else (if (now.playing) "▶ " else "") + app.name, 11.sp, fg, spacing = 0.24.em, ellipsis = true)
        }
        T(now?.title?.uppercase() ?: app.name, if (now == null) 30.sp else 24.sp, fg, Anton, spacing = 0.02.em, lineHeight = 30.sp, ellipsis = true)
    }
}

@Composable
private fun EmptySlot(index: Int, modifier: Modifier) {
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .drawBehind { if (index > 0) drawRect(Line, Offset.Zero, Size(1f, size.height)) }
            .background(Noir)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        T(two(index + 1), 11.sp, Rule, spacing = 0.24.em)
        T(tr("MENU › FIXAR", "MENU › PIN"), 13.sp, Rule, spacing = 0.2.em)
    }
}

/** A dark card with the app's banner on the right: black and white at rest, in colour with focus. */
@Composable
private fun WideTile(app: AppEntry, state: LauncherState, actions: LauncherActions, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) state.heroPkg = app.pkg
            }
            .remote(onClick = { actions.open(app.pkg) }, onMenu = { state.menuFor = app })
            .background(Card)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Edge)
    ) {
        Art(app.art, app.artIsBanner, focused, Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(16f / 9f))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Card, 0.42f to Card, 0.72f to Card.copy(alpha = if (focused) 0.1f else 0.55f), 1f to Color.Transparent)
            )
        )
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            T(kindLabel(app.kind) + if (state.isFavorite(app.pkg)) tr(" · FILA", " · ROW") else "", 11.sp, Dim, spacing = 0.28.em)
            T(app.name, 40.sp, if (focused) Accent else Paper, Anton, lineHeight = 36.sp)
        }
    }
}

/** A live channel in the home row: its logo on a light square, the name in Anton. */
@Composable
private fun LiveStrip(ch: dev.lumina.atlas.data.Channel, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .remote(onClick)
            .background(Card)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Edge)
            .padding(12.dp),
    ) {
        Box(Modifier.fillMaxHeight().aspectRatio(1f).background(Color(0xFFECECE6)), contentAlignment = Alignment.Center) {
            if (ch.logo.isNotBlank()) coil.compose.AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.7f))
            else T(ch.name.take(3).uppercase(), 26.sp, Rule, Anton)
        }
        Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            T(tr("● DIRETO", "● LIVE"), 11.sp, Color(0xFFFF5A4F), weight = FontWeight.Bold, spacing = 0.2.em)
            T(ch.name.uppercase(), 26.sp, if (focused) Accent else Paper, Anton, lineHeight = 26.sp, maxLines = 2, ellipsis = true)
        }
    }
}

@Composable
private fun Art(art: ImageBitmap?, banner: Boolean, focused: Boolean, modifier: Modifier) {
    if (art == null) return
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            art, null,
            contentScale = if (banner) ContentScale.Crop else ContentScale.Fit,
            colorFilter = if (focused) null else Monochrome,
            modifier = (if (banner) Modifier.fillMaxSize() else Modifier.fillMaxHeight(0.6f).aspectRatio(1f))
                .alpha(if (focused) 1f else 0.5f),
        )
    }
}

// --- Aplicações -------------------------------------------------------------------------------------------

@Composable
private fun AllApps(state: LauncherState, actions: LauncherActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(state.apps.isNotEmpty()) { runCatching { first.requestFocus() } }
    Column(Modifier.offset(x = 192.dp, y = 140.dp).width(1664.dp).height(940.dp)) {
        Kicker("01", tr("TODAS AS APLICAÇÕES", "ALL APPS"), size = 14, trailing = two(state.apps.size))
        T(tr("APLICAÇÕES", "APPS"), 112.sp, Accent, Anton, lineHeight = 100.sp, modifier = Modifier.padding(top = 22.dp, bottom = 28.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 64.dp),
        ) {
            itemsIndexed(state.apps, key = { _, a -> a.pkg }) { i, app ->
                GridTile(app, i, state, actions, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

@Composable
private fun GridTile(app: AppEntry, index: Int, state: LauncherState, actions: LauncherActions, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .remote(onClick = { actions.open(app.pkg) }, onMenu = { state.menuFor = app })
            .background(if (focused) Accent else Card)
            .border(1.dp, if (focused) Accent else Edge)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink)) {
            Art(app.art, app.artIsBanner, focused, Modifier.fillMaxSize())
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth().alpha(0.72f)) {
                T(two(index + 1), 11.sp, if (focused) Noir else Paper, spacing = 0.24.em)
                Spacer(Modifier.weight(1f))
                T(kindLabel(app.kind) + if (state.isFavorite(app.pkg)) tr(" · FILA", " · ROW") else "", 11.sp, if (focused) Noir else Paper, spacing = 0.24.em)
            }
            T(app.name, 30.sp, if (focused) Noir else Paper, Anton, lineHeight = 30.sp)
        }
    }
}

// --- Options sheet and toast ------------------------------------------------------------------------------

@Composable
private fun Options(app: AppEntry, state: LauncherState, actions: LauncherActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(app.pkg) { runCatching { first.requestFocus() } }
    val pinned = state.isFavorite(app.pkg)
    val at = state.favorites.indexOf(app.pkg)
    Box(Modifier.fillMaxSize().background(Noir.copy(alpha = 0.92f))) {
        Column(Modifier.offset(x = 192.dp, y = 200.dp).width(900.dp)) {
            Kicker("··", tr("OPÇÕES", "OPTIONS"), size = 14)
            T(app.name, 112.sp, Accent, Anton, lineHeight = 100.sp, maxLines = 2, modifier = Modifier.padding(top = 22.dp))
            T(app.label, 30.sp, Color(0xFFE6E6E0), Serif, modifier = Modifier.padding(top = 10.dp, bottom = 34.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Option(tr("ABRIR", "OPEN"), Modifier.focusRequester(first)) { state.menuFor = null; actions.open(app.pkg) }
                Option(if (pinned) tr("TIRAR DA FILA", "REMOVE FROM ROW") else tr("PÔR NA FILA", "ADD TO ROW")) {
                    if (!state.toggleFavorite(app.pkg)) state.toast = tr("A FILA JÁ TEM $MAX_FAVORITES APPS", "THE ROW ALREADY HAS $MAX_FAVORITES APPS")
                    state.menuFor = null
                }
                if (pinned && at > 0) Option(tr("MOVER PARA A ESQUERDA", "MOVE LEFT")) { state.move(app.pkg, -1) }
                if (pinned && at < state.favorites.size - 1) Option(tr("MOVER PARA A DIREITA", "MOVE RIGHT")) { state.move(app.pkg, 1) }
                if (state.now(app.pkg) != null) Option(tr("TIRAR DE «A VER AGORA»", "REMOVE FROM NOW PLAYING")) { state.menuFor = null; actions.forget(app.pkg) }
                Option(tr("INFORMAÇÕES DA APP", "APP INFO")) { state.menuFor = null; actions.appInfo(app.pkg) }
                Option(tr("FECHAR", "CLOSE")) { state.menuFor = null }
            }
        }
    }
}

@Composable
private fun Option(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .width(620.dp).height(64.dp)
            .onFocusChanged { focused = it.isFocused }
            .remote(onClick)
            .background(if (focused) Accent else Color.Transparent)
            .border(1.dp, if (focused) Accent else Outline)
            .padding(horizontal = 28.dp),
    ) { T(label, 15.sp, if (focused) Noir else Paper, weight = FontWeight.Bold, spacing = 0.24.em) }
}

@Composable
private fun Toast(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.padding(bottom = 56.dp).clip(CircleShape).background(Accent).padding(horizontal = 30.dp, vertical = 16.dp)) {
            T(text, 14.sp, Noir, weight = FontWeight.Bold, spacing = 0.2.em)
        }
    }
}
