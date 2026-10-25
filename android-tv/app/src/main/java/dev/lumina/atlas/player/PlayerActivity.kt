package dev.lumina.atlas.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.lumina.atlas.data.Channel
import dev.lumina.atlas.data.NowPlayingStore
import dev.lumina.atlas.ui.Accent
import dev.lumina.atlas.ui.Anton
import dev.lumina.atlas.ui.Dim
import dev.lumina.atlas.ui.Line2
import dev.lumina.atlas.ui.Mono
import dev.lumina.atlas.ui.Noir
import dev.lumina.atlas.ui.Paper
import dev.lumina.atlas.ui.Soft

/** What to play. Live channels come with the list they belong to, so ▲/▼ can zap. */
data class PlayItem(val kind: String, val title: String, val subtitle: String, val url: String, val image: String)

object PlayQueue {
    var channels: List<Channel> = emptyList()
    var item: PlayItem? = null
}

/** Full-screen player in the Noir look: ExoPlayer with FFmpeg audio decoders, an overlay that comes and goes. */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var item by mutableStateOf<PlayItem?>(null)
    private var overlayTick by mutableIntStateOf(0)
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var zapIndex = -1

    private val resume by lazy { getSharedPreferences("resume", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player = buildPlayer(this)
        session = MediaSession.Builder(this, player).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; saveNow() }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    duration = player.duration.takeIf { it > 0 } ?: 0L
                    restorePosition()
                }
                if (state == Player.STATE_ENDED) item?.let { resume.edit().remove(it.url).apply() }
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "NÃO FOI POSSÍVEL REPRODUZIR\n${e.errorCodeName}"
                overlayTick++
            }
        })
        setContent { Screen() }
        PlayQueue.item?.let { start(it) } ?: finish()
        lifecycleScope.launch {
            while (isActive) {
                position = player.currentPosition.coerceAtLeast(0)
                if (player.duration > 0) duration = player.duration
                delay(500)
            }
        }
    }

    private var restored = false

    private fun start(next: PlayItem) {
        saveNow()
        item = next
        error = null
        restored = false
        zapIndex = if (next.kind == "live") PlayQueue.channels.indexOfFirst { it.url == next.url } else -1
        val media = MediaItem.Builder().setUri(next.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(next.title).setArtist(next.subtitle).build())
            .build()
        player.setMediaItem(media)
        player.prepare()
        player.playWhenReady = true
        overlayTick++
    }

    private fun restorePosition() {
        val current = item ?: return
        if (restored || current.kind == "live") return
        restored = true
        val at = resume.getLong(current.url, 0L)
        if (at > 30_000 && (duration == 0L || at < duration - 60_000)) player.seekTo(at)
    }

    /** Remembers the position (films, episodes) and feeds the launcher's "Continuar". */
    private fun saveNow() {
        val current = item ?: return
        if (!::player.isInitialized) return
        val pos = player.currentPosition.coerceAtLeast(0)
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        if (current.kind != "live" && pos > 30_000) resume.edit().putLong(current.url, pos).apply()
        val isPlaying = player.isPlaying
        val ctx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            NowPlayingStore.recordOwn(ctx, current, isPlaying, if (current.kind == "live") 0 else pos, if (current.kind == "live") 0 else dur)
        }
    }

    private fun zap(by: Int) {
        val list = PlayQueue.channels
        if (zapIndex < 0 || list.isEmpty()) return
        val next = list[(zapIndex + by + list.size) % list.size]
        start(PlayItem("live", next.name, next.group, next.url, next.logo))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val live = item?.kind == "live"
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (live) { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (live) { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (!live) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); overlayTick++; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!live) { player.seekTo(player.currentPosition + 30_000); overlayTick++; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (!live || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) player.playWhenReady = !player.playWhenReady
                overlayTick++
                return true
            }
        }
        overlayTick++
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        saveNow()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        saveNow()
        session?.release()
        player.release()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        var overlay by remember { mutableStateOf(true) }
        LaunchedEffect(overlayTick, playing) {
            overlay = true
            if (playing) { delay(4500); overlay = false }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false; player = this@PlayerActivity.player; keepScreenOn = true } },
                modifier = Modifier.fillMaxSize(),
            )
            val canvas = Density(density = constraints.maxWidth / 1920f, fontScale = 1f)
            CompositionLocalProvider(LocalDensity provides canvas) {
                Box(Modifier.size(1920.dp, 1080.dp)) {
                    if (overlay || error != null) Overlay()
                    if (buffering && error == null) {
                        Box(Modifier.align(Alignment.Center).width(240.dp).height(3.dp).background(Line2)) {
                            Box(Modifier.fillMaxHeight().width(72.dp).background(Accent))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun T(text: String, size: TextUnit, color: Color, font: FontFamily = Mono, weight: FontWeight = FontWeight.Normal, spacing: TextUnit = 0.em, maxLines: Int = 1) =
        BasicText(text, style = TextStyle(color = color, fontSize = size, fontFamily = font, fontWeight = weight, letterSpacing = spacing), maxLines = maxLines, overflow = TextOverflow.Ellipsis)

    private fun clock(ms: Long): String {
        val s = ms / 1000
        fun two(n: Long) = n.toString().padStart(2, '0')
        return if (s >= 3600) "${s / 3600}:${two((s % 3600) / 60)}:${two(s % 60)}" else "${s / 60}:${two(s % 60)}"
    }

    @Composable
    private fun Overlay() {
        val current = item ?: return
        val live = current.kind == "live"
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(0f to Noir.copy(alpha = 0.92f), 1f to Color.Transparent)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(300.dp).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Noir.copy(alpha = 0.95f))))
            Column(Modifier.padding(start = 112.dp, top = 72.dp, end = 112.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(if (live) "EM DIRETO" else if (current.kind == "episode") "EPISÓDIO" else "FILME", 14.sp, Accent, weight = FontWeight.Bold, spacing = 0.3.em)
                    Box(Modifier.padding(horizontal = 18.dp).width(48.dp).height(1.dp).background(Dim))
                    T(current.subtitle.uppercase(), 14.sp, Soft, spacing = 0.3.em)
                }
                Box(Modifier.padding(top = 18.dp)) { T(current.title.uppercase(), 96.sp, Accent, Anton, maxLines = 2) }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 112.dp, end = 112.dp, bottom = 72.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                error?.let { T(it, 20.sp, Paper, weight = FontWeight.Bold, spacing = 0.12.em, maxLines = 3) }
                if (!live && duration > 0) {
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Line2)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).background(Accent))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    T(if (playing) "A REPRODUZIR" else "EM PAUSA", 14.sp, Paper, weight = FontWeight.Bold, spacing = 0.24.em)
                    if (!live && duration > 0) {
                        T(clock(position), 14.sp, Paper, spacing = 0.14.em)
                        T("−" + clock(duration - position), 14.sp, Dim, spacing = 0.14.em)
                    }
                    Box(Modifier.weight(1f))
                    T(if (live) "▲ ▼ CANAL · OK INFORMAÇÃO · ◀ VOLTAR" else "◀ ▶ −10 S / +30 S · OK PAUSA · VOLTAR SAIR", 12.sp, Dim, spacing = 0.2.em)
                }
            }
        }
    }

    companion object {
        fun play(context: Context, item: PlayItem, channels: List<Channel> = emptyList()) {
            PlayQueue.item = item
            PlayQueue.channels = channels
            context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private fun buildPlayer(context: Context): ExoPlayer {
            val http: DataSource.Factory = DefaultHttpDataSource.Factory()
                .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
            // Some live channels declare an H.264 level too low for their reference frames; patch it on the way in.
            val patched = DataSource.Factory { SpsLevelPatchingDataSource(http.createDataSource()) }
            val renderers = NextRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
            return ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(patched))
                .setRenderersFactory(renderers)
                .build().apply { setWakeMode(C.WAKE_MODE_NETWORK) }
        }
    }
}
