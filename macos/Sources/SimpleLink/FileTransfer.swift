import Foundation

enum PathUtils {
    static func sanitize(_ path: String) -> String {
        path
            .split(separator: "/")
            .map(String.init)
            .filter { !$0.isEmpty && $0 != "." && $0 != ".." }
            .joined(separator: "/")
    }
}

enum TransferProgress {
    static func percent(done: Int64, total: Int64) -> Int {
        guard total > 0 else { return 0 }
        return min(100, Int((done * 100) / total))
    }

    static func sending(done: Int64, total: Int64) -> String {
        "\(percent(done: done, total: total))% — Sending…"
    }

    static func receiving(done: Int64, total: Int64) -> String {
        "\(percent(done: done, total: total))% — Receiving…"
    }
}

struct OutgoingFile {
    let url: URL
    let relativePath: String
}

final class FileReceiver {
    private var openFiles: [String: (URL, FileHandle, Int64)] = [:]

    var downloadsDirectory: URL {
        let base = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("SimpleLink", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func handleBegin(id: String, name: String, size: Int64, relativePath: String?) throws {
        let rel = PathUtils.sanitize(relativePath ?? name)
        let url = downloadsDirectory.appendingPathComponent(rel)
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
        FileManager.default.createFile(atPath: url.path, contents: nil)
        guard let handle = FileHandle(forWritingAtPath: url.path) else {
            throw NSError(domain: "SimpleLink", code: 1, userInfo: [NSLocalizedDescriptionKey: "Cannot create file"])
        }
        openFiles[id] = (url, handle, size)
    }

    func handleChunk(id: String, offset: Int64, data: Data) throws {
        guard let (_, handle, _) = openFiles[id] else { return }
        try handle.seek(toOffset: UInt64(offset))
        try handle.write(contentsOf: data)
    }

    @discardableResult
    func handleEnd(id: String) -> URL? {
        guard let (url, handle, _) = openFiles.removeValue(forKey: id) else { return nil }
        try? handle.close()
        return url
    }
}

final class BatchReceiveProgress {
    private(set) var batchTotal: Int64 = 0
    private(set) var batchOffset: Int64 = 0
    private(set) var currentFileReceived: Int64 = 0
    private(set) var lastPercent: Int = -1

    func begin(batchTotal: Int64, batchOffset: Int64) {
        self.batchTotal = max(batchTotal, 1)
        self.batchOffset = batchOffset
        currentFileReceived = 0
        lastPercent = -1
    }

    func trackChunk(offset: Int64, size: Int) {
        currentFileReceived = max(currentFileReceived, offset + Int64(size))
    }

    var doneBytes: Int64 {
        min(batchOffset + currentFileReceived, batchTotal)
    }

    func currentPercent() -> Int {
        TransferProgress.percent(done: doneBytes, total: batchTotal)
    }

    func markReported(_ percent: Int) {
        lastPercent = percent
    }
}

final class FileSender {
    func collectItems(from urls: [URL]) throws -> [OutgoingFile] {
        var files: [OutgoingFile] = []
        for url in urls {
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) else { continue }
            if isDirectory.boolValue {
                try collectDirectory(url, into: &files)
            } else {
                files.append(OutgoingFile(url: url, relativePath: url.lastPathComponent))
            }
        }
        return files
    }

    func totalBytes(of files: [OutgoingFile]) throws -> Int64 {
        var total: Int64 = 0
        for item in files {
            total += try fileSize(at: item.url)
        }
        return total
    }

    func sendItems(
        from urls: [URL],
        emit: (MessageType, Data) -> Void,
        onProgress: ((Int64, Int64) -> Void)? = nil
    ) throws {
        let files = try collectItems(from: urls)
        guard !files.isEmpty else { return }

        let batchTotal = try totalBytes(of: files)
        var batchOffset: Int64 = 0

        for item in files {
            let fileSize = try fileSize(at: item.url)
            try sendFile(
                url: item.url,
                relativePath: item.relativePath,
                batchTotal: batchTotal,
                batchOffset: batchOffset,
                emit: emit
            ) { sentInFile in
                onProgress?(batchOffset + sentInFile, batchTotal)
            }
            batchOffset += fileSize
        }

        onProgress?(batchTotal, batchTotal)
    }

    private func collectDirectory(_ directory: URL, into files: inout [OutgoingFile]) throws {
        let rootPath = directory.path
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        for case let fileURL as URL in enumerator {
            let values = try fileURL.resourceValues(forKeys: [.isRegularFileKey])
            guard values.isRegularFile == true else { continue }
            let relative = fileURL.path.hasPrefix(rootPath + "/")
                ? String(fileURL.path.dropFirst(rootPath.count + 1))
                : fileURL.lastPathComponent
            files.append(OutgoingFile(url: fileURL, relativePath: relative))
        }
    }

    private func fileSize(at url: URL) throws -> Int64 {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func sendFile(
        url: URL,
        relativePath: String,
        batchTotal: Int64,
        batchOffset: Int64,
        emit: (MessageType, Data) -> Void,
        onProgress: ((Int64) -> Void)? = nil
    ) throws {
        let size = try fileSize(at: url)
        let id = UUID().uuidString
        let name = url.lastPathComponent
        let path = PathUtils.sanitize(relativePath)

        var beginPayload: [String: Any] = [
            "id": id,
            "name": name,
            "size": size,
            "batchTotal": batchTotal,
            "batchOffset": batchOffset
        ]
        if path != name {
            beginPayload["path"] = path
        }
        emit(.fileBegin, LinkProtocol.jsonData(beginPayload))

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        var offset: Int64 = 0
        while true {
            guard let chunk = try handle.read(upToCount: LinkProtocol.chunkSize), !chunk.isEmpty else { break }

            let header = LinkProtocol.jsonData([
                "id": id,
                "offset": offset
            ])
            var length = UInt32(header.count).bigEndian
            var payload = Data(bytes: &length, count: 4)
            payload.append(header)
            payload.append(chunk)
            emit(.fileChunk, payload)

            offset += Int64(chunk.count)
            onProgress?(offset)
        }

        emit(.fileEnd, LinkProtocol.jsonData(["id": id]))
    }
}
