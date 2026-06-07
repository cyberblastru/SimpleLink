import Foundation

enum MessageType: UInt8 {
    case auth = 1
    case authOK = 2
    case authFail = 3
    case clipboard = 4
    case fileBegin = 5
    case fileChunk = 6
    case fileEnd = 7
    case ping = 8
    case pong = 9
}

enum LinkProtocol {
    static let magic = Data("SLNK".utf8)
    static let defaultPort: UInt16 = 9473
    static let chunkSize = 64 * 1024

    static func encode(type: MessageType, payload: Data = Data()) -> Data {
        var frame = Data()
        frame.append(magic)
        frame.append(type.rawValue)
        var length = UInt32(payload.count).bigEndian
        frame.append(Data(bytes: &length, count: 4))
        frame.append(payload)
        return frame
    }

    static func decodeFrames(from buffer: inout Data) -> [(MessageType, Data)] {
        var messages: [(MessageType, Data)] = []

        while buffer.count >= 9 {
            guard buffer.prefix(4) == magic else {
                if let range = buffer.range(of: magic) {
                    buffer.removeSubrange(buffer.startIndex..<range.lowerBound)
                } else {
                    buffer.removeAll()
                }
                continue
            }

            let typeRaw = buffer[4]
            let length = buffer.subdata(in: 5..<9).withUnsafeBytes {
                $0.load(as: UInt32.self).bigEndian
            }
            let total = 9 + Int(length)
            guard buffer.count >= total else { break }

            let payload = buffer.subdata(in: 9..<total)
            if let type = MessageType(rawValue: typeRaw) {
                messages.append((type, payload))
            }
            buffer.removeSubrange(0..<total)
        }

        return messages
    }

    static func jsonData(_ object: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    static func jsonObject(from data: Data) -> [String: Any]? {
        try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}

struct PairingPayload: Codable {
    let v: Int
    let host: String
    let port: Int
    let token: String
}

enum DeviceSide: String {
    case mac
    case android
}
