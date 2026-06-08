package com.simplelink.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.systemBarsPadding
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
import com.simplelink.app.ui.theme.SimpleLinkTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val pendingShareState = mutableStateOf<SharePayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        pendingShareState.value = ShareIntentParser.parse(this, intent)

        setContent {
            SimpleLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val pendingShare by pendingShareState
                    SimpleLinkScreen(
                        pendingShare = pendingShare,
                        onShareConsumed = { pendingShareState.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingShareState.value = ShareIntentParser.parse(this, intent)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (LinkSession.connected.value) {
                moveTaskToBack(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LinkSession.pollClipboard()
    }
}

@Composable
fun SimpleLinkScreen(
    pendingShare: SharePayload?,
    onShareConsumed: () -> Unit
) {
    val context = LocalContext.current
    val status by LinkSession.status.collectAsState()
    val connected by LinkSession.connected.collectAsState()
    val lastFile by LinkSession.lastReceivedFile.collectAsState()
    var showScanner by remember { mutableStateOf(!connected) }
    var shareNoticeShown by remember { mutableStateOf(false) }

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

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val files = contextFilesFromUris(context, uris)
        if (files.isNotEmpty()) LinkSession.sendFiles(files)
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val files = contextFilesFromTreeUri(context, uri)
        if (files.isNotEmpty()) {
            LinkSession.sendFiles(files)
        } else {
            Toast.makeText(context, "Folder is empty", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(pendingShare) {
        shareNoticeShown = false
    }

    LaunchedEffect(pendingShare, connected) {
        val payload = pendingShare ?: return@LaunchedEffect
        if (!connected) {
            if (!shareNoticeShown) {
                Toast.makeText(context, "Connect to Mac first", Toast.LENGTH_LONG).show()
                shareNoticeShown = true
            }
            return@LaunchedEffect
        }

        when (val result = LinkSession.handleShare(context, payload)) {
            LinkSession.ShareResult.Sent -> {
                onShareConsumed()
                (context as? ComponentActivity)?.moveTaskToBack(true)
            }
            LinkSession.ShareResult.NotConnected -> {
                Toast.makeText(context, "Connect to Mac first", Toast.LENGTH_LONG).show()
            }
            LinkSession.ShareResult.Unsupported -> {
                Toast.makeText(context, "Cannot send this content", Toast.LENGTH_LONG).show()
                onShareConsumed()
            }
        }
    }

    LaunchedEffect(connected) {
        if (connected) showScanner = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
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
            onClick = { pickFiles.launch(arrayOf("*/*")) },
            enabled = connected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send files to Mac")
        }

        Button(
            onClick = { pickFolder.launch(null) },
            enabled = connected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send folder to Mac")
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
                "Share text or files from other apps — text goes to Mac clipboard, files to Downloads/SimpleLink/.",
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
