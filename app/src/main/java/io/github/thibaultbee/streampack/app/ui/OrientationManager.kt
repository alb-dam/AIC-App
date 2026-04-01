package io.github.thibaultbee.streampack.app.ui

import android.content.Context
import android.view.OrientationEventListener
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Lifecycle-aware manager that rotates a set of UI views based on device orientation.
 *
 * Register it with `lifecycle.addObserver(orientationManager)`.
 * It automatically enables/disables the sensor listener on start/stop.
 */
class OrientationManager(
    context: Context,
    private val viewsProvider: () -> List<View>,
    private val animationDurationMs: Long = 300L
) : DefaultLifecycleObserver {

    private object RotationConstants {
        val RANGE_270 = 45..134
        val RANGE_180 = 135..224
        val RANGE_90 = 225..314
    }

    private var currentUiRotation = 0f

    private val orientationEventListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return

            val baseTarget = when (orientation) {
                in RotationConstants.RANGE_270 -> 270f
                in RotationConstants.RANGE_180 -> 180f
                in RotationConstants.RANGE_90  -> 90f
                else                           -> 0f
            }

            var diff = baseTarget - (currentUiRotation % 360f)
            while (diff <= -180f) diff += 360f
            while (diff > 180f) diff -= 360f

            if (diff != 0f) {
                currentUiRotation += diff
                viewsProvider().forEach { view ->
                    view.animate()
                        .rotation(currentUiRotation)
                        .setDuration(animationDurationMs)
                        .start()
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        orientationEventListener.enable()
    }

    override fun onStop(owner: LifecycleOwner) {
        orientationEventListener.disable()
    }
}
