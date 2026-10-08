package com.halo.floatingbutton

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Used only to perform two global actions on request: lock the screen and
 * take a screenshot. It reads nothing and reacts to no events.
 */
class HaloAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    companion object {
        @Volatile
        var instance: HaloAccessibilityService? = null
    }
}
