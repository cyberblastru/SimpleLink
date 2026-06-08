#!/usr/bin/env swift
import AppKit

let brandBlue = NSColor(
    calibratedRed: 37.0 / 255.0,
    green: 99.0 / 255.0,
    blue: 235.0 / 255.0,
    alpha: 1
)

let diamondViewport: [(CGFloat, CGFloat)] = [
    (30, 54), (54, 30), (78, 54), (54, 78)
]

func mapAndroidPoint(_ point: (CGFloat, CGFloat), in rect: NSRect) -> NSPoint {
    NSPoint(
        x: rect.minX + (point.0 / 108.0) * rect.width,
        y: rect.minY + (1.0 - point.1 / 108.0) * rect.height
    )
}

func diamondPath(in rect: NSRect) -> NSBezierPath {
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

func drawAppIcon(size: Int) -> NSImage {
    let side = CGFloat(size)
    let image = NSImage(size: NSSize(width: side, height: side))
    image.lockFocus()

    let rect = NSRect(x: 0, y: 0, width: side, height: side)
    brandBlue.setFill()
    NSBezierPath(rect: rect).fill()

    NSColor.white.setFill()
    diamondPath(in: rect).fill()

    image.unlockFocus()
    return image
}

let sizes: [(String, Int)] = [
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024),
]

let iconsetURL = URL(fileURLWithPath: "Resources/AppIcon.iconset", isDirectory: true)
try FileManager.default.createDirectory(at: iconsetURL, withIntermediateDirectories: true)

for (name, size) in sizes {
    let image = drawAppIcon(size: size)

    guard
        let tiff = image.tiffRepresentation,
        let rep = NSBitmapImageRep(data: tiff),
        let png = rep.representation(using: .png, properties: [:])
    else {
        fatalError("Failed to render \(name)")
    }

    try png.write(to: iconsetURL.appendingPathComponent(name))
}

print("Generated AppIcon.iconset")
