package edu.cse499a.rocobangla.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.core.ServerSettings
import edu.cse499a.rocobangla.core.ServerSettingsParser
import edu.cse499a.rocobangla.core.UrlCheck
import edu.cse499a.rocobangla.core.UrlProblem
import edu.cse499a.rocobangla.data.AnalysisRepository
import edu.cse499a.rocobangla.data.ModelStatus
import edu.cse499a.rocobangla.data.Outcome
import edu.cse499a.rocobangla.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionTest {
    data object NotRun : ConnectionTest
    data object Running : ConnectionTest

    /** Server reachable and key accepted. [modelStatus] tells whether it can analyze yet. */
    data class Passed(val modelStatus: ModelStatus, val queueLength: Int) : ConnectionTest
    data class Failed(val error: AppError) : ConnectionTest
}

enum class SettingsNotice { Saved, QrInvalid, ScannerUnavailable }

data class SettingsUiState(
    val url: String = "",
    val apiKey: String = "",
    val keyVisible: Boolean = false,
    val urlProblem: UrlProblem? = null,
    val keyMissing: Boolean = false,
    val test: ConnectionTest = ConnectionTest.NotRun,
    val notice: SettingsNotice? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val repository: AnalysisRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = settingsRepository.current()
            _state.update { it.copy(url = saved.baseUrl, apiKey = saved.apiKey) }
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value, urlProblem = null, test = ConnectionTest.NotRun) }

    fun onKeyChange(value: String) = _state.update { it.copy(apiKey = value, keyMissing = false, test = ConnectionTest.NotRun) }

    fun onToggleKeyVisible() = _state.update { it.copy(keyVisible = !it.keyVisible) }

    fun onSave() {
        viewModelScope.launch {
            if (validateAndSave() != null) _state.update { it.copy(notice = SettingsNotice.Saved) }
        }
    }

    /** Saves, then checks /health and that the key is accepted. */
    fun onTestConnection() {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            validateAndSave() ?: return@launch
            _state.update { it.copy(test = ConnectionTest.Running) }
            val result = when (val health = repository.health()) {
                is Outcome.Failure -> ConnectionTest.Failed(health.error)
                is Outcome.Success -> when (val key = repository.verifyKey()) {
                    is Outcome.Failure -> ConnectionTest.Failed(key.error)
                    is Outcome.Success -> ConnectionTest.Passed(health.value.status, health.value.queueLength)
                }
            }
            _state.update { it.copy(test = result) }
        }
    }

    fun onQrScanned(raw: String?) {
        val parsed = ServerSettingsParser.parseQr(raw)
        if (parsed == null) {
            _state.update { it.copy(notice = SettingsNotice.QrInvalid) }
            return
        }
        _state.update { it.copy(url = parsed.baseUrl, apiKey = parsed.apiKey, urlProblem = null, keyMissing = false) }
        onTestConnection()
    }

    fun onScannerUnavailable() = _state.update { it.copy(notice = SettingsNotice.ScannerUnavailable) }

    fun onNoticeShown() = _state.update { it.copy(notice = null) }

    private suspend fun validateAndSave(): ServerSettings? {
        val s = _state.value
        val urlCheck = ServerSettingsParser.checkUrl(s.url)
        val key = s.apiKey.trim()
        val problem = (urlCheck as? UrlCheck.Invalid)?.problem
        if (problem != null || key.isEmpty()) {
            _state.update { it.copy(urlProblem = problem, keyMissing = key.isEmpty()) }
            return null
        }
        val settings = ServerSettings((urlCheck as UrlCheck.Valid).url, key)
        settingsRepository.save(settings)
        _state.update { it.copy(url = settings.baseUrl, apiKey = settings.apiKey) }
        return settings
    }
}
