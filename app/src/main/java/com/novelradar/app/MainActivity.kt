package com.novelradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.novelradar.app.app.NovelRadarApp
import com.novelradar.app.ui.theme.NovelRadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovelRadarTheme {
                NovelRadarApp()
            }
        }
    }
}
