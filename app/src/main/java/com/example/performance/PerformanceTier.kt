package com.example.performance

import org.json.JSONObject

/**
 * Execution tiers supported by SONIX Adaptive Performance Engine.
 */
enum class PerformanceTier(val tierName: String) {
    PERFORMANCE("performance"),
    BALANCED("balanced"),
    PREMIUM("premium");

    companion object {
        fun fromString(value: String?): PerformanceTier {
            return when (value?.lowercase()) {
                "performance", "perf", "low" -> PERFORMANCE
                "premium", "high", "cinematic" -> PREMIUM
                else -> BALANCED
            }
        }
    }
}

/**
 * User-selectable performance modes.
 */
enum class PerformanceMode(val modeName: String) {
    SMART("smart"),
    PERFORMANCE("performance"),
    BALANCED("balanced"),
    PREMIUM("premium");

    companion object {
        fun fromString(value: String?): PerformanceMode {
            return when (value?.lowercase()) {
                "performance", "perf" -> PERFORMANCE
                "balanced", "balance" -> BALANCED
                "premium", "cinematic" -> PREMIUM
                else -> SMART
            }
        }
    }
}

/**
 * Detailed device specifications and performance status snapshot.
 */
data class DeviceSpecs(
    val cpuCores: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val isLowRamDevice: Boolean,
    val openGlEsVersion: String,
    val glRenderer: String? = null,
    val calculatedTier: PerformanceTier,
    val activeTier: PerformanceTier,
    val activeMode: PerformanceMode,
    val isDynamicallyThrottled: Boolean = false,
    val throttleReason: String? = null
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("cpu_cores", cpuCores)
        json.put("total_ram_mb", totalRamMb)
        json.put("available_ram_mb", availableRamMb)
        json.put("is_low_ram_device", isLowRamDevice)
        json.put("opengles_version", openGlEsVersion)
        json.put("gl_renderer", glRenderer ?: "Unknown")
        json.put("calculated_tier", calculatedTier.tierName)
        json.put("active_tier", activeTier.tierName)
        json.put("active_mode", activeMode.modeName)
        json.put("is_dynamically_throttled", isDynamicallyThrottled)
        json.put("throttle_reason", throttleReason ?: "")
        return json
    }
}
