package com.procamera.app.ui.components

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.procamera.app.viewmodel.CameraViewModel

@Composable
fun ViewfinderSurface(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel
) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture, width: Int, height: Int
                    ) {
                        viewModel.startPreview(surface, width, height)
                    }
                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture, width: Int, height: Int
                    ) { /* handled by camera session */ }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier
            .pointerInput(Unit) {
                // Pinch-to-zoom
                detectTransformGestures { _, _, zoom, _ ->
                    val current = viewModel.uiState.value.settings.zoomRatio
                    viewModel.setZoom(current * zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { viewModel.toggleCamera() }
                )
            }
    )
}
