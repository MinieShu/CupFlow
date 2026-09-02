package com.cupflow.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DecisionLogStore(context: Context) {
    private val preferences = context.getSharedPreferences("cupflow_decisions", Context.MODE_PRIVATE)

    fun append(order: CupOrder?, step: String, result: VisionResult?, outcome: String, detail: String) {
        val entries = JSONArray(preferences.getString(KEY, "[]"))
        entries.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("orderId", order?.id.orEmpty())
            put("drink", order?.drink.orEmpty())
            put("step", step)
            put("event", result?.event.orEmpty())
            put("confidence", result?.confidence ?: 0.0)
            put("source", result?.source.orEmpty())
            put("durationMs", result?.durationMs ?: -1)
            put("traceId", result?.traceId.orEmpty())
            put("outcome", outcome)
            put("detail", detail.take(120))
        })
        val kept = JSONArray()
        val start = (entries.length() - MAX_ENTRIES).coerceAtLeast(0)
        for (index in start until entries.length()) kept.put(entries.optJSONObject(index))
        preferences.edit().putString(KEY, kept.toString()).apply()
    }

    fun load(): List<JSONObject> {
        val entries = JSONArray(preferences.getString(KEY, "[]"))
        return (entries.length() - 1 downTo 0).mapNotNull { index -> entries.optJSONObject(index) }
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 100
    }
}
