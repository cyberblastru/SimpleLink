import AppKit
import Combine
import Foundation

@MainActor
final class ClipboardMonitor: ObservableObject {
    @Published private(set) var lastSentText: String = ""

    private var timer: Timer?
    private var lastChangeCount = NSPasteboard.general.changeCount
    var onRemotePaste: ((String) -> Void)?

    func start() {
        stop()
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.poll()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    func applyRemoteText(_ text: String) {
        lastSentText = text
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.writeObjects([text as NSString])
        lastChangeCount = pasteboard.changeCount
    }

    private func poll() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastChangeCount else { return }
        lastChangeCount = pasteboard.changeCount

        guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        guard text != lastSentText else { return }
        lastSentText = text
        onRemotePaste?(text)
    }
}
