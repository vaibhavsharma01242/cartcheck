package com.cartcheck.app

import android.content.Context

/** User goals + backend settings, stored locally. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cartcheck", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = sp.getString("backendUrl", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = sp.edit().putString("backendUrl", v).apply()

    var appToken: String
        get() = sp.getString("appToken", "") ?: ""
        set(v) = sp.edit().putString("appToken", v).apply()

    var maxCalories: Int
        get() = sp.getInt("maxCalories", 800)
        set(v) = sp.edit().putInt("maxCalories", v).apply()

    var maxSugarG: Int
        get() = sp.getInt("maxSugarG", 25)
        set(v) = sp.edit().putInt("maxSugarG", v).apply()

    var goalNotes: String
        get() = sp.getString("goalNotes", "") ?: ""
        set(v) = sp.edit().putString("goalNotes", v).apply()

    companion object {
        // 10.0.2.2 is your computer's localhost as seen from the Android emulator.
        const val DEFAULT_URL = "http://10.0.2.2:8080"
    }
}
