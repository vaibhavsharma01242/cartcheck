package com.cartcheck.app

import org.json.JSONObject
import kotlin.math.roundToInt

data class ItemInfo(
    val name: String,
    val qty: Int,
    val calories: Int?,
    val sugarG: Int?,
    val proteinG: Int?,
    val flag: String?,
)

/** Parsed response from the backend. rating: good | caution | bad | none | error */
data class Verdict(
    val rating: String,
    val score: Int?,
    val headline: String,
    val calories: Int?,
    val sugarG: Int?,
    val proteinG: Int?,
    val fatG: Int?,
    val items: List<ItemInfo>,
    val suggestion: String,
    val error: String? = null,
) {
    val isNotCart: Boolean get() = rating == "none"

    fun totalsLine(): String = listOfNotNull(
        calories?.let { "~$it kcal" },
        sugarG?.let { "$it g sugar" },
        proteinG?.let { "$it g protein" },
        fatG?.let { "$it g fat" },
    ).joinToString(" · ")

    fun itemsText(max: Int = 6): String {
        val lines = items.take(max).map { item ->
            val qty = if (item.qty > 1) " ×${item.qty}" else ""
            val stats = listOfNotNull(
                item.calories?.let { "$it kcal" },
                item.sugarG?.let { "$it g sugar" },
            ).joinToString(" · ")
            val base = "• ${item.name}$qty" + if (stats.isNotEmpty()) " — $stats" else ""
            if (item.flag != null) "$base\n   ⚠ ${item.flag}" else base
        }
        val more = items.size - max
        return (if (more > 0) lines + "…and $more more" else lines).joinToString("\n")
    }

    fun toDisplayText(): String = listOf(
        error ?: "",
        totalsLine(),
        itemsText(),
        if (suggestion.isBlank()) "" else "💡 $suggestion",
    ).filter { it.isNotBlank() }.joinToString("\n\n")

    companion object {
        fun error(message: String) = Verdict(
            rating = "error", score = null, headline = "Couldn't check this cart",
            calories = null, sugarG = null, proteinG = null, fatG = null,
            items = emptyList(), suggestion = "", error = message,
        )

        fun fromJson(o: JSONObject): Verdict {
            o.strOrNull("error")?.let { return error(it) }
            val totals = o.optJSONObject("totals")
            val arr = o.optJSONArray("items")
            val items = mutableListOf<ItemInfo>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    items += ItemInfo(
                        name = obj.strOrNull("name") ?: "Item",
                        qty = obj.intOrNull("qty") ?: 1,
                        calories = obj.intOrNull("calories"),
                        sugarG = obj.intOrNull("sugar_g"),
                        proteinG = obj.intOrNull("protein_g"),
                        flag = obj.strOrNull("flag"),
                    )
                }
            }
            return Verdict(
                rating = (o.strOrNull("rating") ?: "caution").lowercase(),
                score = o.intOrNull("score"),
                headline = o.strOrNull("headline") ?: "Cart checked",
                calories = totals?.intOrNull("calories"),
                sugarG = totals?.intOrNull("sugar_g"),
                proteinG = totals?.intOrNull("protein_g"),
                fatG = totals?.intOrNull("fat_g"),
                items = items,
                suggestion = o.strOrNull("suggestion") ?: "",
            )
        }
    }
}

private fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val d = optDouble(key)
    return if (d.isNaN()) null else d.roundToInt()
}

private fun JSONObject.strOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null
    else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
