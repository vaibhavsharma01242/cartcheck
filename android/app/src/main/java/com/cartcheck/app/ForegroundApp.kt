package com.cartcheck.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Works out which app is on screen using Usage Access (no Accessibility Service needed). */
object ForegroundApp {
    private var lastPackage: String? = null
    private var lastQueryAt = 0L

    fun current(context: Context): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val from = if (lastQueryAt == 0L) now - 60 * 60 * 1000 else lastQueryAt - 1_000
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastPackage = event.packageName
        }
        lastQueryAt = now
        return lastPackage
    }
}
