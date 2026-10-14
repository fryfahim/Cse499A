package edu.cse499a.rocobangla.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire format of the Colab API. See API.md.

@Serializable
data class HealthDto(
    val status: String,
    val model: String = "",
    @SerialName("queue_length") val queueLength: Int = 0,
    val busy: Boolean = false,
    val version: String = "",
)

@Serializable
data class JobCreatedDto(
    @SerialName("job_id") val jobId: String,
    val status: String,
    val position: Int = 0,
)

@Serializable
data class JobDto(
    @SerialName("job_id") val jobId: String,
    val status: String,
    val position: Int = 0,
    val result: ResultDto? = null,
    val error: ErrorDto? = null,
)

@Serializable
data class ResultDto(
    @SerialName("bangla_text") val banglaText: String,
    val mode: String,
    @SerialName("caption_used") val captionUsed: String = "",
    @SerialName("side_check") val sideCheck: SideCheckDto,
    @SerialName("disclaimer_bn") val disclaimerBn: String,
    @SerialName("elapsed_ms") val elapsedMs: Long = 0,
)

@Serializable
data class SideCheckDto(val level: String, val message: String = "")

@Serializable
data class ErrorDto(val code: String, val message: String = "")

@Serializable
data class ErrorEnvelopeDto(val error: ErrorDto? = null)
