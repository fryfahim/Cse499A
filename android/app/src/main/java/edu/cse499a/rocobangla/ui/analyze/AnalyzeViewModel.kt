package edu.cse499a.rocobangla.ui.analyze

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.cse499a.rocobangla.core.AppConfig
import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.data.AnalysisRepository
import edu.cse499a.rocobangla.data.ImageInspector
import edu.cse499a.rocobangla.data.JobState
import edu.cse499a.rocobangla.data.ModelStatus
import edu.cse499a.rocobangla.data.Outcome
import edu.cse499a.rocobangla.data.PickedImage
import edu.cse499a.rocobangla.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AnalyzeViewModel(
    private val repository: AnalysisRepository,
    private val settingsRepository: SettingsRepository,
    private val imageInspector: ImageInspector,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(AnalyzeUiState(caption = savedState[KEY_CAPTION] ?: ""))
    val state: StateFlow<AnalyzeUiState> = _state.asStateFlow()

    private var workJob: Job? = null
    private var healthJob: Job? = null

    init {
        savedState.get<String>(KEY_IMAGE_URI)?.let(::restoreImage)
        val jobId = savedState.get<String>(KEY_JOB_ID)
        if (jobId != null) {
            // Rotation or process restore while waiting: resume polling, never resubmit.
            val startedAt = savedState.get<Long>(KEY_STARTED_AT) ?: clock()
            _state.update { it.copy(phase = AnalyzePhase.Queued(jobId, 0, startedAt)) }
            startPolling(jobId, startedAt)
        }
    }

    // ---- connection monitor (started/stopped with the screen) ---------------

    fun startHealthMonitor() {
        if (healthJob?.isActive == true) return
        healthJob = viewModelScope.launch {
            settingsRepository.settings.distinctUntilChanged().collectLatest { settings ->
                if (!settings.isConfigured) {
                    _state.update { it.copy(connection = ConnectionStatus.NotConfigured) }
                    return@collectLatest
                }
                _state.update { it.copy(connection = ConnectionStatus.Checking) }
                while (isActive) {
                    refreshHealthOnce()
                    delay(AppConfig.HEALTH_INTERVAL_MS)
                }
            }
        }
    }

    fun stopHealthMonitor() {
        healthJob?.cancel()
        healthJob = null
    }

    private suspend fun refreshHealthOnce() {
        val status = when (val r = repository.health()) {
            is Outcome.Success -> when (r.value.status) {
                ModelStatus.Ready ->
                    if (r.value.busy || r.value.queueLength > 0) {
                        ConnectionStatus.Busy(r.value.queueLength + if (r.value.busy) 1 else 0)
                    } else {
                        ConnectionStatus.Ready
                    }
                ModelStatus.Loading -> ConnectionStatus.ModelLoading
                ModelStatus.Error -> ConnectionStatus.ModelError
            }
            is Outcome.Failure ->
                if (r.error == AppError.NotConfigured) ConnectionStatus.NotConfigured else ConnectionStatus.Offline
        }
        _state.update { it.copy(connection = status) }
    }

    // ---- image and caption ---------------------------------------------------

    fun onImagePicked(uri: String?) {
        if (uri == null || _state.value.isWorking) return // picker cancelled, or a job is running
        viewModelScope.launch {
            when (val r = imageInspector.inspect(uri)) {
                is Outcome.Success -> selectImage(r.value)
                is Outcome.Failure -> _state.update { it.copy(imageError = r.error) }
            }
        }
    }

    private fun selectImage(image: PickedImage) {
        val problem = when {
            image.sizeBytes > AppConfig.MAX_IMAGE_BYTES -> AppError.TooLarge
            image.mimeType != null && image.mimeType !in AppConfig.SUPPORTED_MIME_TYPES -> AppError.UnsupportedImageType
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(imageError = problem) }
            return
        }
        savedState[KEY_IMAGE_URI] = image.uri
        _state.update { it.copy(image = image, imageError = null, phase = AnalyzePhase.ImageSelected) }
    }

    private fun restoreImage(uri: String) {
        viewModelScope.launch {
            val r = imageInspector.inspect(uri)
            if (r is Outcome.Success) {
                _state.update {
                    it.copy(image = r.value, phase = if (it.phase == AnalyzePhase.Idle) AnalyzePhase.ImageSelected else it.phase)
                }
            } else {
                savedState.remove<String>(KEY_IMAGE_URI) // permission to read it ended with the old process
            }
        }
    }

    fun onRemoveImage() {
        if (_state.value.isWorking) return
        savedState.remove<String>(KEY_IMAGE_URI)
        _state.update { it.copy(image = null, imageError = null, phase = AnalyzePhase.Idle) }
    }

    fun onCaptionChange(text: String) {
        val caption = text.take(AppConfig.MAX_CAPTION_CHARS)
        savedState[KEY_CAPTION] = caption
        _state.update { it.copy(caption = caption) }
    }

    // ---- analysis --------------------------------------------------------------

    /** Upload only happens here, after an explicit tap. Repeated taps are ignored. */
    fun onAnalyze() {
        if (!_state.value.canAnalyze) return
        submit()
    }

    /** Retry after an error: always a new job. Skips the cached chip state,
     *  because the server may be back even if the last health check failed. */
    fun onRetry() {
        val s = _state.value
        if (s.phase !is AnalyzePhase.Error || s.image == null) return
        submit()
    }

    /** "Analyze again" on the result screen: same image and caption, new job. */
    fun onAnalyzeAgain() {
        val s = _state.value
        if (s.phase !is AnalyzePhase.Success || s.image == null) return
        submit()
    }

    private fun submit() {
        val image = _state.value.image ?: return
        val caption = _state.value.caption.trim()
        _state.update { it.copy(phase = AnalyzePhase.Submitting, notice = null) } // synchronous: debounces taps
        workJob?.cancel()
        workJob = viewModelScope.launch {
            when (val r = repository.submit(image, caption)) {
                is Outcome.Success -> {
                    val startedAt = clock()
                    savedState[KEY_JOB_ID] = r.value.jobId
                    savedState[KEY_STARTED_AT] = startedAt
                    _state.update { it.copy(phase = AnalyzePhase.Queued(r.value.jobId, r.value.position, startedAt)) }
                    startPolling(r.value.jobId, startedAt)
                }
                is Outcome.Failure -> fail(r.error)
            }
        }
    }

    private fun startPolling(jobId: String, startedAt: Long) {
        workJob?.cancel()
        workJob = viewModelScope.launch {
            var failuresInARow = 0
            while (isActive) {
                if (clock() - startedAt >= AppConfig.POLL_TIMEOUT_MS) {
                    fail(AppError.Timeout)
                    return@launch
                }
                when (val r = repository.job(jobId)) {
                    is Outcome.Success -> {
                        failuresInARow = 0
                        when (val job = r.value) {
                            is JobState.Queued ->
                                _state.update { it.copy(phase = AnalyzePhase.Queued(jobId, job.position, startedAt)) }
                            JobState.Running ->
                                _state.update { it.copy(phase = AnalyzePhase.Running(jobId, startedAt)) }
                            is JobState.Completed -> {
                                clearJob()
                                _state.update { it.copy(phase = AnalyzePhase.Success(job.result, clock())) }
                                return@launch
                            }
                            is JobState.Failed -> {
                                fail(job.error)
                                return@launch
                            }
                        }
                    }
                    is Outcome.Failure -> {
                        failuresInARow++
                        val transient = r.error == AppError.Offline || r.error == AppError.ServerError
                        if (!transient || failuresInARow >= AppConfig.MAX_CONSECUTIVE_POLL_FAILURES) {
                            fail(r.error)
                            return@launch
                        }
                    }
                }
                delay(AppConfig.POLL_INTERVAL_MS)
            }
        }
    }

    private fun fail(error: AppError) {
        clearJob()
        _state.update {
            it.copy(
                phase = AnalyzePhase.Error(error),
                connection = if (error == AppError.Offline) ConnectionStatus.Offline else it.connection,
            )
        }
    }

    /** Stops waiting on this device. The server may still finish the job. */
    fun onCancelWaiting() {
        if (!_state.value.isWorking) return
        workJob?.cancel()
        workJob = null
        clearJob()
        _state.update {
            it.copy(
                phase = if (it.image != null) AnalyzePhase.ImageSelected else AnalyzePhase.Idle,
                notice = Notice.StoppedWaiting,
            )
        }
    }

    fun onDismissError() {
        if (_state.value.phase !is AnalyzePhase.Error) return
        _state.update { it.copy(phase = if (it.image != null) AnalyzePhase.ImageSelected else AnalyzePhase.Idle) }
    }

    /** Back from the result keeps the image and caption. */
    fun onCloseResult() {
        if (_state.value.phase !is AnalyzePhase.Success) return
        _state.update { it.copy(phase = if (it.image != null) AnalyzePhase.ImageSelected else AnalyzePhase.Idle) }
    }

    fun onNewImage() {
        if (_state.value.isWorking) return
        clearJob()
        savedState.remove<String>(KEY_IMAGE_URI)
        savedState.remove<String>(KEY_CAPTION)
        _state.update { AnalyzeUiState(connection = it.connection) }
    }

    fun onNoticeShown() {
        _state.update { it.copy(notice = null) }
    }

    private fun clearJob() {
        savedState.remove<String>(KEY_JOB_ID)
        savedState.remove<Long>(KEY_STARTED_AT)
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_STARTED_AT = "job_started_at"
        const val KEY_IMAGE_URI = "image_uri"
        const val KEY_CAPTION = "caption"
    }
}
