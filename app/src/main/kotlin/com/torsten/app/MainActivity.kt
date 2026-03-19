package com.torsten.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.torsten.app.ui.navigation.AppNavigation
import com.torsten.app.ui.theme.TorstenTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TorstenTheme {
                AppNavigation()
            }
        }
    }
}
