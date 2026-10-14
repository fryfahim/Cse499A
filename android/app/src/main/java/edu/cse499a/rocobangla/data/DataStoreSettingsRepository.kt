package edu.cse499a.rocobangla.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import edu.cse499a.rocobangla.core.ServerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Stores the URL and key in app-private storage (excluded from backups). */
class DataStoreSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    override val settings: Flow<ServerSettings> = dataStore.data.map { prefs ->
        ServerSettings(baseUrl = prefs[KEY_URL].orEmpty(), apiKey = prefs[KEY_API_KEY].orEmpty())
    }

    override suspend fun current(): ServerSettings = settings.first()

    override suspend fun save(settings: ServerSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_URL] = settings.baseUrl
            prefs[KEY_API_KEY] = settings.apiKey
        }
    }

    private companion object {
        val KEY_URL = stringPreferencesKey("server_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
    }
}
