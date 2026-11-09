package com.example.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Discovers real hardware capabilities using official Android system APIs.
 * Does not rely on device marketing names or assume all TVs are weak or phones strong.
 */
object DeviceCapabilityDetector {

    private const val TAG = "SonixCapabilityDetector"

    /**
     * Inspects device runtime and determines the hardware baseline tier.
     */
    fun detectDeviceSpecs(
        context: Context,
        activeMode: PerformanceMode = PerformanceMode.SMART,
        glRenderer: String? = null
    ): DeviceSpecs {
        val cpuCores = getAvailableProcessors()
        val memInfo = getMemoryInfo(context)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)
        val isLowRam = isLowRamDevice(context)
        val openGlEsVersion = getOpenGlEsVersion(context)
        val glMajor = parseGlMajorVersion(openGlEsVersion)

        val calculatedTier = evaluateTier(
            cpuCores = cpuCores,
            totalRamMb = totalRamMb,
            isLowRamDevice = isLowRam,
            glMajorVersion = glMajor
        )

        val activeTier = when (activeMode) {
            PerformanceMode.PERFORMANCE -> PerformanceTier.PERFORMANCE
            PerformanceMode.BALANCED -> PerformanceTier.BALANCED
            PerformanceMode.PREMIUM -> PerformanceTier.PREMIUM
            PerformanceMode.SMART -> calculatedTier
        }

        Log.i(
            TAG,
            "Detected device hardware: Cores=$cpuCores, RAM=${totalRamMb}MB (Free=${availRamMb}MB), " +
                "LowRamDevice=$isLowRam, GLES=$openGlEsVersion -> CalculatedTier=$calculatedTier, ActiveTier=$activeTier"
        )

        return DeviceSpecs(
            cpuCores = cpuCores,
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            isLowRamDevice = isLowRam,
            openGlEsVersion = openGlEsVersion,
            glRenderer = glRenderer,
            calculatedTier = calculatedTier,
            activeTier = activeTier,
            activeMode = activeMode,
            isDynamicallyThrottled = false,
            throttleReason = null
        )
    }

    /**
     * Core evaluation algorithm separating devices into 3 distinct tiers.
     * Pure function for robust unit and integration testing.
     */
    fun evaluateTier(
        cpuCores: Int,
        totalRamMb: Long,
        isLowRamDevice: Boolean,
        glMajorVersion: Int = 3
    ): PerformanceTier {
        // Strict low-end indicators: system low-RAM flag or under 1.8GB RAM
        if (isLowRamDevice || totalRamMb <= 1800 || (cpuCores <= 4 && totalRamMb <= 2048 && glMajorVersion < 3)) {
            return PerformanceTier.PERFORMANCE
        }

        // High-end indicators: 4GB+ RAM and 6+ cores or modern OpenGL ES 3.2+
        if (totalRamMb >= 3600 && cpuCores >= 6 && glMajorVersion >= 3) {
            return PerformanceTier.PREMIUM
        }

        // Balanced tier: standard 2GB - 3GB devices, quad-core or mid-range octa-core
        return PerformanceTier.BALANCED
    }

    fun getAvailableProcessors(): Int {
        return try {
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        } catch (_: Exception) {
            4 // Safe fallback
        }
    }

    fun getMemoryInfo(context: Context): ActivityManager.MemoryInfo {
        val memoryInfo = ActivityManager.MemoryInfo()
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.getMemoryInfo(memoryInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read MemoryInfo: ${e.message}")
        }
        return memoryInfo
    }

    fun isLowRamDevice(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun getOpenGlEsVersion(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val configInfo = am?.deviceConfigurationInfo
            configInfo?.glEsVersion ?: "3.0"
        } catch (_: Exception) {
            "3.0"
        }
    }

    fun parseGlMajorVersion(glVersionString: String): Int {
        return try {
            val parts = glVersionString.split(".")
            if (parts.isNotEmpty()) {
                parts[0].toIntOrNull() ?: 3
            } else 3
        } catch (_: Exception) {
            3
        }
    }
}
