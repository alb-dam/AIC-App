package io.github.thibaultbee.streampack.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.thibaultbee.streampack.app.databinding.ActivityMainBinding
import io.github.thibaultbee.streampack.app.ui.OrientationManager
import io.github.thibaultbee.streampack.app.ui.PermissionsHandler
import io.github.thibaultbee.streampack.app.ui.PowerSavingManager
import io.github.thibaultbee.streampack.app.utils.showDialog
import io.github.thibaultbee.streampack.app.utils.toast
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.streamers.lifecycle.StreamerActivityLifeCycleObserver
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val LIVE_BUTTON_COOLDOWN_MS = 5000L
    }

    // ── Core ──

    private lateinit var binding: ActivityMainBinding

    private val streamer by lazy { SingleStreamer(applicationContext, withAudio = true, withVideo = true) }
    private val audioStreamer by lazy { SingleStreamer(applicationContext, withAudio = true, withVideo = false) }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(this.application, streamer, audioStreamer)
    }

    // ── Extracted Components ──

    @SuppressLint("MissingPermission")
    private val permissionsHandler = PermissionsHandler(
        activity = this,
        permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        onGranted = { onPermissionsGranted() },
        onRationale = { permissions, retry ->
            showDialog(
                title = getString(R.string.permissions_denied_title),
                message = getString(R.string.permissions_rationale, permissions),
                positiveButtonText = R.string.accept,
                onPositiveButtonClick = { retry() },
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
        }
    )

    private val orientationManager by lazy {
        OrientationManager(
            context = this,
            viewsProvider = {
                listOf(
                    binding.settingsTopButton,
                    binding.settingsPanel,
                    binding.focusTopButton,
                    binding.focusPanel,
                    binding.liveButton
                )
            }
        )
    }

    private val powerSavingManager by lazy {
        PowerSavingManager(
            activity = this,
            overlayProvider = { binding.energySavingOverlay },
            energyButtonProvider = { binding.energySavingButton },
            onPowerSavingEnabled = { toast(getString(R.string.energy_saving_message)) }
        )
    }

    private val streamerLifeCycleObserver by lazy {
        StreamerActivityLifeCycleObserver(streamer)
    }

    private val audioStreamerLifeCycleObserver by lazy {
        StreamerActivityLifeCycleObserver(audioStreamer)
    }

    // ── State ──

    private var wasRecActive = false
    private var isFrontCamera = false

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

        // Register lifecycle observers
        lifecycle.addObserver(orientationManager)
        lifecycle.addObserver(streamerLifeCycleObserver)
        lifecycle.addObserver(audioStreamerLifeCycleObserver)

        bindObservers()
        bindControls()
        configureStreamer()
    }

    override fun onStart() {
        super.onStart()
        permissionsHandler.request()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(streamerLifeCycleObserver)
        lifecycle.removeObserver(audioStreamerLifeCycleObserver)
        lifecycle.removeObserver(orientationManager)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isRecActive(viewModel.uiState.value) && !powerSavingManager.isPowerSavingActive) {
            powerSavingManager.startInactivityTimer()
        }
    }

    // ──────────────────────────────────────────────
    //  ViewModel Observation
    // ──────────────────────────────────────────────

    private fun bindObservers() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                handleStreamState(state)
            }
        }

        viewModel.focusProgressLiveData.observe(this) { progress ->
            binding.focusTopButton.text = if (progress == null || progress < 0) "AF" else "MF"
        }

        observeResolutionAndFps()
    }

    // ──────────────────────────────────────────────
    //  UI Controls Setup
    // ──────────────────────────────────────────────

    private fun bindControls() {
        // Live button
        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) viewModel.processIntent(StreamIntent.StartStream) else viewModel.processIntent(StreamIntent.StopStream)
                view.isEnabled = false
                view.alpha = 0.5f
                Handler(Looper.getMainLooper()).postDelayed({
                    view.isEnabled = true
                    view.alpha = 1.0f
                }, LIVE_BUTTON_COOLDOWN_MS)
            }
        }

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

        setupFocusSeekBar()
        setupCameraSwitch()
        setupMute()

        // Energy saving (delegated to PowerSavingManager)
        binding.energySavingButton.setOnClickListener { powerSavingManager.enablePowerSaving() }
        binding.energySavingOverlay.setOnClickListener { powerSavingManager.disablePowerSaving() }
    }

    // ──────────────────────────────────────────────
    //  Streaming State → UI
    // ──────────────────────────────────────────────

    private fun isRecActive(state: StreamState): Boolean = state !is StreamState.Idle && state !is StreamState.Error

    private fun handleStreamState(state: StreamState) {
        val recActive = isRecActive(state)
        val isStreaming = state is StreamState.Live

        if (recActive) {
            powerSavingManager.setKeepScreenOn(true)
            powerSavingManager.setEnergyButtonVisible(true)
            if (isStreaming) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            if (!wasRecActive) powerSavingManager.startInactivityTimer()
        } else {
            requestedOrientation = ApplicationConstants.supportedOrientation
            powerSavingManager.onRecordingStop()
        }

        wasRecActive = recActive
        updateLiveButtonState(recActive)
        updateStatusDot(state)

        if (state is StreamState.Error) {
            toast(getString(R.string.error_generic, state.cause?.message ?: ""))
        }
    }

    private fun updateLiveButtonState(recActive: Boolean) {
        binding.liveButton.isChecked = recActive
    }

    private fun updateStatusDot(state: StreamState) {
        val color = when (state) {
            is StreamState.Live -> 0xFF4CAF50.toInt()
            is StreamState.Connecting, is StreamState.Retrying -> 0xFFFFC107.toInt()
            else -> 0xFFFF4444.toInt()
        }
        val bg = binding.streamStatusDot.background
        if (bg is android.graphics.drawable.GradientDrawable) {
            bg.setColor(color)
        }
    }

    // ──────────────────────────────────────────────
    //  Settings
    // ──────────────────────────────────────────────

    private fun loadSettings() {
        binding.ipInput.setText(viewModel.settingsRepository.srtIp)
        binding.srtPortInput.setText(viewModel.settingsRepository.srtPort.toString())
        binding.audioSrtPortInput.setText(viewModel.settingsRepository.audioSrtPort.toString())
        val mbps = viewModel.settingsRepository.videoBitrate / 1_000_000f
        val mbpsStr = if (mbps == mbps.toInt().toFloat()) mbps.toInt().toString() else mbps.toString()
        binding.bitrateInput.setText(mbpsStr)
    }

    private fun saveSettings() {
        val ip = binding.ipInput.text.toString()
        if (ip.isNotBlank()) viewModel.settingsRepository.srtIp = ip
        binding.srtPortInput.text.toString().toIntOrNull()?.let { viewModel.settingsRepository.srtPort = it }
        binding.audioSrtPortInput.text.toString().toIntOrNull()?.let { viewModel.settingsRepository.audioSrtPort = it }
        binding.bitrateInput.text.toString().toFloatOrNull()?.takeIf { it > 0 }?.let {
            viewModel.settingsRepository.videoBitrate = (it * 1_000_000).toInt()
        }
    }

    // ──────────────────────────────────────────────
    //  Resolution & FPS
    // ──────────────────────────────────────────────

    private fun observeResolutionAndFps() {
        val accentColor = ContextCompat.getColor(this, R.color.accent)
        val whiteColor = ContextCompat.getColor(this, R.color.white)

        val resolutionButtons = mapOf(
            binding.res8k  to ResolutionOption.RES_8K,
            binding.resUhd to ResolutionOption.RES_UHD,
            binding.resFhd to ResolutionOption.RES_FHD,
            binding.resHd  to ResolutionOption.RES_HD
        )
        val fpsButtons = mapOf(binding.fps60 to 60, binding.fps30 to 30)

        viewModel.videoResolutionLiveData.observe(this) { size ->
            resolutionButtons.forEach { (button, option) ->
                button.setTextColor(if (size.width == option.size.width && size.height == option.size.height) accentColor else whiteColor)
            }
            updateSettingsButtonLabel()
        }
        viewModel.videoFpsLiveData.observe(this) { fps ->
            fpsButtons.forEach { (button, value) ->
                button.setTextColor(if (fps == value) accentColor else whiteColor)
            }
            updateSettingsButtonLabel()
        }

        resolutionButtons.forEach { (button, option) ->
            button.setOnClickListener {
                if (viewModel.uiState.value is StreamState.Live) {
                    toast(getString(R.string.error_stop_stream_resolution))
                } else {
                    viewModel.setResolution(option.size.width, option.size.height)
                    configureStreamer()
                }
            }
        }
        fpsButtons.forEach { (button, value) ->
            button.setOnClickListener {
                if (viewModel.uiState.value is StreamState.Live) {
                    toast(getString(R.string.error_stop_stream_fps))
                } else {
                    viewModel.setFps(value)
                    configureStreamer()
                }
            }
        }
    }

    private fun updateSettingsButtonLabel() {
        val size = viewModel.videoResolutionLiveData.value ?: Resolution(1920, 1080)
        val fps = viewModel.videoFpsLiveData.value ?: ApplicationConstants.DEFAULT_FPS
        val resLabel = ResolutionOption.labelForSize(android.util.Size(size.width, size.height))
        binding.settingsTopButton.text = "$resLabel\n$fps"
    }

    // ──────────────────────────────────────────────
    //  Focus / Camera / Mute
    // ──────────────────────────────────────────────

    private fun setupFocusSeekBar() {
        binding.focusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (progress == 0) {
                    binding.focusModeLabel.text = getString(R.string.focus_auto)
                    lifecycleScope.launch {
                        try { viewModel.setAutoFocus() }
                        catch (e: Exception) { Log.w(TAG, "Failed to set auto focus", e); toast(getString(R.string.error_focus)) }
                    }
                } else {
                    binding.focusModeLabel.text = if (progress == 100) getString(R.string.focus_infinity) else "$progress%"
                    lifecycleScope.launch {
                        try { viewModel.setManualFocus(progress / 100f) }
                        catch (e: Exception) { Log.w(TAG, "Failed to set manual focus", e); toast(getString(R.string.error_focus)) }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
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
                        if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) { targetCameraId = id; break }
                    }
                    if (targetCameraId == null) { targetCameraId = this@MainActivity.defaultCameraId; isFrontCamera = false }
                    viewModel.setCameraId(targetCameraId)
                } catch (e: Exception) { Log.e(TAG, "Error switching camera", e) }
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
    //  Permissions & Streamer Setup
    // ──────────────────────────────────────────────

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    private fun onPermissionsGranted() {
        lifecycleScope.launch {
            viewModel.setAudioSource()
            viewModel.setCameraId(this@MainActivity.defaultCameraId)
        }
        lifecycleScope.launch {
            binding.preview.setVideoSourceProvider(streamer)
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

    private fun toast(message: String) {
        runOnUiThread { applicationContext.toast(message) }
    }
}