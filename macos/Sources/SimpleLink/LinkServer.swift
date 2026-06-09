import Combine
import Foundation
import Network

@MainActor
final class LinkServer: ObservableObject {
    @Published private(set) var isListening = false
    @Published private(set) var isConnected = false
    @Published private(set) var statusText = "Starting…"
    @Published private(set) var pairingJSON = ""
    @Published private(set) var lastReceivedFile: String?
    @Published private(set) var localAddress = "127.0.0.1"
    @Published private(set) var transferProgress = TransferProgressState()

    let clipboard = ClipboardMonitor()
    private let fileReceiver = FileReceiver()
    private let fileSender = FileSender()

    private var listener: NWListener?
    private var connection: NWConnection?
    private var receiveBuffer = Data()
    private var authToken = UUID().uuidString
    private var keepAliveTimer: Timer?
    private var receiveProgress = BatchReceiveProgress()
    private var lastSendPercent = -1
    private var activeSendTask: Task<Void, Never>?

    func start() {
        authToken = UUID().uuidString
        localAddress = Self.primaryIPv4Address() ?? "127.0.0.1"
        updatePairingJSON()

        do {
            let params = NWParameters.tcp
            listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: LinkProtocol.defaultPort)!)
        } catch {
            statusText = "Failed to start: \(error.localizedDescription)"
            return
        }

        listener?.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .ready:
                    self?.isListening = true
                    self?.statusText = "Waiting for Android…"
                case .failed(let error):
                    self?.isListening = false
                    self?.statusText = "Listener failed: \(error.localizedDescription)"
                default:
                    break
                }
            }
        }

        listener?.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in
                self?.attach(connection: connection)
            }
        }

        listener?.start(queue: .global(qos: .userInitiated))
        clipboard.onRemotePaste = { [weak self] text in
            self?.sendClipboard(text)
        }
    }

    func stop() {
        keepAliveTimer?.invalidate()
        keepAliveTimer = nil
        clipboard.stop()
        connection?.cancel()
        connection = nil
        listener?.cancel()
        listener = nil
        isConnected = false
        isListening = false
        statusText = "Stopped"
    }

    func restartPairing() {
        cancelTransfer()
        connection?.cancel()
        connection = nil
        isConnected = false
        authToken = UUID().uuidString
        updatePairingJSON()
        statusText = "Waiting for Android…"
        lastReceivedFile = nil
    }

    func cancelTransfer() {
        activeSendTask?.cancel()
        activeSendTask = nil
        fileReceiver.closeAll()
        receiveProgress.reset()
        clearTransferProgress()
        lastReceivedFile = nil
        lastSendPercent = -1
        if isConnected {
            statusText = "Connected"
        }
    }

    func sendFiles(urls: [URL]) {
        guard isConnected, let connection else {
            statusText = "Connect Android first"
            return
        }
        guard !urls.isEmpty else { return }
        activeSendTask?.cancel()
        lastSendPercent = -1
        activeSendTask = Task.detached { [weak self, connection] in
            do {
                let sender = await MainActor.run { self?.fileSender }
                guard let sender else { return }
                let count = try sender.collectItems(from: urls).count
                try sender.sendItems(from: urls, emit: { type, payload in
                    let frame = LinkProtocol.encode(type: type, payload: payload)
                    let semaphore = DispatchSemaphore(value: 0)
                    connection.send(content: frame, completion: .contentProcessed { _ in
                        semaphore.signal()
                    })
                    semaphore.wait()
                }, onProgress: { done, total in
                    Task { @MainActor in
                        self?.reportSendProgress(done: done, total: total)
                    }
                })
                await MainActor.run {
                    guard let self, !Task.isCancelled else { return }
                    if count == 1, let name = urls.first?.lastPathComponent {
                        self.statusText = "Sent \(name)"
                    } else {
                        self.statusText = "Sent \(count) items"
                    }
                    self.clearTransferProgress()
                    self.lastSendPercent = -1
                    self.activeSendTask = nil
                }
            } catch is CancellationError {
                await MainActor.run {
                    self?.cancelTransfer()
                }
            } catch {
                await MainActor.run {
                    self?.clearTransferUiState()
                    if self?.isConnected == true {
                        self?.statusText = "Connected"
                    }
                    self?.lastSendPercent = -1
                    self?.activeSendTask = nil
                }
            }
        }
    }

    private func reportSendProgress(done: Int64, total: Int64) {
        let percent = TransferProgress.percent(done: done, total: total)
        guard percent != lastSendPercent else { return }
        lastSendPercent = percent
        let label = TransferProgress.sending(done: done, total: total)
        statusText = label
        transferProgress = TransferProgressState(
            direction: .sending,
            done: done,
            total: total,
            label: label
        )
    }

    private func reportReceiveProgress() {
        let percent = receiveProgress.currentPercent()
        guard percent != receiveProgress.lastPercent else { return }
        receiveProgress.markReported(percent)
        let label = TransferProgress.receiving(
            done: receiveProgress.doneBytes,
            total: receiveProgress.batchTotal
        )
        statusText = label
        transferProgress = TransferProgressState(
            direction: .receiving,
            done: receiveProgress.doneBytes,
            total: receiveProgress.batchTotal,
            label: label
        )
    }

    private func clearTransferProgress() {
        transferProgress = TransferProgressState()
    }

    private func clearTransferUiState() {
        clearTransferProgress()
        lastReceivedFile = nil
        receiveProgress.reset()
    }

    func sendFile(url: URL) {
        sendFiles(urls: [url])
    }

    private func attach(connection: NWConnection) {
        self.connection?.cancel()
        self.connection = connection
        receiveBuffer.removeAll(keepingCapacity: true)

        connection.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .ready:
                    self?.receiveLoop()
                case .failed, .cancelled:
                    self?.handleDisconnect()
                default:
                    break
                }
            }
        }
        connection.start(queue: .global(qos: .userInitiated))
    }

    private func receiveLoop() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            Task { @MainActor in
                guard let self else { return }
                if let data, !data.isEmpty {
                    self.receiveBuffer.append(data)
                    self.processBuffer()
                }
                if error != nil || isComplete {
                    self.handleDisconnect()
                    return
                }
                self.receiveLoop()
            }
        }
    }

    private func processBuffer() {
        let messages = LinkProtocol.decodeFrames(from: &receiveBuffer)
        for (type, payload) in messages {
            handle(type: type, payload: payload)
        }
    }

    private func handle(type: MessageType, payload: Data) {
        switch type {
        case .auth:
            let token = String(data: payload, encoding: .utf8) ?? ""
            if token == authToken {
                send(type: .authOK)
                isConnected = true
                statusText = "Connected"
                clipboard.start()
                startKeepAlive()
            } else {
                send(type: .authFail, payload: Data("Invalid token".utf8))
                connection?.cancel()
            }

        case .clipboard:
            guard let json = LinkProtocol.jsonObject(from: payload),
                  let text = json["text"] as? String,
                  !text.isEmpty else { return }
            let from = json["from"] as? String ?? ""
            guard from != DeviceSide.mac.rawValue else { return }
            clipboard.applyRemoteText(text)
            statusText = "Clipboard updated from Android"

        case .fileBegin:
            guard let json = LinkProtocol.jsonObject(from: payload),
                  let id = json["id"] as? String,
                  let name = json["name"] as? String else { return }
            let size = Self.int64(json["size"]) ?? 0
            let path = json["path"] as? String
            let batchTotal = Self.int64(json["batchTotal"]) ?? size
            let batchOffset = Self.int64(json["batchOffset"]) ?? 0
            try? fileReceiver.handleBegin(id: id, name: name, size: size, relativePath: path)
            receiveProgress.begin(batchTotal: batchTotal, batchOffset: batchOffset)
            reportReceiveProgress()

        case .fileChunk:
            guard payload.count >= 4 else { return }
            let jsonLength = payload.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            let headerEnd = 4 + Int(jsonLength)
            guard payload.count > headerEnd,
                  let json = LinkProtocol.jsonObject(from: payload.subdata(in: 4..<headerEnd)),
                  let id = json["id"] as? String,
                  let offset = Self.int64(json["offset"]) else { return }
            let chunk = payload.subdata(in: headerEnd..<payload.count)
            try? fileReceiver.handleChunk(id: id, offset: offset, data: chunk)
            receiveProgress.trackChunk(offset: offset, size: chunk.count)
            reportReceiveProgress()

        case .fileEnd:
            guard let json = LinkProtocol.jsonObject(from: payload),
                  let id = json["id"] as? String else { return }
            if let url = fileReceiver.handleEnd(id: id) {
                lastReceivedFile = url.lastPathComponent
                clearTransferProgress()
                statusText = "Received \(url.lastPathComponent)"
            }

        case .ping:
            send(type: .pong)

        case .pong:
            break

        default:
            break
        }
    }

    private func send(type: MessageType, payload: Data = Data()) {
        let frame = LinkProtocol.encode(type: type, payload: payload)
        connection?.send(content: frame, completion: .contentProcessed { _ in })
    }

    private func sendClipboard(_ text: String) {
        let payload = LinkProtocol.jsonData([
            "text": text,
            "from": DeviceSide.mac.rawValue
        ])
        send(type: .clipboard, payload: payload)
    }

    private func startKeepAlive() {
        keepAliveTimer?.invalidate()
        keepAliveTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.send(type: .ping)
            }
        }
    }

    private func handleDisconnect() {
        keepAliveTimer?.invalidate()
        keepAliveTimer = nil
        clipboard.stop()
        activeSendTask?.cancel()
        activeSendTask = nil
        fileReceiver.closeAll()
        isConnected = false
        clearTransferUiState()
        statusText = "Waiting for Android…"
        connection = nil
    }

    private func updatePairingJSON() {
        let payload = PairingPayload(
            v: 1,
            host: localAddress,
            port: Int(LinkProtocol.defaultPort),
            token: authToken
        )
        if let data = try? JSONEncoder().encode(payload),
           let json = String(data: data, encoding: .utf8) {
            pairingJSON = json
        }
    }

    private static func int64(_ value: Any?) -> Int64? {
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        if let value = value as? NSNumber { return value.int64Value }
        return nil
    }

    private static func primaryIPv4Address() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            let family = interface.ifa_addr.pointee.sa_family
            guard family == UInt8(AF_INET) else { continue }
            let name = String(cString: interface.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("wl") else { continue }

            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(
                interface.ifa_addr,
                socklen_t(interface.ifa_addr.pointee.sa_len),
                &hostname,
                socklen_t(hostname.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            let ip = String(cString: hostname)
            if !ip.hasPrefix("127.") {
                address = ip
                break
            }
        }
        return address
    }
}
