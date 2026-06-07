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

    private val receiveBuffer = mutableListOf<Byte>()
    private val fileReceiver = FileReceiver(context)
    private val fileSender = FileSender()
    private var clipboardMonitor: ClipboardMonitor? = null

    fun connect(pairing: PairingPayload, enableAutoReconnect: Boolean = true) {
        userInitiatedDisconnect = false
        autoReconnectEnabled = enableAutoReconnect
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
        _connected.value = false

        if (userInitiated) {
            _status.value = "Disconnected"
            LinkSession.onConnectionChanged(false, _status.value)
            LinkForegroundService.stop(context)
        }
    }

    fun sendFile(file: ContextFile) {
        val out = output ?: run {
            setStatus("Not connected", connected = false)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                setStatus("Sending ${file.name}…", connected = true)
                sendMutex.withLock {
                    fileSender.send(file, out)
                }
                setStatus("Sent ${file.name}", connected = true)
            } catch (e: Exception) {
                setStatus("Send failed: ${e.message}", connected = true)
            }
        }
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
                    sock.connect(InetSocketAddress(pairing.host, pairing.port), 10_000)
                    val out = DataOutputStream(sock.getOutputStream())
                    val input = DataInputStream(sock.getInputStream())

                    sendFrame(out, MessageType.AUTH, pairing.token.toByteArray(Charsets.UTF_8))

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
            if (read <= 0) break

            for (i in 0 until read) receiveBuffer.add(buffer[i])
            processBuffer()
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

        if (userInitiatedDisconnect || !autoReconnectEnabled) {
            _status.value = message
            LinkSession.onConnectionChanged(false, message)
            return
        }

        setStatus(message, connected = false)
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
                        sock.connect(InetSocketAddress(pairing.host, pairing.port), 10_000)
                        val out = DataOutputStream(sock.getOutputStream())
                        val input = DataInputStream(sock.getInputStream())
                        sendFrame(out, MessageType.AUTH, pairing.token.toByteArray(Charsets.UTF_8))
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
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
        receiveBuffer.clear()
    }

    private fun processBuffer() {
        val messages = LinkProtocol.decodeFrames(receiveBuffer)
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
                pendingPairing?.let { PairingStore.save(context, it) }
                setStatus("Connected", connected = true)
                clipboardMonitor = ClipboardMonitor(context) { text ->
                    sendClipboard(text)
                }.also { it.start() }
                startPing()
                startClipboardPolling()
            }

            MessageType.AUTH_FAIL -> {
                autoReconnectEnabled = false
                reconnectJob?.cancel()
                PairingStore.clear(context)
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
                fileReceiver.handleBegin(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    size = json.getLong("size")
                )
                setStatus("Receiving ${json.getString("name")}…", connected = true)
            }

            MessageType.FILE_CHUNK -> {
                if (payload.size < 4) return
                val jsonLength = java.nio.ByteBuffer.wrap(payload, 0, 4)
                    .order(java.nio.ByteOrder.BIG_ENDIAN).int
                val headerEnd = 4 + jsonLength
                if (payload.size <= headerEnd) return
                val json = LinkProtocol.jsonObject(payload.copyOfRange(4, headerEnd)) ?: return
                val chunk = payload.copyOfRange(headerEnd, payload.size)
                fileReceiver.handleChunk(
                    id = json.getString("id"),
                    offset = json.getLong("offset"),
                    data = chunk
                )
            }

            MessageType.FILE_END -> {
                val json = LinkProtocol.jsonObject(payload) ?: return
                val file = fileReceiver.handleEnd(json.getString("id"))
                if (file != null) {
                    _lastReceivedFile.value = file.path
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
                delay(15_000)
                sendType(MessageType.PING)
            }
        }
    }

    private fun startClipboardPolling() {
        clipboardPollJob?.cancel()
        clipboardPollJob = scope.launch(Dispatchers.Main) {
            while (isActive && _connected.value) {
                clipboardMonitor?.poll()
                delay(500)
            }
        }
    }

    private fun setStatus(message: String, connected: Boolean) {
        _status.value = message
        _connected.value = connected
        LinkSession.onConnectionChanged(connected, message)
    }
}
