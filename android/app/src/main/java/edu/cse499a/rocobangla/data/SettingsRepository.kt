package edu.cse499a.rocobangla.data

import edu.cse499a.rocobangla.core.ServerSettings
import kotlinx.coroutines.flow.Flow

/** Server URL and API key, entered or scanned by the user. Never built into the APK. */
interface SettingsRepository {
    val settings: Flow<ServerSettings>
    suspend fun current(): ServerSettings
    suspend fun save(settings: ServerSettings)
}
