package edu.cse499a.rocobangla.ui

import androidx.lifecycle.SavedStateHandle
import edu.cse499a.rocobangla.FakeAnalysisRepository
import edu.cse499a.rocobangla.FakeImageInspector
import edu.cse499a.rocobangla.FakeSettingsRepository
import edu.cse499a.rocobangla.core.AppConfig
import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.core.ServerSettings
import edu.cse499a.rocobangla.data.JobState
import edu.cse499a.rocobangla.data.JobTicket
import edu.cse499a.rocobangla.data.ModelStatus
import edu.cse499a.rocobangla.data.Outcome
import edu.cse499a.rocobangla.data.ServerHealth
import edu.cse499a.rocobangla.picked
import edu.cse499a.rocobangla.result
import edu.cse499a.rocobangla.ui.analyze.AnalyzePhase
import edu.cse499a.rocobangla.ui.analyze.AnalyzeViewModel
import edu.cse499a.rocobangla.ui.analyze.ConnectionStatus
import edu.cse499a.rocobangla.ui.analyze.Notice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeAnalysisRepository
    private lateinit var settings: FakeSettingsRepository
    private lateinit var images: FakeImageInspector
    private lateinit var saved: SavedStateHandle

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeAnalysisRepository()
        settings = FakeSettingsRepository()
        images = FakeImageInspector().apply { this.images["content://media/1"] = Outcome.Success(picked()) }
        saved = SavedStateHandle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newViewModel(handle: SavedStateHandle = saved) =
        AnalyzeViewModel(repo, settings, images, handle, clock = { testScheduler.currentTime })

    /** A ViewModel with an image picked and a Ready connection. */
    private fun TestScope.readyViewModel(): AnalyzeViewModel {
        val vm = newViewModel()
        vm.startHealthMonitor()
        vm.onImagePicked("content://media/1")
        runCurrent()
        return vm
    }

    @Test
    fun startsIdleAndChecking() = runTest(dispatcher) {
        val vm = newViewModel()
        assertEquals(AnalyzePhase.Idle, vm.state.value.phase)
        assertEquals(ConnectionStatus.Checking, vm.state.value.connection)
        assertFalse(vm.state.value.canAnalyze)
    }

    @Test
    fun pickingAnImageSelectsIt() = runTest(dispatcher) {
        val vm = readyViewModel()
        assertEquals(AnalyzePhase.ImageSelected, vm.state.value.phase)
        assertEquals("xray.jpg", vm.state.value.image?.displayName)
        assertEquals(ConnectionStatus.Ready, vm.state.value.connection)
        assertTrue(vm.state.value.canAnalyze)
        vm.stopHealthMonitor()
    }

    @Test
    fun cancelledPickerChangesNothing() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onImagePicked(null)
        runCurrent()
        assertEquals(AnalyzePhase.Idle, vm.state.value.phase)
    }

    @Test
    fun imageOver10MbIsRejectedBeforeUpload() = runTest(dispatcher) {
        images.images["content://big"] = Outcome.Success(picked("content://big", size = AppConfig.MAX_IMAGE_BYTES + 1))
        val vm = newViewModel()
        vm.onImagePicked("content://big")
        runCurrent()
        assertNull(vm.state.value.image)
        assertEquals(AppError.TooLarge, vm.state.value.imageError)
        vm.onAnalyze()
        assertEquals(0, repo.submitCalls)
    }

    @Test
    fun unsupportedTypeIsRejected() = runTest(dispatcher) {
        images.images["content://heic"] = Outcome.Success(picked("content://heic", mime = "image/heic"))
        val vm = newViewModel()
        vm.onImagePicked("content://heic")
        runCurrent()
        assertEquals(AppError.UnsupportedImageType, vm.state.value.imageError)
    }

    @Test
    fun unreadableImageShowsError() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onImagePicked("content://gone")
        runCurrent()
        assertEquals(AppError.ImageUnreadable, vm.state.value.imageError)
    }

    @Test
    fun analyzeIsBlockedWhenOffline() = runTest(dispatcher) {
        repo.health = Outcome.Failure(AppError.Offline)
        val vm = readyViewModel()
        assertEquals(ConnectionStatus.Offline, vm.state.value.connection)
        assertFalse(vm.state.value.canAnalyze)
        vm.onAnalyze()
        runCurrent()
        assertEquals(0, repo.submitCalls)
        vm.stopHealthMonitor()
    }

    @Test
    fun happyPathQueuedRunningSuccess() = runTest(dispatcher) {
        repo.submitResult = Outcome.Success(JobTicket("job-1", 2))
        repo.jobAnswers += Outcome.Success(JobState.Queued(2))
        repo.jobAnswers += Outcome.Success(JobState.Running)
        repo.jobAnswers += Outcome.Success(JobState.Completed(result()))
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onCaptionChange("  left pneumothorax ")

        vm.onAnalyze()
        assertEquals(AnalyzePhase.Submitting, vm.state.value.phase)
        runCurrent()
        assertEquals(AnalyzePhase.Queued("job-1", 2, 0), vm.state.value.phase)
        assertEquals("job-1", saved.get<String>(AnalyzeViewModel.KEY_JOB_ID))

        advanceTimeBy(AppConfig.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(AnalyzePhase.Running("job-1", 0), vm.state.value.phase)

        advanceTimeBy(AppConfig.POLL_INTERVAL_MS)
        runCurrent()
        val phase = vm.state.value.phase
        assertTrue(phase is AnalyzePhase.Success)
        assertEquals(result(), (phase as AnalyzePhase.Success).result)
        assertEquals(listOf("left pneumothorax"), repo.submittedCaptions)
        assertEquals(1, repo.submitCalls)
        assertNull(saved.get<String>(AnalyzeViewModel.KEY_JOB_ID))

        val polls = repo.jobCalls
        advanceTimeBy(30_000)
        assertEquals("polling stops after success", polls, repo.jobCalls)
    }

    @Test
    fun repeatedTapsSubmitOnce() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Success(JobState.Queued(1))
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onAnalyze()
        vm.onAnalyze()
        vm.onAnalyze()
        runCurrent()
        vm.onAnalyze()
        runCurrent()
        assertEquals(1, repo.submitCalls)
        vm.onCancelWaiting()
    }

    @Test
    fun failedJobMapsCodeAndRetryCreatesNewJob() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Success(JobState.Failed(AppError.EmptyOutput))
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onAnalyze()
        runCurrent()
        assertEquals(AnalyzePhase.Error(AppError.EmptyOutput), vm.state.value.phase)
        assertNull(saved.get<String>(AnalyzeViewModel.KEY_JOB_ID))

        repo.jobAnswers.clear()
        repo.jobAnswers += Outcome.Success(JobState.Completed(result()))
        vm.onRetry()
        runCurrent()
        assertEquals(2, repo.submitCalls)
        assertTrue(vm.state.value.phase is AnalyzePhase.Success)
    }

    @Test
    fun submitErrorsAreMapped() = runTest(dispatcher) {
        repo.submitResult = Outcome.Failure(AppError.QueueFull)
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onAnalyze()
        runCurrent()
        assertEquals(AnalyzePhase.Error(AppError.QueueFull), vm.state.value.phase)
        vm.onDismissError()
        assertEquals(AnalyzePhase.ImageSelected, vm.state.value.phase)
    }

    @Test
    fun pollingTimesOutAfterFiveMinutesAndStops() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Success(JobState.Running)
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onAnalyze()
        runCurrent()
        advanceTimeBy(AppConfig.POLL_TIMEOUT_MS - AppConfig.POLL_INTERVAL_MS)
        runCurrent()
        assertTrue(vm.state.value.phase is AnalyzePhase.Running)
        advanceTimeBy(AppConfig.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(AnalyzePhase.Error(AppError.Timeout), vm.state.value.phase)
        val polls = repo.jobCalls
        assertEquals(AppConfig.POLL_TIMEOUT_MS / AppConfig.POLL_INTERVAL_MS, polls.toLong())
        advanceTimeBy(60_000)
        assertEquals(polls, repo.jobCalls)
    }

    @Test
    fun cancelStopsPollingAndKeepsImage() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Success(JobState.Queued(3))
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onAnalyze()
        runCurrent()
        val polls = repo.jobCalls
        vm.onCancelWaiting()
        assertEquals(AnalyzePhase.ImageSelected, vm.state.value.phase)
        assertEquals(Notice.StoppedWaiting, vm.state.value.notice)
        assertNull(saved.get<String>(AnalyzeViewModel.KEY_JOB_ID))
        advanceTimeBy(20_000)
        assertEquals(polls, repo.jobCalls)
        vm.onNoticeShown()
        assertNull(vm.state.value.notice)
    }

    @Test
    fun oneNetworkBlipIsTolerated() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Failure(AppError.Offline)
        repo.jobAnswers += Outcome.Failure(AppError.Offline)
        repo.jobAnswers += Outcome.Success(JobState.Completed(result()))
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onAnalyze()
        runCurrent()
        advanceTimeBy(2 * AppConfig.POLL_INTERVAL_MS)
        runCurrent()
        assertTrue(vm.state.value.phase is AnalyzePhase.Success)
    }

    @Test
    fun repeatedNetworkFailuresEndAsOffline() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Failure(AppError.Offline)
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onAnalyze()
        runCurrent()
        advanceTimeBy(AppConfig.MAX_CONSECUTIVE_POLL_FAILURES * AppConfig.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(AnalyzePhase.Error(AppError.Offline), vm.state.value.phase)
        assertEquals(ConnectionStatus.Offline, vm.state.value.connection)
        assertEquals(AppConfig.MAX_CONSECUTIVE_POLL_FAILURES, repo.jobCalls)
    }

    @Test
    fun unauthorizedPollFailsImmediately() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Failure(AppError.Unauthorized)
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onAnalyze()
        runCurrent()
        assertEquals(AnalyzePhase.Error(AppError.Unauthorized), vm.state.value.phase)
        assertEquals(1, repo.jobCalls)
    }

    @Test
    fun restoredJobResumesPollingWithoutResubmitting() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Success(JobState.Running)
        repo.jobAnswers += Outcome.Success(JobState.Completed(result()))
        val handle = SavedStateHandle(
            mapOf(
                AnalyzeViewModel.KEY_JOB_ID to "job-9",
                AnalyzeViewModel.KEY_STARTED_AT to 0L,
                AnalyzeViewModel.KEY_IMAGE_URI to "content://media/1",
                AnalyzeViewModel.KEY_CAPTION to "left pneumothorax",
            ),
        )
        val vm = newViewModel(handle)
        assertEquals(AnalyzePhase.Queued("job-9", 0, 0), vm.state.value.phase)
        assertEquals("left pneumothorax", vm.state.value.caption)
        runCurrent()
        assertEquals(AnalyzePhase.Running("job-9", 0), vm.state.value.phase)
        assertEquals("xray.jpg", vm.state.value.image?.displayName)
        advanceTimeBy(AppConfig.POLL_INTERVAL_MS)
        runCurrent()
        assertTrue(vm.state.value.phase is AnalyzePhase.Success)
        assertEquals(0, repo.submitCalls)
    }

    @Test
    fun restoredJobStillTimesOutFromOriginalStart() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Success(JobState.Running)
        advanceTimeBy(AppConfig.POLL_TIMEOUT_MS + 1)
        val handle = SavedStateHandle(mapOf(AnalyzeViewModel.KEY_JOB_ID to "job-9", AnalyzeViewModel.KEY_STARTED_AT to 0L))
        val vm = newViewModel(handle)
        runCurrent()
        assertEquals(AnalyzePhase.Error(AppError.Timeout), vm.state.value.phase)
    }

    @Test
    fun healthMonitorMapsServerStates() = runTest(dispatcher) {
        val vm = newViewModel()
        repo.health = Outcome.Success(ServerHealth(ModelStatus.Ready, 2, true))
        vm.startHealthMonitor()
        runCurrent()
        assertEquals(ConnectionStatus.Busy(3), vm.state.value.connection)
        assertTrue(vm.state.value.connection.allowsAnalyze)

        repo.health = Outcome.Success(ServerHealth(ModelStatus.Loading, 0, false))
        advanceTimeBy(AppConfig.HEALTH_INTERVAL_MS)
        runCurrent()
        assertEquals(ConnectionStatus.ModelLoading, vm.state.value.connection)

        repo.health = Outcome.Failure(AppError.Offline)
        advanceTimeBy(AppConfig.HEALTH_INTERVAL_MS)
        runCurrent()
        assertEquals(ConnectionStatus.Offline, vm.state.value.connection)

        settings.flow.value = ServerSettings()
        runCurrent()
        assertEquals(ConnectionStatus.NotConfigured, vm.state.value.connection)

        val calls = repo.healthCalls
        vm.stopHealthMonitor()
        settings.flow.value = ServerSettings("https://y.trycloudflare.com", "k")
        advanceTimeBy(5 * AppConfig.HEALTH_INTERVAL_MS)
        assertEquals("no health calls while the screen is stopped", calls, repo.healthCalls)
    }

    @Test
    fun captionIsCappedAt1000Chars() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onCaptionChange("a".repeat(1500))
        assertEquals(AppConfig.MAX_CAPTION_CHARS, vm.state.value.caption.length)
    }

    @Test
    fun analyzeAgainAndNewImage() = runTest(dispatcher) {
        repo.jobAnswers += Outcome.Success(JobState.Completed(result()))
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onCaptionChange("cap")
        vm.onAnalyze()
        runCurrent()
        assertTrue(vm.state.value.phase is AnalyzePhase.Success)

        vm.onAnalyzeAgain()
        runCurrent()
        assertEquals(2, repo.submitCalls)
        assertEquals(listOf("cap", "cap"), repo.submittedCaptions)

        vm.onNewImage()
        assertEquals(AnalyzePhase.Idle, vm.state.value.phase)
        assertNull(vm.state.value.image)
        assertEquals("", vm.state.value.caption)
        assertNull(saved.get<String>(AnalyzeViewModel.KEY_IMAGE_URI))
    }

    @Test
    fun removeImageReturnsToIdle() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.stopHealthMonitor()
        vm.onRemoveImage()
        assertEquals(AnalyzePhase.Idle, vm.state.value.phase)
        assertNull(vm.state.value.image)
    }
}
