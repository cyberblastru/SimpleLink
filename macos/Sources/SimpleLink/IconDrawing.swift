import AppKit

enum IconDrawing {
    static let brandBlue = NSColor(
        calibratedRed: 37.0 / 255.0,
        green: 99.0 / 255.0,
        blue: 235.0 / 255.0,
        alpha: 1
    )

    private static let diamondViewport: [(CGFloat, CGFloat)] = [
        (30, 54), (54, 30), (78, 54), (54, 78)
    ]

    static func diamondPath(in rect: NSRect) -> NSBezierPath {
        let path = NSBezierPath()
        for (index, point) in diamondViewport.enumerated() {
            let mapped = mapAndroidPoint(point, in: rect)
            if index == 0 {
                path.move(to: mapped)
            } else {
                path.line(to: mapped)
            }
        }
        path.close()
        return path
    }

    static func drawAppIcon(in rect: NSRect) {
        brandBlue.setFill()
        NSBezierPath(rect: rect).fill()

        NSColor.white.setFill()
        diamondPath(in: rect).fill()
    }

    static func drawStatusBarIcon(in rect: NSRect) {
        let outer = NSBezierPath(
            roundedRect: rect.insetBy(dx: 1, dy: 1),
            xRadius: 3.5,
            yRadius: 3.5
        )
        let cutout = diamondPath(in: rect.insetBy(dx: 4.5, dy: 4.5))
        outer.append(cutout)
        outer.windingRule = .evenOdd

        NSColor.black.setFill()
        outer.fill()
    }

    private static func mapAndroidPoint(_ point: (CGFloat, CGFloat), in rect: NSRect) -> NSPoint {
        NSPoint(
            x: rect.minX + (point.0 / 108.0) * rect.width,
            y: rect.minY + (1.0 - point.1 / 108.0) * rect.height
        )
    }
}
