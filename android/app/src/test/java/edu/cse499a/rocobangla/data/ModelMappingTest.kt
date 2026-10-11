package edu.cse499a.rocobangla.data

import edu.cse499a.rocobangla.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelMappingTest {

    private val json = ApiJson

    @Test
    fun completedJobKeepsTextExactly() {
        val text = "এটি বুকের এক্স-রে।  বাম দিকে বাতাস জমেছে।"
        val dto = json.decodeFromString(
            JobDto.serializer(),
            """{"job_id":"j","status":"completed","position":0,"result":{"bangla_text":"$text",
               "mode":"caption","caption_used":"left pneumothorax",
               "side_check":{"level":"warning","message":"WARNING: left/right"},
               "disclaimer_bn":"ডাক্তার দেখান","elapsed_ms":61234},"error":null}""",
        )
        val state = dto.toModel() as JobState.Completed
        assertEquals(text, state.result.banglaText)
        assertEquals(AnalysisMode.Caption, state.result.mode)
        assertEquals(SideCheckLevel.Warning, state.result.sideCheck.level)
        assertEquals(61234L, state.result.elapsedMs)
    }

    @Test
    fun statusesAndErrors() {
        assertEquals(JobState.Queued(3), JobDto("j", "queued", 3).toModel())
        assertEquals(JobState.Running, JobDto("j", "running").toModel())
        assertEquals(JobState.Failed(AppError.EmptyOutput), JobDto("j", "failed", error = ErrorDto("EMPTY_OUTPUT")).toModel())
        assertEquals(JobState.Failed(AppError.ServerError), JobDto("j", "completed").toModel())
        assertEquals(JobState.Failed(AppError.ServerError), JobDto("j", "weird").toModel())
    }

    @Test
    fun unknownSideCheckLevelIsNotChecked() {
        val r = ResultDto("t", "image_only", "", SideCheckDto("maybe"), "d").toModel()
        assertEquals(SideCheckLevel.NotChecked, r.sideCheck.level)
        assertEquals(AnalysisMode.ImageOnly, r.mode)
    }

    @Test
    fun healthMapping() {
        val h = json.decodeFromString(HealthDto.serializer(),
            """{"status":"ready","model":"m","queue_length":2,"busy":true,"version":"1.0.0","extra":1}""")
        assertEquals(ServerHealth(ModelStatus.Ready, 2, true), h.toModel())
        assertEquals(ModelStatus.Loading, HealthDto("loading").toModel().status)
        assertEquals(ModelStatus.Error, HealthDto("error").toModel().status)
    }
}
