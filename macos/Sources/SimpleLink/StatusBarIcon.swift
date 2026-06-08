import AppKit

enum StatusBarIcon {
    static func make() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { rect in
            IconDrawing.drawStatusBarIcon(in: rect)
            return true
        }
        image.isTemplate = true
        return image
    }
}
