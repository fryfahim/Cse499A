package edu.cse499a.rocobangla.data

import edu.cse499a.rocobangla.FakeSettingsRepository
import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.core.ServerSettings
import edu.cse499a.rocobangla.picked
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Repository against a local MockWebServer (plain http is only allowed here, in tests). */
class NetworkAnalysisRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: FakeSettingsRepository
    private lateinit var repo: NetworkAnalysisRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        settings = FakeSettingsRepository(ServerSettings(server.url("/").toString().trimEnd('/'), "secret-key"))
        repo = NetworkAnalysisRepository(
            settingsRepository = settings,
            apiFactory = RocoApiFactory(OkHttpClient()),
            uploadBodyFactory = { "JPEGBYTES".toByteArray().toRequestBody("image/jpeg".toMediaType()) },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun submitSendsMultipartWithKeyAndCaption() = runTest {
        server.enqueue(json(202, """{"job_id":"abc","status":"queued","position":1}"""))
        val r = repo.submit(picked(), "  left pneumothorax ")
        assertEquals(Outcome.Success(JobTicket("abc", 1)), r)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/jobs", req.path)
        assertEquals("secret-key", req.getHeader("X-API-Key"))
        val body = req.body.readUtf8()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(body.contains("name=\"image\"; filename=\"xray.jpg\""))
        assertTrue(body.contains("JPEGBYTES"))
        assertTrue(body.contains("name=\"caption\""))
        assertTrue(body.contains("\r\n\r\nleft pneumothorax\r\n"))
    }

    @Test
    fun fileNameIsMadeAsciiSafe() {
        assertEquals("______-___.jpg", safeFileName("এক্সরে-ছবি.jpg"))
        assertEquals("xray_1.png", safeFileName("xray 1.png"))
        assertEquals("image", safeFileName(""))
    }

    @Test
    fun emptyCaptionIsNotSent() = runTest {
        server.enqueue(json(202, """{"job_id":"abc","status":"queued","position":1}"""))
        repo.submit(picked(), "   ")
        assertTrue(!server.takeRequest().body.readUtf8().contains("name=\"caption\""))
    }

    @Test
    fun errorBodiesAreMapped() = runTest {
        server.enqueue(json(429, """{"error":{"code":"QUEUE_FULL","message":"full"}}"""))
        assertEquals(Outcome.Failure(AppError.QueueFull), repo.submit(picked(), ""))
        server.enqueue(json(503, """{"error":{"code":"MODEL_NOT_READY","message":"x"}}"""))
        assertEquals(Outcome.Failure(AppError.ModelNotReady), repo.submit(picked(), ""))
        server.enqueue(MockResponse().setResponseCode(530).setBody("<html>Cloudflare tunnel error</html>"))
        assertEquals(Outcome.Failure(AppError.Offline), repo.job("abc"))
    }

    @Test
    fun oversizedImageIsNeverUploaded() = runTest {
        assertEquals(Outcome.Failure(AppError.TooLarge), repo.submit(picked(size = 10L * 1024 * 1024 + 1), ""))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun pollParsesJob() = runTest {
        server.enqueue(json(200, """{"job_id":"abc","status":"queued","position":2,"result":null,"error":null}"""))
        assertEquals(Outcome.Success(JobState.Queued(2)), repo.job("abc"))
        assertEquals("/api/v1/jobs/abc", server.takeRequest().path)
    }

    @Test
    fun healthNeedsNoKeyButKeyCheckDoes() = runTest {
        server.enqueue(json(200, """{"status":"ready","model":"m","queue_length":0,"busy":false,"version":"1.0.0"}"""))
        assertEquals(Outcome.Success(ServerHealth(ModelStatus.Ready, 0, false)), repo.health())

        server.enqueue(json(404, """{"error":{"code":"NOT_FOUND","message":"x"}}"""))
        assertEquals(Outcome.Success(Unit), repo.verifyKey())
        server.enqueue(json(401, """{"error":{"code":"UNAUTHORIZED","message":"x"}}"""))
        assertEquals(Outcome.Failure(AppError.Unauthorized), repo.verifyKey())
    }

    @Test
    fun nonJsonSuccessIsServerError() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not our server</html>"))
        assertEquals(Outcome.Failure(AppError.ServerError), repo.health())
    }

    @Test
    fun unreachableServerIsOffline() = runTest {
        server.shutdown()
        assertEquals(Outcome.Failure(AppError.Offline), repo.health())
    }

    @Test
    fun notConfiguredMakesNoRequest() = runTest {
        settings.flow.value = ServerSettings()
        assertEquals(Outcome.Failure(AppError.NotConfigured), repo.health())
        assertEquals(0, server.requestCount)
    }
}
