package edu.cse499a.rocobangla.core

/** App-wide limits and timings. They match the server contract in API.md. */
object AppConfig {
    const val MAX_IMAGE_BYTES: Long = 10L * 1024 * 1024
    const val MAX_CAPTION_CHARS: Int = 1000

    const val POLL_INTERVAL_MS: Long = 2_000
    const val POLL_TIMEOUT_MS: Long = 5 * 60_000
    /** A single failed poll is retried; this many failures in a row end the wait. */
    const val MAX_CONSECUTIVE_POLL_FAILURES: Int = 3

    const val HEALTH_INTERVAL_MS: Long = 10_000

    val SUPPORTED_MIME_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")

    /** Used by "Test connection" to check the API key: an authenticated GET
     *  for a job that cannot exist answers 404 (key accepted) or 401. */
    const val KEY_CHECK_JOB_ID: String = "00000000-0000-0000-0000-000000000000"
}
