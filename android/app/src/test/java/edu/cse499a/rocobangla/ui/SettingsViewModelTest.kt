package edu.cse499a.rocobangla.ui

import edu.cse499a.rocobangla.FakeAnalysisRepository
import edu.cse499a.rocobangla.FakeSettingsRepository
import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.core.ServerSettings
import edu.cse499a.rocobangla.core.UrlProblem
import edu.cse499a.rocobangla.data.ModelStatus
import edu.cse499a.rocobangla.data.Outcome
import edu.cse499a.rocobangla.ui.settings.ConnectionTest
import edu.cse499a.rocobangla.ui.settings.SettingsNotice
import edu.cse499a.rocobangla.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeAnalysisRepository()
    private val settings = FakeSettingsRepository(ServerSettings())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun refusesHttpUrlAndMissingKey() = runTest(dispatcher) {
        val vm = SettingsViewModel(settings, repo)
        runCurrent()
        vm.onUrlChange("http://abc.trycloudflare.com")
        vm.onSave()
        runCurrent()
        assertEquals(UrlProblem.NotHttps, vm.state.value.urlProblem)
        assertEquals(true, vm.state.value.keyMissing)
        assertEquals(0, settings.saveCalls)
    }

    @Test
    fun qrScanSavesAndTests() = runTest(dispatcher) {
        val vm = SettingsViewModel(settings, repo)
        runCurrent()
        vm.onQrScanned("""{"url":"https://abc.trycloudflare.com","key":"k1"}""")
        runCurrent()
        assertEquals(ServerSettings("https://abc.trycloudflare.com", "k1"), settings.flow.value)
        assertEquals(ConnectionTest.Passed(ModelStatus.Ready, 0), vm.state.value.test)
    }

    @Test
    fun badQrShowsNotice() = runTest(dispatcher) {
        val vm = SettingsViewModel(settings, repo)
        vm.onQrScanned("hello")
        assertEquals(SettingsNotice.QrInvalid, vm.state.value.notice)
        assertEquals(0, settings.saveCalls)
    }

    @Test
    fun wrongKeyFailsTheTest() = runTest(dispatcher) {
        repo.keyResult = Outcome.Failure(AppError.Unauthorized)
        val vm = SettingsViewModel(settings, repo)
        runCurrent()
        vm.onUrlChange("https://abc.trycloudflare.com/")
        vm.onKeyChange("bad")
        vm.onTestConnection()
        runCurrent()
        assertEquals(ConnectionTest.Failed(AppError.Unauthorized), vm.state.value.test)
        assertEquals("https://abc.trycloudflare.com", vm.state.value.url)
    }

    @Test
    fun offlineServerFailsTheTest() = runTest(dispatcher) {
        repo.health = Outcome.Failure(AppError.Offline)
        val vm = SettingsViewModel(settings, repo)
        runCurrent()
        vm.onUrlChange("https://abc.trycloudflare.com")
        vm.onKeyChange("k")
        vm.onTestConnection()
        runCurrent()
        assertEquals(ConnectionTest.Failed(AppError.Offline), vm.state.value.test)
    }
}
