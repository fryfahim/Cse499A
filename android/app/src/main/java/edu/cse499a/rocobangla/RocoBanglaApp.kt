package edu.cse499a.rocobangla

import android.app.Application
import edu.cse499a.rocobangla.data.AppContainer

class RocoBanglaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
