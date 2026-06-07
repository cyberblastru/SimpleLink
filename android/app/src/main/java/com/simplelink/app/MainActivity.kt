package com.simplelink.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.widget.Toast
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUri = when (intent?.action) {
            android.content.Intent.ACTION_SEND -> intent.getParcelableExtra(
                android.content.Intent.EXTRA_STREAM,
                Uri::class.java
            )
            else -> null
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SimpleLinkScreen(pendingShareUri = sharedUri)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LinkSession.pollClipboard()
    }
}

@Composable
fun SimpleLinkScreen(pendingShareUri: Uri?) {
    val context = LocalContext.current
    val status by LinkSession.status.collectAsState()
    val connected by LinkSession.connected.collectAsState()
    val lastFile by LinkSession.lastReceivedFile.collectAsState()
    var showScanner by remember { mutableStateOf(!connected) }
    var handledShare by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* continue regardless */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        contextFileFromUri(context, uri)?.let { LinkSession.sendFile(it) }
    }

    LaunchedEffect(connected, pendingShareUri, handledShare) {
        if (connected && pendingShareUri != null && !handledShare) {
            contextFileFromUri(context, pendingShareUri)?.let { LinkSession.sendFile(it) }
            handledShare = true
        }
    }

    LaunchedEffect(connected) {
        if (connected) showScanner = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SimpleLink", style = MaterialTheme.typography.headlineMedium)
        Text(if (connected) "Connected to Mac" else "Scan QR on your Mac")
        Text(status)

        lastFile?.let {
            Text("Last file: $it", style = MaterialTheme.typography.bodySmall)
        }

        if (showScanner && !connected) {
            QRScanner { json ->
                PairingPayload.parse(json)?.let { pairing ->
                    runCatching {
                        LinkSession.connect(context, pairing)
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "Cannot start connection: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                showScanner = false
            }
        } else {
            Button(onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Reconnect" else "Scan QR code")
            }
        }

        Button(
            onClick = { pickFile.launch(arrayOf("*/*")) },
            enabled = connected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send file to Mac")
        }

        if (connected) {
            Button(
                onClick = {
                    LinkSession.disconnect()
                    showScanner = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disconnect")
            }
            Text(
                "Connection restores automatically after Wi‑Fi returns. Files save to Download/SimpleLink/.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun QRScanner(onCode: (String) -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCamera) {
        Text("Camera permission required to scan QR")
        return
    }

    val scanned = remember { mutableStateOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        factory = { ctx ->
            PreviewView(ctx).also { previewView ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { imageProxy ->
                        if (scanned.value) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val mediaImage = imageProxy.image ?: run {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val raw = barcodes.firstOrNull()?.rawValue
                                if (!raw.isNullOrBlank() && !scanned.value) {
                                    scanned.value = true
                                    onCode(raw)
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        context as ComponentActivity,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }, ContextCompat.getMainExecutor(ctx))
            }
        }
    )
}
