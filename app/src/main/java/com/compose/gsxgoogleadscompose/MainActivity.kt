package com.compose.gsxgoogleadscompose

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.compose.gsxgoogleadscompose.nav.AppNav
import com.compose.gsxgoogleadscompose.ui.theme.GSXGoogleAdsComposeTheme

/** 1 activity duy nhất — host Navigation Compose ([AppNav]). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            GSXGoogleAdsComposeTheme {
                AppNav()
            }
        }
    }
}
