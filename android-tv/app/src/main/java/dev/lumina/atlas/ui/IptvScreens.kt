package dev.lumina.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.lumina.atlas.data.Channel
import dev.lumina.atlas.data.Episode
import dev.lumina.atlas.data.Movie
import dev.lumina.atlas.data.Show

/** Catalogue and setup actions, implemented by the activity. */
interface IptvActions {
    fun playChannel(channel: Channel, list: List<Channel>)
    fun openMovie(movie: Movie)
    fun openShow(show: Show)
    fun playMovie(movie: Movie)
    fun playEpisode(show: Show, episode: Episode)
    fun saveSource(text: String)
    fun pickSourceFile()
    fun refreshCatalog()
}

private val LogoGround = Color(0xFFECECE6)

// --- Shared page frame -------------------------------------------------------------------------------

@Composable
private fun Page(index: String, kicker: String, title: String, trailing: String?, content: @Composable () -> Unit) {
    Column(Modifier.offset(x = 192.dp, y = 140.dp).width(1664.dp).height(940.dp)) {
        Kicker(index, kicker, size = 14, trailing = trailing)
        T(title, 112.sp, Accent, Anton, lineHeight = 100.sp, modifier = Modifier.padding(top = 22.dp, bottom = 24.dp))
        content()
    }
}

/** What a catalogue page shows when there is nothing to list. */
@Composable
private fun Empty(state: LauncherState, actions: IptvActions, first: FocusRequester) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(top = 20.dp)) {
        val text = when {
            state.sourceLabel == null -> "Ainda não há fonte IPTV. Liga uma conta Xtream ou uma lista M3U."
            state.catalogLoading -> "A carregar o catálogo…"
            state.catalogError != null -> "Não foi possível carregar o catálogo: ${state.catalogError}"
            else -> "O fornecedor não tem nada nesta secção."
        }
        T(text, 34.sp, Soft, Serif, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.sourceLabel == null) HeroButton("LIGAR FONTE", primary = true, modifier = Modifier.focusRequester(first)) { state.section = Section.FONTE }
            else HeroButton("TENTAR DE NOVO", primary = true, modifier = Modifier.focusRequester(first)) { actions.refreshCatalog() }
        }
    }
}

/** Group pills over a grid: channel groups, film or series categories. */
@Composable
private fun Tabs(groups: List<Pair<String, Int>>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.padding(bottom = 22.dp).border(1.dp, Line),
    ) {
        items(groups, key = { it.first }) { (name, count) ->
            var focused by remember { mutableStateOf(false) }
            val on = name == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(52.dp)
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect(name) }
                    .remote({ onSelect(name) })
                    .background(if (on) Accent else if (focused) Raised else Ink)
                    .border(if (focused) 2.dp else 0.dp, if (focused) Paper else Color.Transparent)
                    .padding(horizontal = 22.dp),
            ) {
                T(name.uppercase(), 13.sp, if (on) Noir else Paper, weight = if (on) FontWeight.Bold else FontWeight.Normal, spacing = 0.22.em)
                T("  $count", 11.sp, if (on) Noir.copy(alpha = 0.6f) else Dim, spacing = 0.1.em)
            }
        }
    }
}

// --- Direto ---------------------------------------------------------------------------------------------

@Composable
fun LivePage(state: LauncherState, actions: IptvActions) {
    val first = remember { FocusRequester() }
    val groups = remember(state.catalog) { state.catalog.groups.map { g -> g to state.catalog.channels.count { it.group.ifBlank { "Canais" } == g } } }
    val selected = state.tabs[Section.DIRETO]?.takeIf { s -> groups.any { it.first == s } } ?: groups.firstOrNull()?.first
    val list = remember(state.catalog, selected) { state.catalog.channels.filter { it.group.ifBlank { "Canais" } == selected } }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Page("02", "Em direto", "DIRETO", "${two(state.catalog.channels.size)} CANAIS") {
        if (list.isEmpty()) return@Page Empty(state, actions, first)
        Tabs(groups, selected) { state.tabs = state.tabs + (Section.DIRETO to it) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 64.dp),
        ) {
            items(list, key = { it.id }) { ch ->
                ChannelCard(ch, if (ch == list.first()) Modifier.focusRequester(first) else Modifier) { actions.playChannel(ch, list) }
            }
        }
    }
}

@Composable
fun ChannelCard(ch: Channel, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .remote(onClick)
            .background(Card)
            .border(if (focused) 3.dp else 1.dp, if (focused) Accent else Edge)
            .padding(10.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(LogoGround), contentAlignment = Alignment.Center) {
            if (ch.logo.isNotBlank()) AsyncImage(ch.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.62f))
            else T(ch.name.take(3).uppercase(), 40.sp, Rule, Anton)
        }
        T(ch.name.uppercase(), 22.sp, if (focused) Accent else Paper, Anton, ellipsis = true, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        T("● DIRETO", 11.sp, Color(0xFFFF5A4F), weight = FontWeight.Bold, spacing = 0.2.em, modifier = Modifier.padding(top = 4.dp, start = 4.dp, bottom = 4.dp))
    }
}

// --- Filmes e séries --------------------------------------------------------------------------------------

@Composable
fun PosterPage(state: LauncherState, actions: IptvActions, series: Boolean) {
    val first = remember { FocusRequester() }
    val section = if (series) Section.SERIES else Section.FILMES
    val items: List<Triple<Long, String, Pair<String, String>>> = remember(state.catalog, series) {
        if (series) state.catalog.shows.map { Triple(it.id, it.name, it.poster to it.category) }
        else state.catalog.movies.map { Triple(it.id, it.name, it.poster to it.category) }
    }
    val groups = remember(items) { items.groupingBy { it.third.second.ifBlank { "Outros" } }.eachCount().toList() }
    val selected = state.tabs[section]?.takeIf { s -> groups.any { it.first == s } } ?: groups.firstOrNull()?.first
    val list = remember(items, selected) { items.filter { it.third.second.ifBlank { "Outros" } == selected } }
    LaunchedEffect(series) { runCatching { first.requestFocus() } }
    Page(if (series) "04" else "03", "Catálogo", if (series) "SÉRIES" else "FILMES", "${two(items.size)} TÍTULOS") {
        if (list.isEmpty()) return@Page Empty(state, actions, first)
        Tabs(groups, selected) { state.tabs = state.tabs + (section to it) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(bottom = 64.dp),
        ) {
            items(list, key = { it.first }) { (id, name, art) ->
                PosterCard(name, art.first, if (id == list.first().first) Modifier.focusRequester(first) else Modifier) {
                    if (series) state.catalog.shows.firstOrNull { it.id == id }?.let(actions::openShow)
                    else state.catalog.movies.firstOrNull { it.id == id }?.let(actions::openMovie)
                }
            }
        }
    }
}

@Composable
private fun PosterCard(name: String, poster: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.onFocusChanged { focused = it.isFocused }.remote(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Card).border(if (focused) 3.dp else 1.dp, if (focused) Accent else Edge)) {
            if (poster.isNotBlank()) AsyncImage(poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (focused) 3.dp else 1.dp))
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T(name.take(1).uppercase(), 54.sp, Line2, Anton) }
        }
        T(name.uppercase(), 19.sp, if (focused) Accent else Paper, Anton, ellipsis = true, modifier = Modifier.padding(top = 10.dp))
    }
}

// --- Detalhe -------------------------------------------------------------------------------------------------

@Composable
fun DetailOverlay(detail: DetailState, state: LauncherState, actions: IptvActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(detail.movie?.id, detail.show?.id) { runCatching { first.requestFocus() } }
    val d = detail.details
    val name = detail.movie?.name ?: detail.show?.name.orEmpty()
    val poster = detail.movie?.poster ?: detail.show?.poster.orEmpty()
    val backdrop = d?.backdrop?.takeIf { it.isNotBlank() } ?: poster
    Box(Modifier.fillMaxSize().background(Noir)) {
        if (backdrop.isNotBlank()) Box(Modifier.align(Alignment.TopEnd).size(1240.dp, 760.dp)) {
            AsyncImage(backdrop, null, contentScale = ContentScale.Crop, colorFilter = MonochromeFilter, modifier = Modifier.fillMaxSize().alpha(0.85f))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Noir, 0.3f to Noir.copy(alpha = 0.85f), 0.7f to Noir.copy(alpha = 0.2f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Noir)))
        }
        Column(Modifier.offset(x = 192.dp, y = 120.dp).width(1000.dp)) {
            val meta = listOfNotNull(
                if (detail.show != null) "SÉRIE" else "FILME",
                d?.year?.takeIf { it.isNotBlank() }, d?.duration?.takeIf { it.isNotBlank() },
                (d?.genre?.takeIf { it.isNotBlank() } ?: detail.movie?.category ?: detail.show?.category)?.uppercase(),
            ).joinToString(" · ")
            Kicker("··", meta, size = 14)
            T(name.uppercase(), if (name.length <= 18) 120.sp else 88.sp, Accent, Anton, lineHeight = 104.sp, maxLines = 2, ellipsis = true, modifier = Modifier.padding(top = 22.dp))
            T(
                when {
                    detail.loading -> "A carregar…"
                    d?.plot.isNullOrBlank() -> "Sem sinopse do fornecedor."
                    else -> d!!.plot
                },
                15.sp, Soft, lineHeight = 26.sp, maxLines = 5, ellipsis = true, modifier = Modifier.padding(top = 18.dp).width(820.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 28.dp)) {
                if (detail.movie != null) HeroButton("REPRODUZIR", primary = true, modifier = Modifier.focusRequester(first)) { actions.playMovie(detail.movie) }
                HeroButton("VOLTAR", modifier = if (detail.movie == null) Modifier.focusRequester(first) else Modifier) { state.detail = null }
            }
        }
        val episodes = d?.episodes.orEmpty()
        if (detail.show != null && episodes.isNotEmpty()) Episodes(detail.show, episodes, actions)
    }
}

@Composable
private fun Episodes(show: Show, episodes: List<Episode>, actions: IptvActions) {
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    var season by remember(episodes) { mutableStateOf(seasons.first()) }
    Column(Modifier.offset(x = 192.dp, y = 640.dp).width(1664.dp).height(420.dp)) {
        Tabs(seasons.map { s -> "Temporada $s" to episodes.count { it.season == s } }, "Temporada $season") { season = it.removePrefix("Temporada ").toInt() }
        LazyColumn(Modifier.border(1.dp, Line)) {
            items(episodes.filter { it.season == season }, key = { it.id }) { e ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(68.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .remote({ actions.playEpisode(show, e) })
                        .background(if (focused) Accent else Ink)
                        .padding(horizontal = 22.dp),
                ) {
                    T(two(e.num), 30.sp, if (focused) Noir else Dim, Anton, modifier = Modifier.width(80.dp))
                    T(e.title, 15.sp, if (focused) Noir else Paper, spacing = 0.04.em, ellipsis = true, modifier = Modifier.weight(1f))
                    T("▶", 16.sp, if (focused) Noir else Paper)
                }
            }
        }
    }
}

// --- Ligar fonte (edição pública) --------------------------------------------------------------------------

@Composable
fun SourcePage(state: LauncherState, actions: IptvActions) {
    val first = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Page("00", "Fornecedor IPTV", "LIGAR FONTE", state.sourceLabel?.let { "ATUAL · ${it.uppercase()}" }) {
        T("Uma conta Xtream ou qualquer lista M3U. Fica guardada só neste aparelho.", 28.sp, Soft, Serif)
        T("URL DA CONTA OU DA LISTA", 11.sp, Dim, spacing = 0.3.em, modifier = Modifier.padding(top = 30.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            var focused by remember { mutableStateOf(false) }
            BasicTextField(
                value = text,
                onValueChange = { text = it; state.setupError = null },
                singleLine = true,
                textStyle = TextStyle(color = Paper, fontFamily = Mono, fontSize = 18.sp),
                cursorBrush = SolidColor(Accent),
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.saveSource(text) }),
                modifier = Modifier.width(1100.dp).height(64.dp)
                    .focusRequester(first)
                    .onFocusChanged { focused = it.isFocused }
                    .background(Ink)
                    .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Outline)
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) T("https://servidor.exemplo/get.php?username=…&password=…", 18.sp, Rule)
                    inner()
                },
            )
            HeroButton(if (reveal) "OCULTAR" else "MOSTRAR") { reveal = !reveal }
        }
        state.setupError?.let { T(it, 14.sp, Color(0xFFFF5A4F), spacing = 0.04.em, maxLines = 2, modifier = Modifier.padding(top = 12.dp)) }
        Column(Modifier.padding(top = 26.dp).width(1100.dp).border(1.dp, Line)) {
            listOf(
                "XTREAM" to "get.php?username=…&password=… ou utilizador:senha@servidor",
                "LISTA M3U" to "qualquer endereço .m3u ou .m3u8",
                "FICHEIRO" to "lista .m3u completa ou atalho .url, escolhido no aparelho",
            ).forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 22.dp, vertical = 14.dp)) {
                    T(k, 12.sp, Accent, weight = FontWeight.Bold, spacing = 0.24.em, modifier = Modifier.width(180.dp))
                    T(v, 14.sp, Soft, ellipsis = true)
                }
                if (i < 2) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 30.dp)) {
            HeroButton(if (state.setupBusy) "A LIGAR…" else "GUARDAR FONTE", primary = true) { if (!state.setupBusy) actions.saveSource(text) }
            HeroButton("ESCOLHER FICHEIRO") { actions.pickSourceFile() }
            if (state.sourceLabel != null) HeroButton("VOLTAR") { state.section = Section.INICIO }
        }
    }
}
