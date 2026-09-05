package com.layer.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.layer.app.locale.AppLanguagePreferences
import com.layer.app.ui.LayerRoot
import com.layer.app.ui.theme.LayerTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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
