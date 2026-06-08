# SimpleLink Protocol v1

Direct TCP connection on the local network. No cloud.

## Pairing

The Mac app shows a QR code containing JSON:

```json
{
  "v": 1,
  "host": "192.168.1.10",
  "port": 9473,
  "token": "random-uuid"
}
```

Android scans the QR, opens a TCP socket to `host:port`, and sends `AUTH` with the token.

## Wire format

Every message is a frame:

| Field   | Size     | Description        |
|---------|----------|--------------------|
| magic   | 4 bytes  | `SLNK`             |
| type    | 1 byte   | message type       |
| length  | 4 bytes  | payload length (BE)|
| payload | variable | type-specific      |

## Message types

| ID | Name       | Payload                          |
|----|------------|----------------------------------|
| 1  | AUTH       | UTF-8 token string               |
| 2  | AUTH_OK    | empty                            |
| 3  | AUTH_FAIL  | UTF-8 reason                     |
| 4  | CLIPBOARD  | JSON `{"text":"...","from":"mac\|android"}` |
| 5  | FILE_BEGIN | JSON `{"id","name","size","path?","batchTotal?","batchOffset?"}` — batch fields enable multi-file progress |
| 6  | FILE_CHUNK | JSON header + raw bytes appended |
| 7  | FILE_END   | JSON `{"id"}`                    |
| 8  | PING       | empty                            |
| 9  | PONG       | empty                            |

`FILE_CHUNK` payload layout: 4-byte JSON length (BE) + JSON `{"id","offset"}` + raw file bytes.

## Clipboard

Either side sends `CLIPBOARD` when local clipboard text changes. Ignore messages where `from` matches the local device to avoid echo loops.

## Files

1. Sender emits `FILE_BEGIN` for each file (folders are expanded recursively)
2. Optional `path` preserves folder structure, e.g. `Photos/2024/img.jpg`
3. Optional `batchTotal` / `batchOffset` report overall batch progress (bytes already sent before this file, total bytes in batch)
4. Sender streams `FILE_CHUNK` messages (recommended chunk size: 64 KiB)
5. Sender emits `FILE_END`
6. Receiver writes to `~/Downloads/SimpleLink/` (Mac) or `Download/SimpleLink/` (Android)
7. Both sides should show transfer status as percent of total batch volume when `batchTotal` is present
