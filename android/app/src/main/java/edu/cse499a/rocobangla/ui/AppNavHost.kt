package edu.cse499a.rocobangla.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import edu.cse499a.rocobangla.data.AppContainer
import edu.cse499a.rocobangla.ui.analyze.AnalyzeRoute
import edu.cse499a.rocobangla.ui.analyze.AnalyzeViewModel
import edu.cse499a.rocobangla.ui.settings.SettingsRoute
import edu.cse499a.rocobangla.ui.settings.SettingsViewModel
import edu.cse499a.rocobangla.ui.theme.LocalReduceMotion

private object Routes {
    const val ANALYZE = "analyze"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val reduceMotion = LocalReduceMotion.current
    val enter: EnterTransition = if (reduceMotion) EnterTransition.None else fadeIn(tween(200))
    val exit: ExitTransition = if (reduceMotion) ExitTransition.None else fadeOut(tween(150))

    NavHost(
        navController = navController,
        startDestination = Routes.ANALYZE,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { enter },
        popExitTransition = { exit },
    ) {
        composable(Routes.ANALYZE) {
            val vm: AnalyzeViewModel = viewModel(factory = ViewModelFactories.analyze(container))
            AnalyzeRoute(
                viewModel = vm,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = ViewModelFactories.settings(container))
            SettingsRoute(
                viewModel = vm,
                onBack = {
                    // Guard against a double tap popping the start destination.
                    if (navController.currentDestination?.route == Routes.SETTINGS) navController.popBackStack()
                },
            )
        }
    }
}
