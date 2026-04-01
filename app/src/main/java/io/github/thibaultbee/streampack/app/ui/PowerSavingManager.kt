package io.github.thibaultbee.streampack.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Lifecycle-aware manager for screen power saving (wakelock, brightness dimming,
 * inactivity auto-dim).
 *
 * Register with `lifecycle.addObserver(powerSavingManager)`.
 */
class PowerSavingManager(
    private val activity: Activity,
    private val overlayProvider: () -> View,
    private val energyButtonProvider: () -> View,
    private val inactivityTimeoutMs: Long = 30_000L,
    private val onPowerSavingEnabled: (() -> Unit)? = null
) : DefaultLifecycleObserver {

    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    private val handler = Handler(Looper.getMainLooper())
    private val energySavingRunnable = Runnable {
        val overlay = overlayProvider()
        if (overlay.visibility == View.GONE) {
            enablePowerSaving()
        }
    }

    /** Whether the power-saving overlay is currently showing. */
    val isPowerSavingActive: Boolean
        get() = overlayProvider().visibility == View.VISIBLE

    // ── Wakelock ──

    fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun setEnergyButtonVisible(visible: Boolean) {
        energyButtonProvider().visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ── Power saving mode (brightness dim + overlay) ──

    fun enablePowerSaving() {
        originalBrightness = activity.window.attributes.screenBrightness

        val layoutParams = activity.window.attributes
        layoutParams.screenBrightness = 0.01f
        activity.window.attributes = layoutParams

        overlayProvider().visibility = View.VISIBLE
        stopInactivityTimer()

        onPowerSavingEnabled?.invoke()
    }

    fun disablePowerSaving() {
        val overlay = overlayProvider()
        if (overlay.visibility == View.VISIBLE) {
            val layoutParams = activity.window.attributes
            layoutParams.screenBrightness = originalBrightness
            activity.window.attributes = layoutParams

            overlay.visibility = View.GONE
            startInactivityTimer()
        }
    }

    // ── Inactivity timer ──

    fun startInactivityTimer() {
        handler.removeCallbacks(energySavingRunnable)
        handler.postDelayed(energySavingRunnable, inactivityTimeoutMs)
    }

    fun stopInactivityTimer() {
        handler.removeCallbacks(energySavingRunnable)
    }

    /** Call from Activity.onRecordingInactive() to fully reset. */
    fun onRecordingStop() {
        setKeepScreenOn(false)
        setEnergyButtonVisible(false)
        disablePowerSaving()
        stopInactivityTimer()
    }

    // ── Lifecycle ──

    override fun onDestroy(owner: LifecycleOwner) {
        stopInactivityTimer()
    }
}
