import SwiftUI

struct MenuBarMenu: View {
    @ObservedObject var server: LinkServer
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Group {
            Text(server.isConnected ? "Phone connected" : "Waiting for phone")
                .disabled(true)
            Text(server.statusText)
                .disabled(true)
            Text("\(server.localAddress):\(LinkProtocol.defaultPort)")
                .disabled(true)

            Divider()

            Button("Show QR Code…") {
                openWindow(id: "pairing")
                NSApp.activate(ignoringOtherApps: true)
            }

            Button("Send to Android…") {
                let panel = NSOpenPanel()
                panel.canChooseFiles = true
                panel.canChooseDirectories = true
                panel.allowsMultipleSelection = true
                panel.message = "Select files or folders to send"
                if panel.runModal() == .OK {
                    server.sendFiles(urls: panel.urls)
                }
            }
            .disabled(!server.isConnected)

            Button("New Pairing Code") {
                server.restartPairing()
            }

            Divider()

            Button("Quit SimpleLink") {
                server.stop()
                NSApplication.shared.terminate(nil)
            }
        }
        .onAppear {
            if !server.isListening {
                server.start()
            }
        }
    }
}
