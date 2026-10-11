package edu.cse499a.rocobangla.data

import edu.cse499a.rocobangla.core.AppConfig
import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.core.ErrorMapping
import edu.cse499a.rocobangla.core.ServerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

class NetworkAnalysisRepository(
    private val settingsRepository: SettingsRepository,
    private val apiFactory: RocoApiFactory,
    private val uploadBodyFactory: UploadBodyFactory,
    private val json: Json = ApiJson,
) : AnalysisRepository {

    override suspend fun health(): Outcome<ServerHealth> =
        call { api, _ -> api.health() }.map { it.toModel() }

    override suspend fun submit(image: PickedImage, caption: String): Outcome<JobTicket> {
        if (image.sizeBytes > AppConfig.MAX_IMAGE_BYTES) return Outcome.Failure(AppError.TooLarge)
        return call { api, settings ->
            val part = MultipartBody.Part.createFormData(
                "image",
                safeFileName(image.displayName),
                uploadBodyFactory.create(image),
            )
            val captionBody = caption.trim().takeIf { it.isNotEmpty() }
                ?.toRequestBody("text/plain; charset=utf-8".toMediaType())
            api.createJob(settings.apiKey, part, captionBody)
        }.map { JobTicket(it.jobId, it.position) }
    }

    override suspend fun job(jobId: String): Outcome<JobState> =
        call { api, settings -> api.getJob(settings.apiKey, jobId) }.map { it.toModel() }

    override suspend fun verifyKey(): Outcome<Unit> =
        when (val r = call { api, s -> api.getJob(s.apiKey, AppConfig.KEY_CHECK_JOB_ID) }) {
            // 404 NOT_FOUND means the key was accepted and the fake job does not exist.
            is Outcome.Failure -> if (r.error == AppError.JobNotFound) Outcome.Success(Unit) else r
            is Outcome.Success -> Outcome.Success(Unit)
        }

    private suspend fun <T> call(block: suspend (RocoApi, ServerSettings) -> Response<T>): Outcome<T> {
        val settings = settingsRepository.current()
        if (!settings.isConfigured) return Outcome.Failure(AppError.NotConfigured)
        return try {
            val api = apiFactory.forBaseUrl(settings.baseUrl)
            val response = block(api, settings)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Outcome.Success(body)
            } else {
                Outcome.Failure(ErrorMapping.fromServerCode(errorCode(response), response.code()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException -> Offline; a bad base URL or a body that is not our JSON -> ServerError.
            Outcome.Failure(ErrorMapping.fromThrowable(e))
        }
    }

    private fun errorCode(response: Response<*>): String? = try {
        response.errorBody()?.string()?.let { json.decodeFromString(ErrorEnvelopeDto.serializer(), it).error?.code }
    } catch (e: Exception) {
        null
    }
}

/** The file name only labels the multipart part (the server ignores it), so
 *  keep it to safe ASCII rather than send arbitrary text in a header. */
internal fun safeFileName(name: String): String {
    val cleaned = name.map { c -> if (c.isLetterOrDigit() && c.code < 128 || c in "._-") c else '_' }
        .joinToString("")
        .take(100)
    return cleaned.ifBlank { "image" }
}
