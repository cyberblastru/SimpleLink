package com.simplelink.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

object LinkSession {
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var client: LinkClient
    private var networkMonitor: NetworkMonitor? = null
    private val fallbackStatus = MutableStateFlow("Not connected")
    private val fallbackConnected = MutableStateFlow(false)
    private val fallbackLastFile = MutableStateFlow<String?>(null)
    private val fallbackTransfer = MutableStateFlow(TransferProgressState())

    fun init(app: Application) {
        appContext = app.applicationContext
        client = LinkClient(appContext, scope)
        networkMonitor = NetworkMonitor(appContext) {
            client.tryAutoReconnect()
        }.also { it.start() }

        PairingStore.load(appContext)?.let {
            client.tryAutoReconnect()
        }
    }

    private fun ensureReady() {
        check(::client.isInitialized) { "SimpleLink is still starting up" }
    }

    val status: StateFlow<String>
        get() = if (::client.isInitialized) client.status else fallbackStatus

    val connected: StateFlow<Boolean>
        get() = if (::client.isInitialized) client.connected else fallbackConnected

    val lastReceivedFile: StateFlow<String?>
        get() = if (::client.isInitialized) client.lastReceivedFile else fallbackLastFile

    val transferProgress: StateFlow<TransferProgressState>
        get() = if (::client.isInitialized) client.transferProgress else fallbackTransfer

    fun connect(context: Context, pairing: PairingPayload) {
        ensureReady()
        LinkForegroundService.connect(context, pairing.toJson())
    }

    fun disconnect() {
        ensureReady()
        client.disconnect(userInitiated = true)
    }

    fun cancelTransfer() {
        ensureReady()
        client.cancelTransfer()
    }

    fun sendFile(file: ContextFile) {
        ensureReady()
        client.sendFile(file)
    }

    fun sendFiles(files: List<ContextFile>) {
        ensureReady()
        client.sendFiles(files)
    }

    fun handleShare(context: Context, payload: SharePayload): ShareResult {
        ensureReady()
        return when (payload) {
            is SharePayload.Text -> {
                if (!client.connected.value) return ShareResult.NotConnected
                client.sendSharedText(payload.text)
                ShareResult.Sent
            }
            is SharePayload.Files -> {
                val files = contextFilesFromUris(context, payload.uris)
                if (files.isEmpty()) return ShareResult.Unsupported
                client.sendFiles(files)
                if (client.connected.value) ShareResult.Sent else ShareResult.Queued
            }
        }
    }

    enum class ShareResult {
        Sent,
        Queued,
        NotConnected,
        Unsupported
    }

    fun pollClipboard() {
        if (::client.isInitialized) {
            client.pollClipboard()
        }
    }

    internal fun clientConnect(pairing: PairingPayload) {
        ensureReady()
        client.connect(pairing, enableAutoReconnect = true)
    }

    internal fun shutdownConnection() {
        if (::client.isInitialized) {
            client.disconnect(userInitiated = true)
        }
    }

    internal fun onConnectionChanged(connected: Boolean, status: String) {
        LinkForegroundService.notifyStatus(status, connected)
    }

    private fun PairingPayload.toJson(): String = JSONObject()
        .put("v", v)
        .put("host", host)
        .put("port", port)
        .put("token", token)
        .toString()
}
