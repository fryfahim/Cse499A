package edu.cse499a.rocobangla

import edu.cse499a.rocobangla.core.AppError
import edu.cse499a.rocobangla.core.ServerSettings
import edu.cse499a.rocobangla.data.AnalysisMode
import edu.cse499a.rocobangla.data.AnalysisRepository
import edu.cse499a.rocobangla.data.AnalysisResult
import edu.cse499a.rocobangla.data.ImageInspector
import edu.cse499a.rocobangla.data.JobState
import edu.cse499a.rocobangla.data.JobTicket
import edu.cse499a.rocobangla.data.ModelStatus
import edu.cse499a.rocobangla.data.Outcome
import edu.cse499a.rocobangla.data.PickedImage
import edu.cse499a.rocobangla.data.ServerHealth
import edu.cse499a.rocobangla.data.SettingsRepository
import edu.cse499a.rocobangla.data.SideCheck
import edu.cse499a.rocobangla.data.SideCheckLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fake server: scripted answers, records calls. */
class FakeAnalysisRepository : AnalysisRepository {
    var health: Outcome<ServerHealth> = Outcome.Success(ServerHealth(ModelStatus.Ready, 0, false))
    var submitResult: Outcome<JobTicket> = Outcome.Success(JobTicket("job-1", 1))
    var keyResult: Outcome<Unit> = Outcome.Success(Unit)

    /** Answers for successive polls; the last one repeats. */
    val jobAnswers = ArrayDeque<Outcome<JobState>>()

    var healthCalls = 0
    var submitCalls = 0
    var jobCalls = 0
    val submittedCaptions = mutableListOf<String>()

    override suspend fun health(): Outcome<ServerHealth> {
        healthCalls++
        return health
    }

    override suspend fun submit(image: PickedImage, caption: String): Outcome<JobTicket> {
        submitCalls++
        submittedCaptions += caption
        return submitResult
    }

    override suspend fun job(jobId: String): Outcome<JobState> {
        jobCalls++
        return if (jobAnswers.size > 1) jobAnswers.removeFirst() else jobAnswers.first()
    }

    override suspend fun verifyKey(): Outcome<Unit> = keyResult
}

class FakeSettingsRepository(initial: ServerSettings = ServerSettings("https://x.trycloudflare.com", "key")) :
    SettingsRepository {
    val flow = MutableStateFlow(initial)
    override val settings: StateFlow<ServerSettings> = flow
    var saveCalls = 0
    override suspend fun current(): ServerSettings = flow.value
    override suspend fun save(settings: ServerSettings) {
        saveCalls++
        flow.value = settings
    }
}

class FakeImageInspector : ImageInspector {
    val images = mutableMapOf<String, Outcome<PickedImage>>()
    override suspend fun inspect(uri: String): Outcome<PickedImage> =
        images[uri] ?: Outcome.Failure(AppError.ImageUnreadable)
}

fun picked(
    uri: String = "content://media/1",
    size: Long = 200_000,
    mime: String? = "image/jpeg",
) = PickedImage(uri, "xray.jpg", size, mime, 1024, 768)

fun result(level: SideCheckLevel = SideCheckLevel.Ok) = AnalysisResult(
    banglaText = "এটি বুকের এক্স-রে। বাম ফুসফুসে সমস্যা দেখা যাচ্ছে।",
    mode = AnalysisMode.Caption,
    captionUsed = "left pneumothorax",
    sideCheck = SideCheck(level, "msg"),
    disclaimerBn = "এটি শুধু ছবি বোঝার জন্য সাহায্য।",
    elapsedMs = 4321,
)
