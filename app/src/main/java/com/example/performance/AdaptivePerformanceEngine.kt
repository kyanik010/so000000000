package com.example.performance

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Central engine orchestrating UI performance tiers, device capabilities,
 * and adaptive rendering optimizations without interfering with playback.
 */
class AdaptivePerformanceEngine(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    initialBaseTier: PerformanceTier? = null
) : ComponentCallbacks2 {

    companion object {
        private const val TAG = "SonixAdaptivePerfEngine"
        private const val PREFS_NAME = "sonix_performance_prefs"
        private const val KEY_PERF_MODE = "sonix_performance_mode"
        private const val HYSTERESIS_COOLDOWN_MS = 15_000L
        private const val RESTORE_STABILIZATION_MS = 30_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val metricsCollector = PerformanceMetricsCollector()

    private var currentMode: PerformanceMode
    private var baseCalculatedTier: PerformanceTier
    private var activeTier: PerformanceTier
    private var isDynamicallyThrottled: Boolean = false
    private var throttleReason: String? = null
    private var lastAdjustmentTime: Long = 0L

    private val tierChangeListeners = mutableListOf<(PerformanceTier, DeviceSpecs) -> Unit>()

    init {
        val savedModeStr = prefs.getString(KEY_PERF_MODE, PerformanceMode.SMART.modeName)
        currentMode = PerformanceMode.fromString(savedModeStr)

        val specs = DeviceCapabilityDetector.detectDeviceSpecs(context, currentMode)
        baseCalculatedTier = initialBaseTier ?: specs.calculatedTier
        activeTier = when (currentMode) {
            PerformanceMode.PERFORMANCE -> PerformanceTier.PERFORMANCE
            PerformanceMode.BALANCED -> PerformanceTier.BALANCED
            PerformanceMode.PREMIUM -> PerformanceTier.PREMIUM
            PerformanceMode.SMART -> baseCalculatedTier
        }

        Log.i(
            TAG,
            "Initialized SONIX Adaptive Performance Engine: Mode=$currentMode, " +
                "BaseTier=$baseCalculatedTier, ActiveTier=$activeTier"
        )
    }

    @Synchronized
    fun getDeviceSpecs(): DeviceSpecs {
        val memInfo = DeviceCapabilityDetector.getMemoryInfo(context)
        val availRamMb = memInfo.availMem / (1024 * 1024)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)

        return DeviceSpecs(
            cpuCores = DeviceCapabilityDetector.getAvailableProcessors(),
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            isLowRamDevice = DeviceCapabilityDetector.isLowRamDevice(context),
            openGlEsVersion = DeviceCapabilityDetector.getOpenGlEsVersion(context),
            glRenderer = null,
            calculatedTier = baseCalculatedTier,
            activeTier = activeTier,
            activeMode = currentMode,
            isDynamicallyThrottled = isDynamicallyThrottled,
            throttleReason = throttleReason
        )
    }

    @Synchronized
    fun getActiveTier(): PerformanceTier = activeTier

    @Synchronized
    fun getActiveMode(): PerformanceMode = currentMode

    @Synchronized
    fun setPerformanceMode(mode: PerformanceMode) {
        if (currentMode == mode) return

        currentMode = mode
        prefs.edit().putString(KEY_PERF_MODE, mode.modeName).apply()

        // Clear dynamic throttling when mode is updated manually
        isDynamicallyThrottled = false
        throttleReason = null

        val previousTier = activeTier
        activeTier = when (mode) {
            PerformanceMode.PERFORMANCE -> PerformanceTier.PERFORMANCE
            PerformanceMode.BALANCED -> PerformanceTier.BALANCED
            PerformanceMode.PREMIUM -> PerformanceTier.PREMIUM
            PerformanceMode.SMART -> baseCalculatedTier
        }

        Log.i(TAG, "Performance Mode switched by user to $mode (ActiveTier: $previousTier -> $activeTier)")

        if (previousTier != activeTier) {
            notifyTierChanged()
        }
    }

    /**
     * Add a listener to observe changes in the active performance tier.
     */
    fun addTierChangeListener(listener: (PerformanceTier, DeviceSpecs) -> Unit) {
        synchronized(tierChangeListeners) {
            tierChangeListeners.add(listener)
        }
    }

    /**
     * Called when the system detects memory pressure.
     * Only adapts if current mode is SMART. Respects user manual selection.
     */
    override fun onTrimMemory(level: Int) {
        if (currentMode != PerformanceMode.SMART) {
            // Respect manual choice, do not alter
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (lastAdjustmentTime > 0L && (now - lastAdjustmentTime < HYSTERESIS_COOLDOWN_MS)) {
            // Anti-flickering / Hysteresis cooldown
            return
        }

        val targetTier = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                throttleReason = "Critical memory pressure (level $level)"
                PerformanceTier.PERFORMANCE
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                throttleReason = "Moderate memory pressure (level $level)"
                if (baseCalculatedTier == PerformanceTier.PREMIUM) PerformanceTier.BALANCED else PerformanceTier.PERFORMANCE
            }
            else -> null
        }

        if (targetTier != null) {
            isDynamicallyThrottled = true
            val tierChanged = (targetTier != activeTier)
            if (tierChanged) {
                lastAdjustmentTime = now
                val previousTier = activeTier
                activeTier = targetTier
                Log.w(
                    TAG,
                    "Adaptive Performance Throttle applied: $previousTier -> $activeTier ($throttleReason)"
                )
                notifyTierChanged()
                scheduleRestorationCheck()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    private fun scheduleRestorationCheck() {
        mainHandler.postDelayed({
            checkAndRestoreTier()
        }, RESTORE_STABILIZATION_MS)
    }

    @Synchronized
    private fun checkAndRestoreTier() {
        if (!isDynamicallyThrottled || currentMode != PerformanceMode.SMART) return

        val memInfo = DeviceCapabilityDetector.getMemoryInfo(context)
        if (!memInfo.lowMemory && (memInfo.availMem > memInfo.threshold * 1.5)) {
            Log.i(TAG, "Memory stabilized. Restoring baseline tier: $baseCalculatedTier")
            isDynamicallyThrottled = false
            throttleReason = null
            activeTier = baseCalculatedTier
            notifyTierChanged()
        } else {
            // Memory still constrained, recheck later
            scheduleRestorationCheck()
        }
    }

    private fun notifyTierChanged() {
        val specs = getDeviceSpecs()
        mainHandler.post {
            synchronized(tierChangeListeners) {
                tierChangeListeners.forEach { it(activeTier, specs) }
            }
        }
    }
}
