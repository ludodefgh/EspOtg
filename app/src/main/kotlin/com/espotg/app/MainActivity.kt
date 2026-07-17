package com.espotg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.espotg.app.ui.EspOtgNavHost
import com.espotg.app.ui.AppViewModel
import com.espotg.app.ui.theme.EspOtgTheme

class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EspOtgTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EspOtgNavHost(appViewModel = appViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appViewModel.refreshDevices()
    }
}
