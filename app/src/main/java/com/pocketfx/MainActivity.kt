package com.pocketfx

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pocketfx.data.update.ApkInstaller
import com.pocketfx.ui.PocketFxApp
import com.pocketfx.ui.update.UpdateAction
import com.pocketfx.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by viewModels<com.pocketfx.ui.PocketFxViewModel> {
        viewModelFactory { initializer { (application as PocketFxApplication).createViewModel() } }
    }
    private val updates by viewModels<UpdateViewModel> {
        viewModelFactory { initializer { (application as PocketFxApplication).createUpdateViewModel() } }
    }
    private val apkInstaller by lazy { ApkInstaller(this) }
    private val installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.installerFinished(it.resultCode == Activity.RESULT_OK, cancelled = it.resultCode == Activity.RESULT_CANCELED)
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (apkInstaller.allowed()) updates.permissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PocketFxApp(model, window, updates) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updates.actions.collect { action ->
                    runCatching {
                        when (action) {
                            is UpdateAction.Install -> {
                                if (apkInstaller.allowed()) installLauncher.launch(apkInstaller.installIntent(action.file))
                                else updates.installationNeedsPermission()
                            }
                            UpdateAction.OpenPermissionSettings -> permissionLauncher.launch(apkInstaller.permissionIntent())
                        }
                    }.onFailure { updates.installerFinished(success = false) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (updates.state.value.configured && apkInstaller.allowed()) updates.permissionGranted()
        updates.check()
    }
}
