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

            Button("Send File to Android…") {
                let panel = NSOpenPanel()
                panel.canChooseFiles = true
                panel.canChooseDirectories = false
                panel.allowsMultipleSelection = false
                if panel.runModal() == .OK, let url = panel.url {
                    server.sendFile(url: url)
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
