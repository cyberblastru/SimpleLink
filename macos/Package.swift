// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SimpleLink",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "SimpleLink",
            path: "Sources/SimpleLink"
        )
    ]
)
