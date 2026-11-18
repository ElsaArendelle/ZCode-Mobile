package com.autosquare.zcode.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.autosquare.zcode.mobile.R
import com.autosquare.zcode.mobile.data.BindingStore
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * 扫码绑定屏：CameraX 预览 + ML Kit 识别 QR + 相册选图识别。
 * 识别结果必须是移动页绑定 URL（见 BindingStore.parseBindingUrl），
 * 校验通过后存储并回调 onBound；无效二维码给出行内提示并继续扫。
 */
@Composable
fun ScanScreen(onBound: (String) -> Unit, onCancel: (() -> Unit)? = null) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) {} // UI 由 granted 分支处理
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    // 相册选图：识别截图里的绑定二维码（Photo Picker，API<33 自动降级系统文件选择器）
    var galleryError by remember { mutableStateOf<String?>(null) }
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        decodeQrFromUri(context, uri) { result ->
            if (result == null) {
                galleryError = context.getString(R.string.scan_no_qr_in_image)
            } else {
                onBound(result)
            }
        }
    }
    fun pickFromGallery() {
        galleryPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    if (granted) {
        CameraScanContent(
            onBound = onBound,
            onCancel = onCancel,
            onPickFromGallery = ::pickFromGallery,
            galleryError = galleryError,
            onGalleryErrorShown = { galleryError = null },
        )
    } else {
        // 无相机权限也能从相册绑定
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.scan_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.scan_permission_denied),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.web_retry))
            }
            TextButton(onClick = ::pickFromGallery, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.scan_from_gallery))
            }
            if (galleryError != null) {
                Text(
                    text = galleryError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CameraScanContent(
    onBound: (String) -> Unit,
    onCancel: (() -> Unit)?,
    onPickFromGallery: () -> Unit,
    galleryError: String?,
    onGalleryErrorShown: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // 错误提示 3 秒后自动消失
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            kotlinx.coroutines.delay(3000)
            errorMessage = null
        }
    }
    LaunchedEffect(galleryError) {
        if (galleryError != null) {
            kotlinx.coroutines.delay(3500)
            onGalleryErrorShown()
        }
    }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                            analyzeFrame(proxy, scanner) { raw ->
                                // 回调在主线程外，抛回主线程处理
                                previewView.post {
                                    if (paused) return@post
                                    val uri = BindingStore.parseBindingUrl(raw)
                                    if (uri != null) {
                                        paused = true
                                        BindingStore.save(ctx, uri.toString())
                                        onBound(uri.toString())
                                    } else {
                                        errorMessage = ctx.getString(R.string.scan_invalid_url)
                                    }
                                }
                            }
                        }
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (_: Exception) {
                        previewView.post { errorMessage = ctx.getString(R.string.scan_no_camera) }
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        // 扫描框装饰
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.Center)
                .border(2.dp, Color.White.copy(alpha = 0.8f)),
        )

        // 手电筒开关（扫另一块屏幕上的二维码时有用）
        IconButton(
            onClick = { torchOn = !torchOn },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_torch),
                contentDescription = "torch",
                tint = if (torchOn) Color.White else Color.White.copy(alpha = 0.4f),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onPickFromGallery) {
                Text(
                    text = stringResource(R.string.scan_from_gallery),
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            if (galleryError != null) {
                Text(
                    text = galleryError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
            }
        }
    }
}

/**
 * 从相册图片识别绑定二维码：解码 → BindingStore.parseBindingUrl 校验 → 存储。
 * 结果回调在主线程；识别失败或非绑定码回调 null（调用方给行内提示）。
 */
private fun decodeQrFromUri(
    context: android.content.Context,
    uri: Uri,
    onResult: (String?) -> Unit,
) {
    val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    try {
        val image = InputImage.fromFilePath(context, uri)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull()?.rawValue
                val bound = raw?.let { BindingStore.parseBindingUrl(it) }?.toString()
                if (bound != null) BindingStore.save(context, bound)
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(bound) }
            }
            .addOnFailureListener {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(null) }
            }
            .addOnCompleteListener { scanner.close() }
    } catch (_: Exception) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(null) }
    }
}

private fun analyzeFrame(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onResult: (String) -> Unit,
) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            val raw = barcodes.firstOrNull()?.rawValue
            if (!raw.isNullOrBlank()) {
                val normalized = try {
                    Uri.parse(raw).toString()
                } catch (_: Exception) {
                    raw
                }
                onResult(normalized)
            }
        }
        .addOnCompleteListener { proxy.close() }
}
