package edu.cse499a.rocobangla.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.settingsDataStore by preferencesDataStore(name = "server_settings")

/** Manual dependency injection: one instance per process, held by the Application. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(appContext.settingsDataStore)

    val imageInspector: ImageInspector = ContentResolverImageInspector(appContext.contentResolver)

    val analysisRepository: AnalysisRepository = NetworkAnalysisRepository(
        settingsRepository = settingsRepository,
        apiFactory = RocoApiFactory(defaultOkHttpClient()),
        uploadBodyFactory = ContentUriUploadBodyFactory(appContext.contentResolver),
    )
}
