package edu.cse499a.rocobangla.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface RocoApi {
    @GET("health")
    suspend fun health(): Response<HealthDto>

    @Multipart
    @POST("api/v1/jobs")
    suspend fun createJob(
        @Header("X-API-Key") apiKey: String,
        @Part image: MultipartBody.Part,
        @Part("caption") caption: RequestBody?,
    ): Response<JobCreatedDto>

    @GET("api/v1/jobs/{jobId}")
    suspend fun getJob(
        @Header("X-API-Key") apiKey: String,
        @Path("jobId") jobId: String,
    ): Response<JobDto>
}

val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Free ngrok domains can answer with an HTML warning page instead of
 *  forwarding the request; this header tells ngrok to skip it. Harmless
 *  for other tunnels. */
const val NGROK_SKIP_WARNING_HEADER = "ngrok-skip-browser-warning"

fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header(NGROK_SKIP_WARNING_HEADER, "true").build())
    }
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()

/** The tunnel URL changes with every Colab restart, so the Retrofit instance
 *  is built per base URL and cached until the URL changes. */
class RocoApiFactory(
    private val client: OkHttpClient,
    private val json: Json = ApiJson,
) {
    private var cached: Pair<String, RocoApi>? = null

    @Synchronized
    fun forBaseUrl(baseUrl: String): RocoApi {
        cached?.let { (url, api) -> if (url == baseUrl) return api }
        val api = Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RocoApi::class.java)
        cached = baseUrl to api
        return api
    }
}
