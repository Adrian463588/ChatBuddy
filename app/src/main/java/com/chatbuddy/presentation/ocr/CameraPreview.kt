package com.chatbuddy.presentation.ocr

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File
import java.util.UUID

@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    onError: (String) -> Unit,
    onReady: () -> Unit = {},
    captureRequest: Long = 0L,
    onCapturedUri: (String) -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val currentOnError = rememberUpdatedState(onError)
    val currentOnReady = rememberUpdatedState(onReady)
    val currentOnCapturedUri = rememberUpdatedState(onCapturedUri)
    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }

    LaunchedEffect(captureRequest) {
        if (captureRequest == 0L) return@LaunchedEffect
        val imageCapture = imageCaptureRef.value
        if (imageCapture == null) {
            currentOnError.value("Camera capture is not ready yet.")
            return@LaunchedEffect
        }
        val directory = File(context.cacheDir, OCR_CAPTURE_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            currentOnError.value("Temporary image storage is unavailable.")
            return@LaunchedEffect
        }
        val file = File(directory, "capture-${UUID.randomUUID()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    currentOnCapturedUri.value(
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        ).toString()
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    currentOnError.value(
                        exception.message?.takeIf(String::isNotBlank)
                            ?: "Camera capture failed."
                    )
                }
            }
        )
    }

    DisposableEffect(lifecycleOwner, previewView, analyzer) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var previewUseCase: Preview? = null
        var analysisUseCase: ImageAnalysis? = null
        var imageCaptureUseCase: ImageCapture? = null
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "chatbuddy-camera-analysis").apply { isDaemon = true }
        }
        val future = ProcessCameraProvider.getInstance(context)

        fun unbindAndShutdown() {
            analysisUseCase?.clearAnalyzer()
            val cameraProvider = provider
            val preview = previewUseCase
            val analysis = analysisUseCase
            val imageCapture = imageCaptureUseCase
            imageCaptureRef.value = null
            if (cameraProvider != null && (preview != null || analysis != null || imageCapture != null)) {
                runCatching {
                    cameraProvider.unbind(*listOfNotNull(preview, analysis, imageCapture).toTypedArray())
                }
            }
            analysisExecutor.shutdown()
        }

        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                if (disposed) return@addListener
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (!cameraProvider.hasCamera(selector)) {
                    analysisExecutor.shutdown()
                    currentOnError.value("Back camera is not available on this device.")
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(analysisExecutor, analyzer)
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                if (disposed) {
                    imageAnalysis.clearAnalyzer()
                    analysisExecutor.shutdown()
                    return@addListener
                }
                cameraProvider.unbindAll()
                if (disposed) {
                    imageAnalysis.clearAnalyzer()
                    analysisExecutor.shutdown()
                    return@addListener
                }
                provider = cameraProvider
                previewUseCase = preview
                analysisUseCase = imageAnalysis
                imageCaptureUseCase = imageCapture
                imageCaptureRef.value = imageCapture
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis,
                    imageCapture
                )
                if (!disposed) currentOnReady.value()
            } catch (error: Throwable) {
                unbindAndShutdown()
                if (!disposed) {
                    currentOnError.value(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "Camera could not be started. Check camera permission and availability."
                    )
                }
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            unbindAndShutdown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .heightIn(min = 220.dp, max = 420.dp)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        overlay()
    }
}

private const val OCR_CAPTURE_DIRECTORY = "chatbuddy-ocr-captures"
