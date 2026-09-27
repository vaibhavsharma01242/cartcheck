package com.cartcheck.app

/**
 * Cheap on-device check so we only call the LLM on cart/checkout screens.
 * Tweak the markers if an app changes its wording.
 */
object CartDetector {
    private val markers = listOf(
        "bill details", "bill summary", "item total", "items total", "item(s) total",
        "to pay", "grand total", "place order", "proceed to pay", "pay now",
        "delivery fee", "delivery partner fee", "handling charge", "platform fee", "restaurant charges",
    )

    fun looksLikeCart(screenText: String): Boolean {
        if (screenText.length < 40) return false
        val t = screenText.lowercase()
        return markers.count { it in t } >= 2
    }
}
