package com.cartcheck.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Sends OCR text (never the screenshot) to your backend, which calls the LLM. */
object ApiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    suspend fun analyze(prefs: Prefs, app: String, screenText: String): Verdict = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("app", app)
                .put("screen_text", screenText.take(6000))
                .put(
                    "goals", JSONObject()
                        .put("max_calories", prefs.maxCalories)
                        .put("max_sugar_g", prefs.maxSugarG)
                        .put("notes", prefs.goalNotes)
                )
            val request = Request.Builder()
                .url(prefs.backendUrl.trimEnd('/') + "/analyze")
                .header("X-App-Token", prefs.appToken)
                .post(payload.toString().toRequestBody(jsonType))
                .build()

            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    return@withContext Verdict.error(
                        "Server ${resp.code}" + if (!msg.isNullOrBlank()) ": $msg" else ""
                    )
                }
                Verdict.fromJson(JSONObject(body))
            }
        } catch (e: Exception) {
            Verdict.error(e.message ?: e.javaClass.simpleName)
        }
    }
}
