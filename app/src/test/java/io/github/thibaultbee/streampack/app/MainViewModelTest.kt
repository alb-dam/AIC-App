package io.github.thibaultbee.streampack.app

import app.cash.turbine.test
import io.github.thibaultbee.streampack.app.data.SettingsRepository
import io.github.thibaultbee.streampack.app.data.rotation.RotationRepository
import io.github.thibaultbee.streampack.app.domain.IAudioProvider
import io.github.thibaultbee.streampack.app.domain.ICameraProvider
import io.github.thibaultbee.streampack.app.domain.IStreamEngine
import io.github.thibaultbee.streampack.app.domain.IVideoEncoderEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    // Mocks
    private lateinit var rotationRepository: RotationRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var streamEngine: IStreamEngine
    private lateinit var videoEncoder: IVideoEncoderEngine
    private lateinit var audioProvider: IAudioProvider
    private lateinit var cameraProvider: ICameraProvider

    // System Under Test
    private lateinit var viewModel: MainViewModel

    // Fake Flows for streamEngine
    private lateinit var isVideoStreamingFlow: MutableStateFlow<Boolean>
    private lateinit var isAudioStreamingFlow: MutableStateFlow<Boolean>
    private lateinit var videoThrowableFlow: MutableSharedFlow<Throwable?>
    private lateinit var audioThrowableFlow: MutableSharedFlow<Throwable?>
    
    // Fake Flows for rotationRepository
    private lateinit var rotationFlow: MutableStateFlow<Int>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        rotationRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        streamEngine = mockk(relaxed = true)
        videoEncoder = mockk(relaxed = true)
        audioProvider = mockk(relaxed = true)
        cameraProvider = mockk(relaxed = true)

        // Setup base settings
        every { settingsRepository.srtUrl } returns "srt://test.url:1234"
        every { settingsRepository.audioSrtUrl } returns "srt://test.url:1234"

        // Setup flows
        isVideoStreamingFlow = MutableStateFlow(false)
        isAudioStreamingFlow = MutableStateFlow(false)
        videoThrowableFlow = MutableSharedFlow(extraBufferCapacity = 1)
        audioThrowableFlow = MutableSharedFlow(extraBufferCapacity = 1)
        rotationFlow = MutableStateFlow(0)

        every { streamEngine.isVideoStreamingFlow } returns isVideoStreamingFlow
        every { streamEngine.isAudioStreamingFlow } returns isAudioStreamingFlow
        every { streamEngine.videoThrowableFlow } returns videoThrowableFlow
        every { streamEngine.audioThrowableFlow } returns audioThrowableFlow
        every { rotationRepository.rotationFlow } returns rotationFlow

        viewModel = MainViewModel(
            rotationRepository,
            settingsRepository,
            streamEngine,
            videoEncoder,
            audioProvider,
            cameraProvider
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // a. startStream() → stato passa da Idle a Connecting a Live
    @Test
    fun `startStream transitions from Idle to Connecting to Live`() = runTest(testDispatcher) {
        coEvery { streamEngine.startVideoStream(any()) } answers { 
            isVideoStreamingFlow.value = true
        }
        coEvery { streamEngine.startAudioStream(any()) } answers {
            isAudioStreamingFlow.value = true
        }

        viewModel.uiState.test {
            assertEquals(StreamState.Idle, awaitItem())

            viewModel.processIntent(StreamIntent.StartStream)
            assertEquals(StreamState.Connecting, awaitItem())

            // Advance time to allow coroutines to run
            testScheduler.advanceUntilIdle()

            // State changes to Live(true, true) as flows emit true
            var finalState = awaitItem()
            while (finalState !is StreamState.Live || !finalState.videoActive || !finalState.audioActive) {
                finalState = awaitItem()
            }
            assertTrue((finalState as StreamState.Live).videoActive)
            assertTrue(finalState.audioActive)
        }
    }

    // b. errore di rete → stato passa a Retrying(attempt=1) poi Retrying(attempt=2)
    @Test
    fun `network error during start triggers Retrying state sequence`() = runTest(testDispatcher) {
        coEvery { streamEngine.startVideoStream(any()) } throws IOException("Network Error")
        coEvery { streamEngine.startAudioStream(any()) } throws IOException("Network Error")

        viewModel.uiState.test {
            assertEquals(StreamState.Idle, awaitItem())

            viewModel.processIntent(StreamIntent.StartStream)
            assertEquals(StreamState.Connecting, awaitItem())

            // Expect a retry for both video and audio
            val retry1a = awaitItem()
            val retry1b = awaitItem()
            assertTrue(retry1a is StreamState.Retrying && retry1a.attempt == 1)
            assertTrue(retry1b is StreamState.Retrying && retry1b.attempt == 1)
            
            // Advance time by RETRY_DELAY_MS (3000ms)
            testScheduler.advanceTimeBy(3000)
            
            val retry2a = awaitItem()
            val retry2b = awaitItem()
            assertTrue(retry2a is StreamState.Retrying && retry2a.attempt == 2)
            assertTrue(retry2b is StreamState.Retrying && retry2b.attempt == 2)
        }
    }

    // c. stop durante retry → stato torna a Idle senza leak di coroutine
    @Test
    fun `stopStream during retry returns to Idle and properly cancels jobs`() = runTest(testDispatcher) {
        coEvery { streamEngine.startVideoStream(any()) } throws IOException("Network Error")
        coEvery { streamEngine.startAudioStream(any()) } throws IOException("Network Error")

        viewModel.uiState.test {
            assertEquals(StreamState.Idle, awaitItem())

            viewModel.processIntent(StreamIntent.StartStream)
            assertEquals(StreamState.Connecting, awaitItem())

            // Wait for Retrying(1)
            assertTrue(awaitItem() is StreamState.Retrying)
            assertTrue(awaitItem() is StreamState.Retrying)

            // Emit Stop before delay completes
            viewModel.processIntent(StreamIntent.StopStream)
            assertEquals(StreamState.Idle, awaitItem())

            // Advance time to verify no more retries
            testScheduler.advanceUntilIdle()
            expectNoEvents()
        }
    }

    // d. toggleCamera() in stato Live → stato rimane Live con camera aggiornata
    @Test
    fun `toggleCamera during Live state keeps State Live and interacts with cameraProvider`() = runTest(testDispatcher) {
        viewModel.uiState.test {
            assertEquals(StreamState.Idle, awaitItem())

            // Bring to Live state directly via flows
            isVideoStreamingFlow.value = true
            isAudioStreamingFlow.value = true
            
            testScheduler.advanceUntilIdle()

            var state = awaitItem()
            while (state !is StreamState.Live || !state.videoActive || !state.audioActive) {
                state = awaitItem() 
            }
            assertTrue((state as StreamState.Live).videoActive)
            assertTrue(state.audioActive)

            // Trigger a toggle camera
            viewModel.processIntent(StreamIntent.ToggleCamera("camera2"))
            testScheduler.advanceUntilIdle()

            // Verify CameraProvider was hit
            coVerify { cameraProvider.setCameraId("camera2") }

            // Because uiState shouldn't emit anything new (value deduplication / skipped logic)
            expectNoEvents()
        }
    }

    // e. errore non recuperabile → stato va a Error(recoverable=false)
    @Test
    fun `unrecoverable engine error emits Error and disconnects`() = runTest(testDispatcher) {
        viewModel.uiState.test {
            assertEquals(StreamState.Idle, awaitItem())

            val fatalError = RuntimeException("Fatal encoder crash")
            videoThrowableFlow.emit(fatalError)
            
            testScheduler.advanceUntilIdle()

            // Error is emitted
            val errorState = awaitItem()
            assertTrue(errorState is StreamState.Error)
            assertEquals(fatalError, (errorState as StreamState.Error).cause)
            assertEquals(false, (errorState as StreamState.Error).recoverable)
            
            // Following disconnecting action, a new Connecting emit should arrive
            assertEquals(StreamState.Connecting, awaitItem())
        }
    }
}
