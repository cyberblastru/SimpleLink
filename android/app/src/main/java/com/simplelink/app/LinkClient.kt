package com.simplelink.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class LinkClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastReceivedFile = MutableStateFlow<String?>(null)
    val lastReceivedFile: StateFlow<String?> = _lastReceivedFile.asStateFlow()

    private val _transferProgress = MutableStateFlow(TransferProgressState())
    val transferProgress: StateFlow<TransferProgressState> = _transferProgress.asStateFlow()

    private val pendingSendQueue = mutableListOf<ContextFile>()
    private var queueOwnerToken: String? = null

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null
    private var clipboardPollJob: Job? = null
    private var reconnectJob: Job? = null
    private val sendMutex = Mutex()
    private val connectMutex = Mutex()

    private var autoReconnectEnabled = false
    private var userInitiatedDisconnect = false
    private var reconnectAttempt = 0
    private var pendingPairing: PairingPayload? = null

    private val receiveBuffer = FrameBuffer()
    private val fileReceiver = FileReceiver(context)
    private val fileSender = FileSender()
    private val receiveProgress = BatchReceiveProgress()
    private var lastSendPercent = -1
    @Volatile
    private var cancelTransferRequested = false
    private var clipboardMonitor: ClipboardMonitor? = null

    fun connect(pairing: PairingPayload, enableAutoReconnect: Boolean = true) {
        userInitiatedDisconnect = false
        autoReconnectEnabled = enableAutoReconnect
        if (queueOwnerToken != null && queueOwnerToken != pairing.token) {
            clearSendQueue()
        }
        queueOwnerToken = pairing.token
        pendingPairing = pairing
        PairingStore.save(context, pairing)
        connectInternal(pairing)
    }

    fun tryAutoReconnect() {
        if (userInitiatedDisconnect || !autoReconnectEnabled || _connected.value) return
        val pairing = PairingStore.load(context) ?: return
        pendingPairing = pairing
        LinkForegroundService.ensureReconnecting(context)
        scope.launch(Dispatchers.IO) {
            connectInternal(pairing)
        }
    }

    fun disconnect(userInitiated: Boolean = true) {
        userInitiatedDisconnect = userInitiated
        if (userInitiated) {
            autoReconnectEnabled = false
            reconnectJob?.cancel()
            reconnectJob = null
            PairingStore.clear(context)
            clearSendQueue()
        }

        pingJob?.cancel()
        clipboardPollJob?.cancel()
        readJob?.cancel()
        clipboardMonitor?.stop()
        clipboardMonitor = null
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
        receiveBuffer.clear()
        cancelTransferRequested = false
        clearTransferUiState()
        _connected.value = false
        _status.value = ""

        if (userInitiated) {
            LinkSession.onConnectionChanged(false, "")
            LinkForegroundService.stop(context)
        }
    }

    fun cancelTransfer() {
        scope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                cancelTransferRequested = true
                pendingSendQueue.clear()
                fileReceiver.closeAll()
                receiveProgress.reset()
                clearTransferUiState()
                cancelTransferRequested = false
                if (_connected.value) {
                    setStatus("Connected", connected = true)
                }
            }
        }
    }

    fun sendFile(file: ContextFile) {
        sendFiles(listOf(file))
    }

    fun sendSharedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            setStatus("Nothing to send", connected = _connected.value)
            return
        }
        if (!_connected.value) {
            setStatus("Not connected", connected = false)
            return
        }
        sendClipboard(trimmed)
        setStatus("Sent to Mac clipboard", connected = true)
    }

    fun sendFiles(files: List<ContextFile>) {
        if (files.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                enqueueFiles(files)
                if (_connected.value && output != null) {
                    flushSendQueue()
                } else {
                    updateQueuedStatus()
                    if (!userInitiatedDisconnect &&
                        (PairingStore.load(context) != null || pendingPairing != null)
                    ) {
                        tryAutoReconnect()
                    }
                }
            }
        }
    }

    fun queueFiles(files: List<ContextFile>): Boolean {
        if (files.isEmpty()) return false
        scope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                enqueueFiles(files)
                updateQueuedStatus()
                if (!userInitiatedDisconnect &&
                    (PairingStore.load(context) != null || pendingPairing != null)
                ) {
                    tryAutoReconnect()
                }
            }
        }
        return true
    }

    private fun enqueueFiles(files: List<ContextFile>) {
        val token = PairingStore.load(context)?.token ?: pendingPairing?.token
        if (token != null) {
            resetQueueIfNewDevice(token)
            queueOwnerToken = token
        }
        pendingSendQueue.addAll(files)
    }

    private fun resetQueueIfNewDevice(token: String) {
        if (queueOwnerToken != null && queueOwnerToken != token) {
            clearSendQueue()
        }
    }

    private fun clearSendQueue() {
        pendingSendQueue.clear()
        queueOwnerToken = null
        if (_transferProgress.value.direction == TransferDirection.Queued) {
            clearTransferProgress()
        }
    }

    private suspend fun flushSendQueue() {
        val out = output ?: return
        if (pendingSendQueue.isEmpty()) return

        val files = pendingSendQueue.toList()
        pendingSendQueue.clear()
        updateQueuedStatus()

        try {
            val batchTotal = files.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
            var batchOffset = 0L
            lastSendPercent = -1
            files.forEach { file ->
                fileSender.send(
                    file = file,
                    output = out,
                    batchTotal = batchTotal,
                    batchOffset = batchOffset,
                    onProgress = { done, total ->
                        reportSendProgress(done, total)
                    },
                    shouldCancel = { cancelTransferRequested }
                )
                batchOffset += file.size.coerceAtLeast(0)
            }
            reportSendProgress(batchTotal, batchTotal, force = true)
            setStatus(
                if (files.size == 1) "Sent ${files.first().name}" else "Sent ${files.size} items",
                connected = true
            )
            clearTransferProgress()
            lastSendPercent = -1
        } catch (e: Exception) {
            if (cancelTransferRequested || e is java.util.concurrent.CancellationException) {
                clearTransferUiState()
                cancelTransferRequested = false
                if (_connected.value) {
                    setStatus("Connected", connected = true)
                }
                return
            }
            pendingSendQueue.addAll(0, files)
            clearTransferUiState()
            if (_connected.value) {
                setStatus("Connected", connected = true)
            } else {
                _status.value = ""
            }
            lastSendPercent = -1
        }
    }

    private fun updateQueuedStatus() {
        val count = pendingSendQueue.size
        if (count == 0) {
            if (_transferProgress.value.direction == TransferDirection.Queued) {
                clearTransferProgress()
            }
            return
        }
        if (!_connected.value) return
        val label = TransferProgress.queued(count)
        _transferProgress.value = TransferProgressState(
            direction = TransferDirection.Queued,
            queuedFiles = count,
            label = label
        )
        setStatus(label, connected = true)
    }

    private fun reportSendProgress(done: Long, total: Long, force: Boolean = false) {
        val percent = TransferProgress.percent(done, total)
        if (!force && percent == lastSendPercent) return
        lastSendPercent = percent
        val label = TransferProgress.sending(done, total)
        _transferProgress.value = TransferProgressState(
            direction = TransferDirection.Sending,
            done = done,
            total = total,
            label = label
        )
        updateTransferStatus(label, connected = true)
    }

    private fun reportReceiveProgress(force: Boolean = false) {
        val percent = receiveProgress.currentPercent()
        if (!force && percent == receiveProgress.lastPercent) return
        receiveProgress.lastPercent = percent
        val label = TransferProgress.receiving(
            receiveProgress.doneBytes,
            receiveProgress.batchTotal
        )
        _transferProgress.value = TransferProgressState(
            direction = TransferDirection.Receiving,
            done = receiveProgress.doneBytes,
            total = receiveProgress.batchTotal,
            label = label
        )
        updateTransferStatus(label, connected = true)
    }

    private fun clearTransferProgress() {
        _transferProgress.value = TransferProgressState()
    }

    private fun clearTransferUiState() {
        clearTransferProgress()
        _lastReceivedFile.value = null
        receiveProgress.reset()
    }

    fun pollClipboard() {
        if (_connected.value) {
            clipboardMonitor?.poll()
        }
    }

    private fun connectInternal(pairing: PairingPayload) {
        reconnectJob?.cancel()
        scope.launch(Dispatchers.IO) {
            val started = connectMutex.withLock {
                if (_connected.value) return@launch
                closeSocket()
                try {
                    setStatus("Connecting…", connected = false)
                    val sock = Socket()
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.soTimeout = 45_000
                    sock.receiveBufferSize = 512 * 1024
                    sock.sendBufferSize = 512 * 1024
                    sock.connect(InetSocketAddress(pairing.host, pairing.port), 10_000)
                    val out = DataOutputStream(sock.getOutputStream())
                    val input = DataInputStream(sock.getInputStream())

                    sendFrame(out, MessageType.AUTH, pairing.token.toByteArray(Charsets.UTF_8))
                    out.flush()

                    socket = sock
                    output = out
                    readJob = launch { readLoop(input) }
                    true
                } catch (e: Exception) {
                    setStatus("Connection failed: ${e.message}", connected = false)
                    false
                }
            }
            if (started != true) {
                handleConnectionLost("Connection failed")
                return@launch
            }
            if (!waitForConnection()) {
                handleConnectionLost("Connection timed out")
            }
        }
    }

    private suspend fun waitForConnection(timeoutMs: Long = 12_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_connected.value) return true
            if (userInitiatedDisconnect || !autoReconnectEnabled) return false
            delay(200)
        }
        return _connected.value
    }

    private suspend fun readLoop(input: DataInputStream) {
        val buffer = ByteArray(256 * 1024)
        while (scope.isActive && socket?.isConnected == true) {
            val read = runCatching { input.read(buffer) }.getOrElse { -1 }
            if (read > 0) {
                receiveBuffer.append(buffer, 0, read)
                processBuffer()
                continue
            }
            if (read < 0) break
            // read == 0 with SO_TIMEOUT: keep connection alive while screen is off
        }
        handleConnectionLost("Connection lost")
    }

    private fun handleConnectionLost(message: String) {
        closeSocket()
        _connected.value = false
        pingJob?.cancel()
        clipboardPollJob?.cancel()
        clipboardMonitor?.stop()
        clipboardMonitor = null
        clearTransferUiState()
        _status.value = ""

        if (userInitiatedDisconnect || !autoReconnectEnabled) {
            LinkSession.onConnectionChanged(false, "")
            return
        }

        if (reconnectJob?.isActive != true) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnectEnabled || userInitiatedDisconnect) return
        if (PairingStore.load(context) == null) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            val delays = listOf(2L, 3L, 5L, 8L, 13L, 21L, 30L)
            var attempt = 0
            while (isActive && autoReconnectEnabled && !userInitiatedDisconnect && !_connected.value) {
                val delaySec = delays.getOrElse(attempt) { 30L }
                attempt++
                reconnectAttempt = attempt
                setStatus("Reconnecting in ${delaySec}s…", connected = false)
                LinkForegroundService.ensureReconnecting(context)
                delay(delaySec * 1000)
                if (!context.isOnLocalNetwork()) continue
                val pairing = PairingStore.load(context) ?: break
                connectMutex.withLock {
                    if (_connected.value) return@launch
                    closeSocket()
                    try {
                        setStatus("Reconnecting…", connected = false)
                        val sock = Socket()
                        sock.tcpNoDelay = true
                        sock.keepAlive = true
                        sock.soTimeout = 45_000
                        sock.receiveBufferSize = 512 * 1024
                        sock.sendBufferSize = 512 * 1024
                        sock.connect(InetSocketAddress(pairing.host, pairing.port), 10_000)
                        val out = DataOutputStream(sock.getOutputStream())
                        val input = DataInputStream(sock.getInputStream())
                        sendFrame(out, MessageType.AUTH, pairing.token.toByteArray(Charsets.UTF_8))
                        out.flush()
                        socket = sock
                        output = out
                        readJob = launch { readLoop(input) }
                    } catch (e: Exception) {
                        closeSocket()
                        setStatus("Reconnect failed: ${e.message}", connected = false)
                    }
                }
                if (waitForConnection()) {
                    reconnectAttempt = 0
                    break
                }
            }
        }
    }

    private fun closeSocket() {
        readJob?.cancel()
        fileReceiver.closeAll()
        receiveProgress.reset()
        clearTransferProgress()
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
        receiveBuffer.clear()
    }

    private fun processBuffer() {
        val messages = receiveBuffer.decodeFrames()
        for ((type, payload) in messages) {
            handle(type, payload)
        }
    }

    private fun handle(type: MessageType, payload: ByteArray) {
        when (type) {
            MessageType.AUTH_OK -> {
                reconnectAttempt = 0
                reconnectJob?.cancel()
                _connected.value = true
                pendingPairing?.let {
                    PairingStore.save(context, it)
                    queueOwnerToken = it.token
                }
                setStatus("Connected", connected = true)
                clipboardMonitor = ClipboardMonitor(context) { text ->
                    sendClipboard(text)
                }.also { it.start() }
                startPing()
                startClipboardPolling()
                scope.launch(Dispatchers.IO) {
                    sendMutex.withLock { flushSendQueue() }
                }
            }

            MessageType.AUTH_FAIL -> {
                autoReconnectEnabled = false
                reconnectJob?.cancel()
                PairingStore.clear(context)
                clearSendQueue()
                setStatus("Pairing expired — scan QR again", connected = false)
                disconnect(userInitiated = true)
            }

            MessageType.CLIPBOARD -> {
                val json = LinkProtocol.jsonObject(payload) ?: return
                val text = json.optString("text")
                val from = json.optString("from")
                if (from == DeviceSide.ANDROID.value || text.isEmpty()) return
                clipboardMonitor?.applyRemoteText(text)
            }

            MessageType.FILE_BEGIN -> {
                val json = LinkProtocol.jsonObject(payload) ?: return
                val name = json.getString("name")
                val path = json.optString("path").takeIf { it.isNotEmpty() } ?: name
                val size = json.getLong("size")
                val batchTotal = json.optLong("batchTotal", size)
                val batchOffset = json.optLong("batchOffset", 0)
                fileReceiver.handleBegin(
                    id = json.getString("id"),
                    name = name,
                    size = size,
                    relativePath = path
                )
                receiveProgress.begin(batchTotal, batchOffset)
                reportReceiveProgress(force = true)
            }

            MessageType.FILE_CHUNK -> {
                if (payload.size < 4) return
                val jsonLength = java.nio.ByteBuffer.wrap(payload, 0, 4)
                    .order(java.nio.ByteOrder.BIG_ENDIAN).int
                val headerEnd = 4 + jsonLength
                if (payload.size <= headerEnd) return
                val json = LinkProtocol.jsonObject(payload, 4, jsonLength) ?: return
                fileReceiver.handleChunk(
                    id = json.getString("id"),
                    offset = json.getLong("offset"),
                    data = payload,
                    dataOffset = headerEnd,
                    dataLength = payload.size - headerEnd
                )
                receiveProgress.trackChunk(
                    json.getLong("offset"),
                    payload.size - headerEnd
                )
                reportReceiveProgress()
            }

            MessageType.FILE_END -> {
                val json = LinkProtocol.jsonObject(payload) ?: return
                val file = fileReceiver.handleEnd(json.getString("id"))
                if (file != null) {
                    _lastReceivedFile.value = file.path
                    clearTransferProgress()
                    setStatus("Saved to ${file.path}", connected = true)
                }
            }

            MessageType.PING -> sendType(MessageType.PONG)
            MessageType.PONG -> Unit
            else -> Unit
        }
    }

    private fun sendClipboard(text: String) {
        val json = JSONObject()
            .put("text", text)
            .put("from", DeviceSide.ANDROID.value)
        send(MessageType.CLIPBOARD, LinkProtocol.jsonBytes(json))
    }

    private fun send(type: MessageType, payload: ByteArray) {
        val out = output ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                sendMutex.withLock {
                    sendFrame(out, type, payload)
                }
            }.onFailure {
                handleConnectionLost("Send failed: ${it.message}")
            }
        }
    }

    private suspend fun sendFrame(out: DataOutputStream, type: MessageType, payload: ByteArray) {
        out.writeFrame(type, payload)
    }

    private fun sendType(type: MessageType) = send(type, ByteArray(0))

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connected.value) {
                delay(10_000)
                sendType(MessageType.PING)
            }
        }
    }

    private fun startClipboardPolling() {
        clipboardPollJob?.cancel()
        clipboardPollJob = scope.launch(Dispatchers.Default) {
            while (isActive && _connected.value) {
                clipboardMonitor?.poll()
                delay(500)
            }
        }
    }

    private fun updateTransferStatus(message: String, connected: Boolean) {
        _status.value = message
        _connected.value = connected
    }

    private fun setStatus(message: String, connected: Boolean) {
        updateTransferStatus(message, connected)
        LinkSession.onConnectionChanged(connected, message)
    }
}
