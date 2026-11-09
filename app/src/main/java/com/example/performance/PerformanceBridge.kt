package com.example.performance

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JavaScript interface exposed to the WebView as "SonixPerformance".
 * Provides seamless two-way coordination between Android system resources
 * and WebView CSS/DOM rendering levels.
 */
class PerformanceBridge(
    private val engine: AdaptivePerformanceEngine
) {

    @JavascriptInterface
    fun getPerformanceTier(): String {
        return engine.getActiveTier().tierName
    }

    @JavascriptInterface
    fun getPerformanceMode(): String {
        return engine.getActiveMode().modeName
    }

    @JavascriptInterface
    fun setPerformanceMode(modeStr: String): String {
        val mode = PerformanceMode.fromString(modeStr)
        engine.setPerformanceMode(mode)
        return engine.getActiveTier().tierName
    }

    @JavascriptInterface
    fun getDeviceSpecsJson(): String {
        return engine.getDeviceSpecs().toJsonObject().toString()
    }

    @JavascriptInterface
    fun getMetricsJson(): String {
        return engine.metricsCollector.getRecentMetricsJson()
    }

    @JavascriptInterface
    fun reportUiMetric(category: String, operation: String, durationMs: Long, metadata: String? = null) {
        engine.metricsCollector.recordMetric(category, operation, durationMs, metadata)
    }

    @JavascriptInterface
    fun recordUiStartup(durationMs: Long) {
        engine.metricsCollector.recordUiStartupTime(durationMs)
    }

    @JavascriptInterface
    fun recordNavigationLatency(sectionName: String, durationMs: Long) {
        engine.metricsCollector.recordNavigationLatency(sectionName, durationMs)
    }
}
