import SwiftUI

struct PairingView: View {
    @ObservedObject var server: LinkServer

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Pair Android")
                .font(.title2.bold())

            Text("Scan this QR code in the SimpleLink app on your phone.")
                .foregroundStyle(.secondary)

            HStack(alignment: .top, spacing: 24) {
                QRCodeView(text: server.pairingJSON)

                VStack(alignment: .leading, spacing: 8) {
                    statusRow("Server", server.isListening ? "Listening" : "Offline")
                    statusRow("Phone", server.isConnected ? "Connected" : "Not connected")
                    Text("IP: \(server.localAddress):\(LinkProtocol.defaultPort)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("New pairing code") {
                        server.restartPairing()
                    }
                }
            }
        }
        .padding(24)
        .frame(minWidth: 420, minHeight: 320)
    }

    private func statusRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .frame(width: 70, alignment: .leading)
            Text(value)
                .fontWeight(.medium)
        }
    }
}
