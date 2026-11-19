package com.stemstudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stemstudio.app.ui.MainScreen
import com.stemstudio.app.ui.MainViewModel
import com.stemstudio.app.ui.theme.StemStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StemStudioTheme {
                val vm: MainViewModel = viewModel()
                MainScreen(viewModel = vm)
            }
        }
    }
}
