package edu.cse499a.rocobangla.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ErrorMappingTest {

    @Test
    fun everyServerCodeHasAnError() {
        val expected = mapOf(
            "INVALID_IMAGE" to AppError.InvalidImage,
            "TOO_LARGE" to AppError.TooLarge,
            "MODEL_NOT_READY" to AppError.ModelNotReady,
            "QUEUE_FULL" to AppError.QueueFull,
            "EMPTY_OUTPUT" to AppError.EmptyOutput,
            "INFERENCE_FAILED" to AppError.InferenceFailed,
            "UNAUTHORIZED" to AppError.Unauthorized,
            "NOT_FOUND" to AppError.JobNotFound,
        )
        expected.forEach { (code, error) -> assertEquals(code, error, ErrorMapping.fromServerCode(code, 400)) }
    }

    @Test
    fun statusWithoutOurBodyMeansTunnelOrColabIsGone() {
        assertEquals(AppError.Offline, ErrorMapping.fromServerCode(null, 530)) // Cloudflare: origin unreachable
        assertEquals(AppError.Offline, ErrorMapping.fromServerCode(null, 502))
        assertEquals(AppError.Offline, ErrorMapping.fromServerCode(null, 404))
        assertEquals(AppError.Unauthorized, ErrorMapping.fromServerCode(null, 401))
        assertEquals(AppError.QueueFull, ErrorMapping.fromServerCode(null, 429))
        assertEquals(AppError.ServerError, ErrorMapping.fromServerCode(null, 500))
        assertEquals(AppError.ServerError, ErrorMapping.fromServerCode("SOMETHING_NEW", 418))
    }

    @Test
    fun networkFailuresAreOffline() {
        listOf(IOException(), UnknownHostException(), SocketTimeoutException(), SSLHandshakeException("x"))
            .forEach { assertEquals(AppError.Offline, ErrorMapping.fromThrowable(it)) }
        assertEquals(AppError.ServerError, ErrorMapping.fromThrowable(IllegalArgumentException("not json")))
    }

    @Test
    fun everyErrorOffersANextStep() {
        AppError.entries.forEach { assertTrue(it.name, ErrorMapping.actionsFor(it).isNotEmpty()) }
        assertEquals(listOf(ErrorAction.OpenSettings), ErrorMapping.actionsFor(AppError.Unauthorized))
        assertEquals(listOf(ErrorAction.PickAnotherImage), ErrorMapping.actionsFor(AppError.InvalidImage))
        assertEquals(listOf(ErrorAction.Retry, ErrorAction.OpenSettings), ErrorMapping.actionsFor(AppError.Offline))
        assertEquals(listOf(ErrorAction.Retry), ErrorMapping.actionsFor(AppError.Timeout))
    }
}
