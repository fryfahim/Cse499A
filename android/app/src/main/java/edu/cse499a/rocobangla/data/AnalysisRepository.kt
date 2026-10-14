package edu.cse499a.rocobangla.data

/** Talks to the Colab server. Every call returns an [Outcome]; nothing throws
 *  except coroutine cancellation. */
interface AnalysisRepository {
    suspend fun health(): Outcome<ServerHealth>
    suspend fun submit(image: PickedImage, caption: String): Outcome<JobTicket>
    suspend fun job(jobId: String): Outcome<JobState>

    /** Succeeds when the server accepts the stored API key. */
    suspend fun verifyKey(): Outcome<Unit>
}
