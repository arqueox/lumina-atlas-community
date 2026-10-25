package dev.lumina.atlas

import dev.lumina.atlas.ui.I18n
import dev.lumina.atlas.ui.tr
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.lumina.atlas.data.AppRepository
import dev.lumina.atlas.data.Channel
import dev.lumina.atlas.data.Episode
import dev.lumina.atlas.data.IptvRepository
import dev.lumina.atlas.data.Movie
import dev.lumina.atlas.data.Show
import dev.lumina.atlas.player.PlayItem
import dev.lumina.atlas.player.PlayerActivity
import dev.lumina.atlas.ui.DetailState
import dev.lumina.atlas.ui.IptvActions
import dev.lumina.atlas.data.LauncherPrefs
import dev.lumina.atlas.data.NowPlayingStore
import dev.lumina.atlas.ui.LauncherActions
import dev.lumina.atlas.ui.LauncherScreen
import dev.lumina.atlas.ui.LauncherState
import dev.lumina.atlas.ui.Section

class MainActivity : ComponentActivity(), LauncherActions, IptvActions {
    private lateinit var repo: AppRepository
    private lateinit var iptv: IptvRepository
    private lateinit var state: LauncherState

    // Public edition: a playlist or .url file chosen on the device.
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(64 * 1024 * 1024 + 1)
                        if (bytes.size > 64 * 1024 * 1024) null else String(bytes)
                    }
                }.getOrNull()
            }
            if (text == null) state.setupError = tr("Não foi possível ler o ficheiro (máximo 64 MB)", "Could not read the file (64 MB at most)") else saveSource(text)
        }
    }

    // Apps installed, removed or updated while the launcher is alive.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    // The watcher writes here from its own thread; the row follows.
    private val nowChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.load(this)
        repo = AppRepository(this)
        iptv = IptvRepository(this)
        state = LauncherState(LauncherPrefs(this), packageName)
        state.builtIn = iptv.builtIn
        state.sourceLabel = iptv.saved()?.label
        setContent { LauncherScreen(state, this, this) }
        iptv.cached()?.let { state.catalog = it }
        refreshCatalog()

        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        reload()
        getSharedPreferences(NowPlayingStore.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(nowChanged)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while the launcher is already up: back to Início, the row in focus.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) goHome()
    }

    override fun onResume() {
        super.onResume()
        state.now = System.currentTimeMillis()
        state.canWatch = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        // Catch up with what played while the launcher was in the background (also covers a watcher restart).
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { NowPlayingStore.refresh(this@MainActivity) }
            reloadNow()
        }
    }

    override fun onDestroy() {
        getSharedPreferences(NowPlayingStore.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(nowChanged)
        unregisterReceiver(packages)
        super.onDestroy()
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { repo.load() }
            state.load(list)
        }
    }

    private fun reloadNow() {
        lifecycleScope.launch {
            val (entries, art) = withContext(Dispatchers.IO) {
                val entries = NowPlayingStore.all(this@MainActivity)
                entries to entries.mapNotNull { e ->
                    e.artFile?.let { f -> BitmapFactory.decodeFile(f.path)?.let { e.pkg to it.asImageBitmap() } }
                }.toMap()
            }
            state.setNow(entries, art)
        }
    }

    private fun goHome() {
        state.menuFor = null
        state.section = Section.INICIO
        state.homeTick++
    }

    private fun tryStart(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        state.toast = tr("NÃO DISPONÍVEL NESTE DISPOSITIVO", "NOT AVAILABLE ON THIS DEVICE")
        return false
    }

    override fun open(pkg: String) {
        // The launcher's own entry in "A ver agora": play the same channel, film or episode again.
        if (pkg == packageName) {
            val now = state.now(pkg) ?: return
            val item = PlayItem(now.kind, now.title, now.subtitle, now.url, now.image)
            val list = if (now.kind == "live") state.catalog.channels.filter { c -> c.group == state.catalog.channels.firstOrNull { it.url == now.url }?.group } else emptyList()
            PlayerActivity.play(this, item, list)
            return
        }
        val intent = repo.launchIntent(pkg)
        if (intent == null) {
            state.toast = tr("ESTA APP NÃO ABRE NA TV", "THIS APP DOES NOT OPEN ON TV")
            return
        }
        if (tryStart(intent)) {
            state.opened(pkg)
            state.heroPkg = pkg
        }
    }

    override fun appInfo(pkg: String) {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
    }

    override fun search() {
        tryStart(Intent(Intent.ACTION_ASSIST), Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH))
    }

    override fun settings() {
        tryStart(Intent(Settings.ACTION_SETTINGS))
    }

    override fun watchSetup() {
        tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    override fun forget(pkg: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { NowPlayingStore.forget(this@MainActivity, pkg) }
            reloadNow()
        }
    }

    // --- IPTV -------------------------------------------------------------------------------------------

    override fun refreshCatalog() {
        if (state.sourceLabel == null || state.catalogLoading) return
        state.catalogLoading = true
        state.catalogError = null
        lifecycleScope.launch {
            runCatching { iptv.refresh() }
                .onSuccess { state.catalog = it }
                .onFailure { state.catalogError = it.message ?: it.javaClass.simpleName }
            state.catalogLoading = false
        }
    }

    override fun playChannel(channel: Channel, list: List<Channel>) =
        PlayerActivity.play(this, PlayItem("live", channel.name, channel.group, channel.url, channel.logo), list)

    override fun openMovie(movie: Movie) {
        state.detail = DetailState(movie, null, null, true)
        lifecycleScope.launch {
            val details = iptv.details(movie)
            if (state.detail?.movie?.id == movie.id) state.detail = DetailState(movie, null, details, false)
        }
    }

    override fun openShow(show: Show) {
        state.detail = DetailState(null, show, null, true)
        lifecycleScope.launch {
            val details = runCatching { iptv.details(show) }.getOrNull()
            if (state.detail?.show?.id == show.id) state.detail = DetailState(null, show, details, false)
        }
    }

    override fun playMovie(movie: Movie) =
        PlayerActivity.play(this, PlayItem("movie", movie.name, movie.category, movie.url, movie.poster))

    override fun playEpisode(show: Show, episode: Episode) =
        PlayerActivity.play(this, PlayItem("episode", show.name, tr("T", "S") + "${episode.season} E${episode.num} · ${episode.title}", episode.url, show.poster))

    override fun saveSource(text: String) {
        if (text.isBlank()) { state.setupError = tr("Introduz o URL da conta ou da lista", "Enter the account or playlist URL"); return }
        state.setupBusy = true
        state.setupError = null
        lifecycleScope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { iptv.save(text) } }
            saved.onFailure { state.setupError = it.message ?: tr("Endereço inválido", "Invalid address") }
            saved.onSuccess { source ->
                state.sourceLabel = source.label
                state.catalog = dev.lumina.atlas.data.Catalog.EMPTY
                runCatching { iptv.refresh() }
                    .onSuccess {
                        state.catalog = it
                        state.catalogError = null
                        state.section = Section.INICIO
                        state.toast = tr("FONTE LIGADA · ${it.channels.size} CANAIS", "SOURCE CONNECTED · ${it.channels.size} CHANNELS")
                    }
                    .onFailure { state.setupError = tr("Fonte guardada, mas o catálogo falhou: ", "Source saved, but the catalogue failed: ") + it.message }
            }
            state.setupBusy = false
        }
    }

    override fun setLanguage(lang: dev.lumina.atlas.ui.Lang) {
        I18n.set(this, lang)
        state.setupError = null
        // Catalogue fallbacks ("Canais"/"Channels") and the source label follow the language.
        state.sourceLabel = iptv.saved()?.label
        state.catalog = state.catalog.copy()
    }

    override fun pickSourceFile() {
        runCatching { pickFile.launch(arrayOf("*/*")) }.onFailure { state.toast = tr("NÃO HÁ GESTOR DE FICHEIROS", "NO FILE MANAGER") }
    }

    override fun homeSetup() {
        tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
