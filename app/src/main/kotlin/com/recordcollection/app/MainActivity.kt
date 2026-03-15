package com.recordcollection.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.recordcollection.app.ui.navigation.AppNavigation
import com.recordcollection.app.ui.theme.RecordCollectionTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RecordCollectionTheme {
                AppNavigation()
            }
        }
    }
}
