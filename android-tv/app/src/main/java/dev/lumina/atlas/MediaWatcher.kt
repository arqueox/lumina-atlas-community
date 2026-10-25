package dev.lumina.atlas

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.ComponentName
import android.os.Handler
import android.os.HandlerThread
import android.service.notification.NotificationListenerService
import dev.lumina.atlas.data.NowPlayingStore

/**
 * Notification access is what Android asks of an app that wants to see other apps' media sessions. This service
 * reads no notifications: it follows the active sessions and records what each app plays ([NowPlayingStore]), so
 * the launcher's "A ver agora" row can show it after the app closes.
 */
class MediaWatcher : NotificationListenerService() {
    private val thread = HandlerThread("now-playing").apply { start() }
    private val handler = Handler(thread.looper)
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var manager: MediaSessionManager? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { follow(it.orEmpty()) }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        manager = m
        val me = ComponentName(this, MediaWatcher::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsChanged, me, handler)
            follow(m.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() = stopFollowing()

    override fun onDestroy() {
        stopFollowing()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun stopFollowing() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
    }

    private fun follow(controllers: List<MediaController>) {
        val current = controllers.associateBy { it.sessionToken }
        callbacks.keys.filter { it.sessionToken !in current }.forEach { gone ->
            callbacks.remove(gone)?.let { gone.unregisterCallback(it) }
        }
        controllers.forEach { controller ->
            if (callbacks.keys.any { it.sessionToken == controller.sessionToken }) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = save(controller)
                override fun onPlaybackStateChanged(state: PlaybackState?) = save(controller)
            }
            controller.registerCallback(callback, handler)
            callbacks[controller] = callback
            save(controller)
        }
    }

    private fun save(controller: MediaController) {
        runCatching { NowPlayingStore.record(this, controller) }
    }
}
