package com.pocketfx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketfx.ui.PocketFxApp

class MainActivity : ComponentActivity() {
    private val model by viewModels<com.pocketfx.ui.PocketFxViewModel> {
        viewModelFactory { initializer { (application as PocketFxApplication).createViewModel() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PocketFxApp(model, window) }
    }
}
