package com.example.performance

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight in-memory metrics collector for UI transitions and responsiveness.
 * Operates with near-zero overhead and bounded capacity.
 */
class PerformanceMetricsCollector(private val maxEntries: Int = 100) {

    private val metricsQueue = ConcurrentLinkedDeque<MetricEntry>()
    private val totalRecordedEvents = AtomicLong(0)
    private var uiStartTimeMs: Long = 0L

    data class MetricEntry(
        val category: String,
        val operation: String,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis(),
        val metadata: String? = null
    ) {
        fun toJsonObject(): JSONObject {
            val json = JSONObject()
            json.put("category", category)
            json.put("operation", operation)
            json.put("duration_ms", durationMs)
            json.put("timestamp", timestamp)
            if (metadata != null) json.put("metadata", metadata)
            return json
        }
    }

    fun recordUiStartupTime(durationMs: Long) {
        uiStartTimeMs = durationMs
        recordMetric("STARTUP", "ui_initial_render", durationMs)
    }

    fun recordNavigationLatency(sectionName: String, durationMs: Long) {
        recordMetric("NAVIGATION", "switch_section_$sectionName", durationMs)
    }

    fun recordRemoteKeyResponse(keyCode: Int, durationMs: Long) {
        recordMetric("REMOTE", "key_press_$keyCode", durationMs)
    }

    fun recordMetric(category: String, operation: String, durationMs: Long, metadata: String? = null) {
        val entry = MetricEntry(category, operation, durationMs, metadata = metadata)
        metricsQueue.addFirst(entry)
        while (metricsQueue.size > maxEntries) {
            metricsQueue.pollLast()
        }
        totalRecordedEvents.incrementAndGet()
        if (durationMs > 250) {
            Log.w("SonixPerfMetrics", "Slow UI event detected: [$category] $operation took ${durationMs}ms")
        }
    }

    fun getRecentMetricsJson(): String {
        val array = JSONArray()
        metricsQueue.take(50).forEach { array.put(it.toJsonObject()) }
        val root = JSONObject()
        root.put("startup_time_ms", uiStartTimeMs)
        root.put("total_events", totalRecordedEvents.get())
        root.put("recent_metrics", array)
        return root.toString()
    }
}
