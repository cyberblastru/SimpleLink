import SwiftUI

@main
struct SimpleLinkApp: App {
    @StateObject private var server = LinkServer()

    init() {
        NSApplication.shared.setActivationPolicy(.accessory)
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarMenu(server: server)
        } label: {
            Image(nsImage: StatusBarIcon.make())
        }
        .menuBarExtraStyle(.menu)

        Window("SimpleLink — Pairing", id: "pairing") {
            PairingView(server: server)
        }
        .windowResizability(.contentSize)
    }
}
