# SimpleLink

Local Android ↔ Mac connection for **file transfer** and **shared clipboard**. No cloud, no accounts.

## How it works

1. Run the Mac app — it shows a QR code.
2. Scan the QR code with the Android app.
3. Send files in either direction; clipboard text syncs automatically.

Incoming files on Mac go to `~/Downloads/SimpleLink/`.  
On Android they go to `Download/SimpleLink/` in the public Downloads folder.

## Mac app

Requirements: macOS 14+

### Install to Applications

```bash
cd macos
chmod +x build-app.sh
./build-app.sh
cp -R dist/SimpleLink.app /Applications/
open /Applications/SimpleLink.app
```

The app appears in **Launchpad / Applications** and runs from the **menu bar** as **SL**. Click it for status, file send, and pairing options. Use **Show QR Code…** to pair with Android.

On first launch macOS may ask to confirm opening an unsigned app: right-click the app → **Open**.

### Development

```bash
cd macos
swift run
```

Both devices must be on the same Wi‑Fi network. If connection fails, check macOS Firewall settings for incoming connections.

## Android app

Open the `android/` folder in Android Studio and run on a device or emulator.

- **Scan QR** to pair
- **Send file to Mac** picks a document from the phone
- Share a file from another app into SimpleLink (after pairing)

## Protocol

See [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Roadmap (not in scope yet)

- USB connection
- End-to-end encryption beyond local network trust
- iPad app
- Notification / SMS sync
