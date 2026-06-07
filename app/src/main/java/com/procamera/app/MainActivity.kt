package com.procamera.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.procamera.app.ui.CameraScreen
import com.procamera.app.ui.PermissionScreen
import com.procamera.app.ui.theme.ProCameraTheme
import com.procamera.app.viewmodel.CameraViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            ProCameraTheme {
                ProCameraApp()
            }
        }
    }
}

@Composable
private fun ProCameraApp() {
    val context = LocalContext.current
    val viewModel: CameraViewModel = viewModel()

    // ── Required permissions ──────────────────────────────────────────────────
    val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun allGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var hasPermissions by remember { mutableStateOf(allGranted()) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        if (hasPermissions) {
            viewModel.initCamera(isFront = false)
        }
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            viewModel.initCamera(isFront = false)
        }
    }

    if (hasPermissions) {
        CameraScreen(viewModel = viewModel)
    } else {
        PermissionScreen(
            onRequestPermissions = {
                permLauncher.launch(requiredPermissions.toTypedArray())
            }
        )
    }
}
