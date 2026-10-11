package edu.cse499a.rocobangla.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** Where the Colab server is and the per-session API key. */
data class ServerSettings(val baseUrl: String = "", val apiKey: String = "") {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}

enum class UrlProblem { Empty, NotHttps, Malformed }

sealed interface UrlCheck {
    data class Valid(val url: String) : UrlCheck
    data class Invalid(val problem: UrlProblem) : UrlCheck
}

object ServerSettingsParser {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class QrPayload(val url: String, val key: String)

    /** Trims, requires https, and drops trailing slashes. Plain http is
     *  refused: the app never sends traffic in cleartext. */
    fun checkUrl(input: String): UrlCheck {
        val raw = input.trim()
        if (raw.isEmpty()) return UrlCheck.Invalid(UrlProblem.Empty)
        if (!raw.startsWith("https://", ignoreCase = true)) return UrlCheck.Invalid(UrlProblem.NotHttps)
        val trimmed = raw.trimEnd('/')
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return UrlCheck.Invalid(UrlProblem.Malformed)
        }
        if (uri.host.isNullOrBlank() || uri.query != null || uri.fragment != null) {
            return UrlCheck.Invalid(UrlProblem.Malformed)
        }
        return UrlCheck.Valid("https://" + trimmed.substring("https://".length))
    }

    /** Parses the QR code printed by the Colab cell: {"url": "...", "key": "..."}. */
    fun parseQr(raw: String?): ServerSettings? {
        if (raw.isNullOrBlank()) return null
        val payload = try {
            json.decodeFromString(QrPayload.serializer(), raw.trim())
        } catch (e: Exception) {
            return null
        }
        val url = (checkUrl(payload.url) as? UrlCheck.Valid)?.url ?: return null
        val key = payload.key.trim()
        if (key.isEmpty()) return null
        return ServerSettings(url, key)
    }
}
