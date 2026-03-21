package io.github.thibaultbee.streampack.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.media.AudioManager
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.thibaultbee.streampack.app.databinding.ActivityMainBinding
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.streamers.lifecycle.StreamerActivityLifeCycleObserver
import io.github.thibaultbee.streampack.app.utils.PermissionsManager
import io.github.thibaultbee.streampack.app.utils.showDialog
import io.github.thibaultbee.streampack.app.utils.toast
import kotlinx.coroutines.launch
import android.view.View
import android.widget.SeekBar
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var wasRecActive = false
    private var isFrontCamera = false

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(this.application)
    }

    private val streamerRequiredPermissions =
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

    @SuppressLint("MissingPermission")
    private val permissionsManager = PermissionsManager(
        this,
        streamerRequiredPermissions,
        onAllGranted = { onPermissionsGranted() },
        onShowPermissionRationale = { permissions, onRequiredPermissionLastTime ->
            showDialog(
                title = getString(R.string.permissions_denied_title),
                message = getString(R.string.permissions_rationale, permissions),
                positiveButtonText = R.string.accept,
                onPositiveButtonClick = { onRequiredPermissionLastTime() },
                negativeButtonText = R.string.denied
            )
        },
        onDenied = {
            showDialog(
                getString(R.string.permissions_denied_title),
                getString(R.string.permissions_denied_message),
                positiveButtonText = 0,
                negativeButtonText = 0
            )
        })

    private val streamerLifeCycleObserver by lazy {
        StreamerActivityLifeCycleObserver(viewModel.streamer)
    }

    // ── UI Rotation ──

    private object RotationConstants {
        val RANGE_270 = 45..134
        val RANGE_180 = 135..224
        val RANGE_90 = 225..314
    }

    private var currentUiRotation = 0f

    private val orientationEventListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val baseTarget = when (orientation) {
                    in RotationConstants.RANGE_270  -> 270f
                    in RotationConstants.RANGE_180 -> 180f
                    in RotationConstants.RANGE_90 -> 90f
                    else        -> 0f
                }

                var diff = baseTarget - (currentUiRotation % 360f)
                while (diff <= -180f) diff += 360f
                while (diff > 180f) diff -= 360f

                if (diff != 0f) {
                    currentUiRotation += diff
                    val viewsToRotate = listOf(
                        binding.settingsTopButton,
                        binding.settingsPanel,
                        binding.focusTopButton,
                        binding.focusPanel,
                        binding.liveButton
                    )
                    viewsToRotate.forEach { view ->
                        view.animate().rotation(currentUiRotation).setDuration(300).start()
                    }
                }
            }
        }
    }

    // ── Auto Energy Saving ──

    private val inactivityTimeoutMs = 5000L
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val energySavingRunnable = Runnable {
        if (isRecActive() && binding.energySavingOverlay.visibility == View.GONE) {
            enablePowerSavingMode()
        }
    }

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        val windowInsetsController = WindowInsetsControllerCompat(window, binding.root)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        bindProperties()
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener.enable()
        permissionsManager.requestPermissions()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(streamerLifeCycleObserver)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isRecActive() && binding.energySavingOverlay.visibility == View.GONE) {
            startInactivityTimer()
        }
    }

    // ──────────────────────────────────────────────
    //  Binding & Observers
    // ──────────────────────────────────────────────

    private fun bindProperties() {
        // Live button toggle (with auto-retry)
        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) {
                    viewModel.startStreamWithRetry()
                } else {
                    viewModel.stopStreamAndRetry()
                }
            }
        }

        lifecycle.addObserver(streamerLifeCycleObserver)
        configureStreamer()

        // Error observers
        viewModel.closedThrowableLiveData.observe(this) {
            toast(getString(R.string.error_connection, it.message))
            viewModel.onStreamDisconnected()
        }
        viewModel.throwableLiveData.observe(this) {
            toast(getString(R.string.error_generic, it.message))
        }

        // Streaming / retry state → unified UI updates
        viewModel.isStreamingLiveData.observe(this) { updateRecState() }
        viewModel.isTryingConnectionLiveData.observe(this) { updateRecState() }
        viewModel.isRetryingLiveData.observe(this) { updateRecState() }

        // Settings panel toggle
        binding.settingsTopButton.setOnClickListener {
            if (binding.settingsPanel.visibility == View.VISIBLE) {
                binding.settingsPanel.visibility = View.GONE
                saveSettings()
            } else {
                binding.focusPanel.visibility = View.GONE
                loadSettings()
                binding.settingsPanel.visibility = View.VISIBLE
            }
        }

        // Focus panel toggle
        binding.focusTopButton.setOnClickListener {
            if (binding.focusPanel.visibility == View.VISIBLE) {
                binding.focusPanel.visibility = View.GONE
            } else {
                binding.settingsPanel.visibility = View.GONE
                binding.focusPanel.visibility = View.VISIBLE
            }
        }

        setupFocusControls()
        setupResolutionAndFpsControls()
        setupEnergySaving()
        setupCameraSwitch()
        setupMute()
    }

    private fun loadSettings() {
        binding.srtUrlInput.setText(viewModel.settingsRepository.srtUrl)
        binding.bitrateInput.setText(viewModel.settingsRepository.videoBitrate.toString())
    }

    private fun saveSettings() {
        val url = binding.srtUrlInput.text.toString()
        if (url.isNotBlank()) viewModel.settingsRepository.srtUrl = url

        val bitrate = binding.bitrateInput.text.toString().toIntOrNull()
        if (bitrate != null && bitrate > 0) viewModel.settingsRepository.videoBitrate = bitrate
    }

    private fun setupCameraSwitch() {
        binding.switchCameraButton.setOnClickListener {
            isFrontCamera = !isFrontCamera
            lifecycleScope.launch {
                try {
                    val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val targetFacing = if (isFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                    
                    var targetCameraId: String? = null
                    for (id in cameraManager.cameraIdList) {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                            targetCameraId = id
                            break
                        }
                    }
                    
                    if (targetCameraId == null) {
                        targetCameraId = this@MainActivity.defaultCameraId
                        isFrontCamera = false
                    }
                    
                    viewModel.setCameraId(targetCameraId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error switching camera", e)
                }
            }
        }
    }

    private fun setupMute() {
        binding.micButton.setOnCheckedChangeListener { _, isChecked ->
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isMicrophoneMute = isChecked
        }
    }

    // ──────────────────────────────────────────────
    //  Live button state (deduplicated)
    // ──────────────────────────────────────────────

    private fun updateLiveButtonState() {
        val isStreaming = viewModel.isStreamingLiveData.value == true
        val isTrying = viewModel.isTryingConnectionLiveData.value == true
        val isRetrying = viewModel.isRetryingLiveData.value == true
        binding.liveButton.isChecked = isStreaming || isTrying || isRetrying
    }

    /** True when the REC button is logically "on" (streaming, connecting, or retrying). */
    private fun isRecActive(): Boolean {
        return viewModel.isStreamingLiveData.value == true
                || viewModel.isTryingConnectionLiveData.value == true
                || viewModel.isRetryingLiveData.value == true
    }

    /**
     * Central method called by all state observers.
     * Manages wake lock, screen dimming, orientation, and status dot.
     */
    private fun updateRecState() {
        val recActive = isRecActive()
        val isStreaming = viewModel.isStreamingLiveData.value == true

        if (recActive) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.energySavingButton.visibility = View.VISIBLE
            if (isStreaming) lockOrientation()
            // Only start the timer on the transition from inactive → active
            if (!wasRecActive) {
                startInactivityTimer()
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.energySavingButton.visibility = View.GONE
            unlockOrientation()
            disablePowerSavingMode()
            stopInactivityTimer()
        }

        wasRecActive = recActive
        updateLiveButtonState()
        updateStatusDot()
    }

    private fun updateStatusDot() {
        val isStreaming = viewModel.isStreamingLiveData.value == true
        val color = when {
            isStreaming -> 0xFF4CAF50.toInt()  // Green – connected
            isRecActive() -> 0xFFFFC107.toInt()  // Yellow – trying/retrying
            else -> 0xFFFF4444.toInt()  // Red – idle
        }
        val bg = binding.streamStatusDot.background
        if (bg is android.graphics.drawable.GradientDrawable) {
            bg.setColor(color)
        }
    }

    // ──────────────────────────────────────────────
    //  Focus controls
    // ──────────────────────────────────────────────

    private fun setupFocusControls() {
        binding.focusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (progress == 0) {
                    binding.focusModeLabel.text = getString(R.string.focus_auto)
                    lifecycleScope.launch {
                        try {
                            viewModel.setAutoFocus()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to set auto focus", e)
                            toast(getString(R.string.error_focus))
                        }
                    }
                } else {
                    binding.focusModeLabel.text = if (progress == 100) getString(R.string.focus_infinity) else "$progress%"
                    lifecycleScope.launch {
                        try {
                            viewModel.setManualFocus(progress / 100f)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to set manual focus", e)
                            toast(getString(R.string.error_focus))
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        viewModel.focusProgressLiveData.observe(this) { progress ->
            binding.focusTopButton.text = if (progress == null || progress < 0) "AF" else "MF"
        }
    }

    // ──────────────────────────────────────────────
    //  Resolution & FPS controls
    // ──────────────────────────────────────────────

    private fun setupResolutionAndFpsControls() {
        val accentColor = ContextCompat.getColor(this, R.color.accent)
        val whiteColor = ContextCompat.getColor(this, R.color.white)

        // Map each button to its ResolutionOption
        val resolutionButtons = mapOf(
            binding.res8k  to ResolutionOption.RES_8K,
            binding.resUhd to ResolutionOption.RES_UHD,
            binding.resFhd to ResolutionOption.RES_FHD,
            binding.resHd  to ResolutionOption.RES_HD
        )

        val fpsButtons = mapOf(
            binding.fps60 to 60,
            binding.fps30 to 30
        )

        // Observe resolution → highlight + update header
        viewModel.videoResolutionLiveData.observe(this) { size ->
            resolutionButtons.forEach { (button, option) ->
                button.setTextColor(if (size == option.size) accentColor else whiteColor)
            }
            updateSettingsButtonLabel()
        }

        // Observe FPS → highlight + update header
        viewModel.videoFpsLiveData.observe(this) { fps ->
            fpsButtons.forEach { (button, value) ->
                button.setTextColor(if (fps == value) accentColor else whiteColor)
            }
            updateSettingsButtonLabel()
        }

        // Resolution click listeners
        resolutionButtons.forEach { (button, option) ->
            button.setOnClickListener {
                if (viewModel.isStreamingLiveData.value == true) {
                    toast(getString(R.string.error_stop_stream_resolution))
                } else {
                    viewModel.setResolution(option.size)
                    configureStreamer()
                }
            }
        }

        // FPS click listeners
        fpsButtons.forEach { (button, value) ->
            button.setOnClickListener {
                if (viewModel.isStreamingLiveData.value == true) {
                    toast(getString(R.string.error_stop_stream_fps))
                } else {
                    viewModel.setFps(value)
                    configureStreamer()
                }
            }
        }
    }

    private fun updateSettingsButtonLabel() {
        val size = viewModel.videoResolutionLiveData.value ?: ApplicationConstants.DEFAULT_RESOLUTION
        val fps = viewModel.videoFpsLiveData.value ?: ApplicationConstants.DEFAULT_FPS
        val resLabel = ResolutionOption.labelForSize(size)
        binding.settingsTopButton.text = "$resLabel\n$fps"
    }

    // ──────────────────────────────────────────────
    //  Energy saving
    // ──────────────────────────────────────────────

    private fun setupEnergySaving() {
        binding.energySavingButton.setOnClickListener {
            enablePowerSavingMode()
        }
        binding.energySavingOverlay.setOnClickListener {
            disablePowerSavingMode()
        }
    }

    private fun enablePowerSavingMode() {
        originalBrightness = window.attributes.screenBrightness

        val layoutParams = window.attributes
        layoutParams.screenBrightness = 0.01f
        window.attributes = layoutParams

        binding.energySavingOverlay.visibility = View.VISIBLE
        stopInactivityTimer()

        toast(getString(R.string.energy_saving_message))
    }

    private fun disablePowerSavingMode() {
        if (binding.energySavingOverlay.visibility == View.VISIBLE) {
            val layoutParams = window.attributes
            layoutParams.screenBrightness = originalBrightness
            window.attributes = layoutParams

            binding.energySavingOverlay.visibility = View.GONE
            startInactivityTimer()
        }
    }

    private fun startInactivityTimer() {
        inactivityHandler.removeCallbacks(energySavingRunnable)
        if (isRecActive()) {
            inactivityHandler.postDelayed(energySavingRunnable, inactivityTimeoutMs)
        }
    }

    private fun stopInactivityTimer() {
        inactivityHandler.removeCallbacks(energySavingRunnable)
    }

    // ──────────────────────────────────────────────
    //  Orientation lock
    // ──────────────────────────────────────────────

    private fun lockOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    private fun unlockOrientation() {
        requestedOrientation = ApplicationConstants.supportedOrientation
    }

    // ──────────────────────────────────────────────
    //  Permissions & Streamer setup
    // ──────────────────────────────────────────────

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    private fun onPermissionsGranted() {
        setAVSource()
        setStreamerView()
    }

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    private fun setAVSource() {
        lifecycleScope.launch {
            viewModel.setAudioSource()
            viewModel.setCameraId(this@MainActivity.defaultCameraId)
        }
    }

    private fun setStreamerView() {
        lifecycleScope.launch {
            binding.preview.setVideoSourceProvider(viewModel.streamer)
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureStreamer() {
        lifecycleScope.launch {
            viewModel.setAudioConfig()
            viewModel.setVideoConfig()
        }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun toast(message: String) {
        runOnUiThread { applicationContext.toast(message) }
    }
}