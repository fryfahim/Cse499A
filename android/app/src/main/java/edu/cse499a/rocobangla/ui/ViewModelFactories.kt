package edu.cse499a.rocobangla.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import edu.cse499a.rocobangla.data.AppContainer
import edu.cse499a.rocobangla.ui.analyze.AnalyzeViewModel
import edu.cse499a.rocobangla.ui.settings.SettingsViewModel

object ViewModelFactories {
    fun analyze(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AnalyzeViewModel(
                repository = container.analysisRepository,
                settingsRepository = container.settingsRepository,
                imageInspector = container.imageInspector,
                savedState = createSavedStateHandle(),
            )
        }
    }

    fun settings(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer { SettingsViewModel(container.settingsRepository, container.analysisRepository) }
    }
}
