package com.osrsbot.autotrainer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.osrsbot.autotrainer.utils.Logger

class OSRSAccessibilityService : AccessibilityService() {

    companion object {
        var instance: OSRSAccessibilityService? = null
            private set
        var isRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        Logger.ok("Accessibility service connected.")
        val intent = Intent("com.osrsbot.ACCESSIBILITY_CONNECTED")
        sendBroadcast(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg.contains("oldschool") || pkg.contains("jagex") || pkg.contains("runescape")) {
                    Logger.info("OSRS window active: $pkg")
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
            }
            else -> {}
        }
    }

    override fun onInterrupt() {
        Logger.warn("Accessibility service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        Logger.warn("Accessibility service destroyed.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        isRunning = false
        return super.onUnbind(intent)
    }
}
