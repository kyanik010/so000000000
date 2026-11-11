package com.example

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.performance.AdaptivePerformanceEngine
import com.example.performance.DeviceCapabilityDetector
import com.example.performance.PerformanceMode
import com.example.performance.PerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptivePerformanceEngineTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("test_perf_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // 1. Scenario: Low-End Android TV (1GB RAM, 4 cores, Low RAM flag)
    @Test
    fun evaluateTier_lowEndAndroidTv_returnsPerformanceTier() {
        val tier = DeviceCapabilityDetector.evaluateTier(
            cpuCores = 4,
            totalRamMb = 1024,
            isLowRamDevice = true,
            glMajorVersion = 2
        )
        assertEquals(PerformanceTier.PERFORMANCE, tier)
    }

    // 2. Scenario: Low RAM device without flag (1.5GB RAM)
    @Test
    fun evaluateTier_lowRamThreshold_returnsPerformanceTier() {
        val tier = DeviceCapabilityDetector.evaluateTier(
            cpuCores = 4,
            totalRamMb = 1536,
            isLowRamDevice = false,
            glMajorVersion = 3
        )
        assertEquals(PerformanceTier.PERFORMANCE, tier)
    }

    // 3. Scenario: Mid-Tier Android TV (2GB RAM, 4 cores, standard GLES 3.0)
    @Test
    fun evaluateTier_midTierAndroidTv_returnsBalancedTier() {
        val tier = DeviceCapabilityDetector.evaluateTier(
            cpuCores = 4,
            totalRamMb = 2048,
            isLowRamDevice = false,
            glMajorVersion = 3
        )
        assertEquals(PerformanceTier.BALANCED, tier)
    }

    // 4. Scenario: High-End Android TV (Shield TV Pro / 4GB RAM, 8 cores)
    @Test
    fun evaluateTier_highEndAndroidTv_returnsPremiumTier() {
        val tier = DeviceCapabilityDetector.evaluateTier(
            cpuCores = 8,
            totalRamMb = 4096,
            isLowRamDevice = false,
            glMajorVersion = 3
        )
        assertEquals(PerformanceTier.PREMIUM, tier)
    }

    // 5. Scenario: Powerful flagship phone (12GB RAM, 8 cores)
    @Test
    fun evaluateTier_flagshipPhone_returnsPremiumTier() {
        val tier = DeviceCapabilityDetector.evaluateTier(
            cpuCores = 8,
            totalRamMb = 12288,
            isLowRamDevice = false,
            glMajorVersion = 3
        )
        assertEquals(PerformanceTier.PREMIUM, tier)
    }

    // 6. Scenario: Device with missing or unparseable GPU info
    @Test
    fun evaluateTier_missingGpuInfo_defaultsSafelyWithoutCrash() {
        val tier = DeviceCapabilityDetector.evaluateTier(
            cpuCores = 4,
            totalRamMb = 2048,
            isLowRamDevice = false,
            glMajorVersion = 1 // Ancient or missing
        )
        // With 4 cores and 2GB RAM and low GL, should fall back to Performance or Balanced safely
        assertTrue(tier == PerformanceTier.PERFORMANCE || tier == PerformanceTier.BALANCED)
    }

    // 7. Scenario: User Manual Selection Strictly Respected
    @Test
    fun engine_manualSelection_strictlyOverridesCalculatedTier() {
        val engine = AdaptivePerformanceEngine(context, prefs)

        // Force manual Performance Mode
        engine.setPerformanceMode(PerformanceMode.PERFORMANCE)
        assertEquals(PerformanceMode.PERFORMANCE, engine.getActiveMode())
        assertEquals(PerformanceTier.PERFORMANCE, engine.getActiveTier())

        // Force manual Premium Mode
        engine.setPerformanceMode(PerformanceMode.PREMIUM)
        assertEquals(PerformanceMode.PREMIUM, engine.getActiveMode())
        assertEquals(PerformanceTier.PREMIUM, engine.getActiveTier())

        // Force manual Balanced Mode
        engine.setPerformanceMode(PerformanceMode.BALANCED)
        assertEquals(PerformanceMode.BALANCED, engine.getActiveMode())
        assertEquals(PerformanceTier.BALANCED, engine.getActiveTier())

        // Reverting to Smart Mode restores calculated tier
        engine.setPerformanceMode(PerformanceMode.SMART)
        assertEquals(PerformanceMode.SMART, engine.getActiveMode())
        assertNotNull(engine.getActiveTier())
    }

    // 8. Scenario: Memory Pressure Throttling in Smart Mode
    @Test
    fun engine_memoryPressure_throttlesInSmartMode() {
        val engine = AdaptivePerformanceEngine(context, prefs, initialBaseTier = PerformanceTier.PREMIUM)
        engine.setPerformanceMode(PerformanceMode.SMART)

        // Simulate critical memory pressure
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(PerformanceTier.PERFORMANCE, engine.getActiveTier())
        val specs = engine.getDeviceSpecs()
        assertTrue(specs.isDynamicallyThrottled)
        assertNotNull(specs.throttleReason)
    }

    // 9. Scenario: Manual Mode ignores Memory Pressure Throttling
    @Test
    fun engine_manualMode_ignoresMemoryPressureThrottling() {
        val engine = AdaptivePerformanceEngine(context, prefs)
        // User explicitly set Premium
        engine.setPerformanceMode(PerformanceMode.PREMIUM)

        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        // Must remain Premium because user explicitly selected it
        assertEquals(PerformanceTier.PREMIUM, engine.getActiveTier())
        val specs = engine.getDeviceSpecs()
        assertFalse(specs.isDynamicallyThrottled)
    }

    // 10. Scenario: Metric Recording and JSON Serialization
    @Test
    fun metricsCollector_recordsAndExportsJson() {
        val engine = AdaptivePerformanceEngine(context, prefs)
        val collector = engine.metricsCollector

        collector.recordUiStartupTime(450L)
        collector.recordNavigationLatency("movies", 32L)
        collector.recordRemoteKeyResponse(19, 14L) // KEYCODE_DPAD_UP

        val jsonStr = collector.getRecentMetricsJson()
        assertTrue(jsonStr.contains("ui_initial_render"))
        assertTrue(jsonStr.contains("switch_section_movies"))
        assertTrue(jsonStr.contains("key_press_19"))
    }

    // 11. Scenario: Listener management, deduplication, and removal
    @Test
    fun engine_listenerManagement_addsAndRemovesCorrectly() {
        val engine = AdaptivePerformanceEngine(context, prefs, initialBaseTier = PerformanceTier.PREMIUM)
        var callCount = 0
        val listener: (PerformanceTier, com.example.performance.DeviceSpecs) -> Unit = { _, _ ->
            callCount++
        }

        // Add listener twice (deduplication should keep only one)
        engine.addTierChangeListener(listener)
        engine.addTierChangeListener(listener)

        engine.setPerformanceMode(PerformanceMode.PERFORMANCE)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1, callCount)

        // Remove listener
        engine.removeTierChangeListener(listener)
        engine.setPerformanceMode(PerformanceMode.BALANCED)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        // callCount must remain 1 after removal
        assertEquals(1, callCount)
    }

    // 12. Scenario: Memory throttling when targetTier equals activeTier still schedules restoration
    @Test
    fun engine_memoryPressure_sameTargetTierSchedulesRestoration() {
        val engine = AdaptivePerformanceEngine(context, prefs, initialBaseTier = PerformanceTier.PERFORMANCE)
        engine.setPerformanceMode(PerformanceMode.SMART)
        assertEquals(PerformanceTier.PERFORMANCE, engine.getActiveTier())

        // System sends critical memory pressure. Target tier is PERFORMANCE, which matches activeTier.
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        val specs = engine.getDeviceSpecs()
        assertTrue(specs.isDynamicallyThrottled)
        assertNotNull(specs.throttleReason)
    }

    // 13. Scenario: Release cleans up engine and stops further callbacks
    @Test
    fun engine_release_preventsCallbacksAndReleasesResources() {
        val engine = AdaptivePerformanceEngine(context, prefs)
        var called = false
        engine.addTierChangeListener { _, _ -> called = true }

        engine.release()
        engine.setPerformanceMode(PerformanceMode.PERFORMANCE)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(called)
    }
}
