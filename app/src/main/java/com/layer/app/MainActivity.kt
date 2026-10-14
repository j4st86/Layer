package com.layer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.layer.app.locale.AppLanguagePreferences
import com.layer.app.ui.LayerRoot
import com.layer.app.ui.theme.LayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme is the platform splash. Switch to the app theme
        // before the window is created; minSdk 34 draws the splash itself.
        setTheme(R.style.Theme_Layer)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLanguagePreferences.applySaved(this)
        setContent {
            LayerTheme {
                LayerRoot(container = (application as LayerApp).container)
            }
        }
    }
}
