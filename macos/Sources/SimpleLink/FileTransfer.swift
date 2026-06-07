import Foundation

final class FileReceiver {
    private var openFiles: [String: (URL, FileHandle, Int64)] = [:]

    var downloadsDirectory: URL {
        let base = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("SimpleLink", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func handleBegin(id: String, name: String, size: Int64) throws {
        let safeName = (name as NSString).lastPathComponent
        let url = downloadsDirectory.appendingPathComponent(safeName)
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

final class FileSender {
    func send(url: URL, send: (MessageType, Data) -> Void) throws {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        let id = UUID().uuidString
        let name = url.lastPathComponent

        let beginPayload = LinkProtocol.jsonData([
            "id": id,
            "name": name,
            "size": size
        ])
        send(.fileBegin, beginPayload)

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        var offset: Int64 = 0
        while true {
            guard let chunk = try handle.read(upToCount: LinkProtocol.chunkSize), !chunk.isEmpty else { break }

            var header = LinkProtocol.jsonData([
                "id": id,
                "offset": offset
            ])
            var length = UInt32(header.count).bigEndian
            var payload = Data(bytes: &length, count: 4)
            payload.append(header)
            payload.append(chunk)
            send(.fileChunk, payload)

            offset += Int64(chunk.count)
        }

        let endPayload = LinkProtocol.jsonData(["id": id])
        send(.fileEnd, endPayload)
    }
}
