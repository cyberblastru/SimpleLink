import AppKit

enum StatusBarIcon {
    static func make() -> NSImage {
        let size = NSSize(width: 18, height: 18)
        let image = NSImage(size: size, flipped: false) { rect in
            let text = "SL" as NSString
            let attributes: [NSAttributedString.Key: Any] = [
                .font: NSFont.systemFont(ofSize: 10, weight: .bold),
                .foregroundColor: NSColor.black
            ]
            let textSize = text.size(withAttributes: attributes)
            let origin = NSPoint(
                x: (rect.width - textSize.width) / 2,
                y: (rect.height - textSize.height) / 2
            )
            text.draw(at: origin, withAttributes: attributes)
            return true
        }
        image.isTemplate = true
        return image
    }
}
