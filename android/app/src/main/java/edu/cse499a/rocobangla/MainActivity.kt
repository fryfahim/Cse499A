package edu.cse499a.rocobangla

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.cse499a.rocobangla.ui.AppNavHost
import edu.cse499a.rocobangla.ui.theme.RocoBanglaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as RocoBanglaApp).container
        setContent {
            RocoBanglaTheme {
                AppNavHost(container)
            }
        }
    }
}
