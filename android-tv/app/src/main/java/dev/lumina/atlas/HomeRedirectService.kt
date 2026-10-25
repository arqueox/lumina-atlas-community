package dev.lumina.atlas

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV ignores third-party home apps: the Home button always opens its own launcher. With this service on
 * (Settings › Accessibility), each time the Google TV home window comes to the front we bring Lumina Atlas up
 * instead. Turning the service off restores the stock behaviour; nothing else on the device is changed.
 */
class HomeRedirectService : AccessibilityService() {
    private var last = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName != GOOGLE_TV_HOME) return
        // Only its home screen, not the panels it opens over apps (they are not activities).
        val cls = event.className?.toString().orEmpty()
        if (!cls.endsWith("Activity")) return
        val t = SystemClock.elapsedRealtime()
        if (t - last < 800) return
        last = t
        startActivity(
            Intent(this, MainActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    override fun onInterrupt() {}

    companion object {
        const val GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx"
    }
}
