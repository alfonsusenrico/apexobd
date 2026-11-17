package com.apexobd.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.apexobd.app.ui.ApexAppRoot
import com.apexobd.app.ui.HudViewModel
import com.apexobd.app.ui.theme.TorqueEvoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HudViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            viewModel.locationTracker.startUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(perms)

        if (intent?.getBooleanExtra("run_ecu_dump", false) == true) {
            viewModel.runDeepEcuDump(
                host = intent.getStringExtra("wifi_host") ?: "192.168.0.10",
                port = intent.getIntExtra("wifi_port", 35000)
            )
        }

        setContent {
            TorqueEvoTheme {
                ApexAppRoot(
                    viewModel = viewModel,
                    onSystemChromeVisibility = { showSystemBars ->
                        if (showSystemBars) {
                            insetsController.show(WindowInsetsCompat.Type.systemBars())
                        } else {
                            insetsController.hide(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                )
            }
        }
    }
}
