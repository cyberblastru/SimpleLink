#!/usr/bin/env swift
import AppKit

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
    let image = NSImage(size: NSSize(width: size, height: size))
    image.lockFocus()
    NSColor(calibratedRed: 0.15, green: 0.39, blue: 0.92, alpha: 1).setFill()
    NSBezierPath(rect: NSRect(x: 0, y: 0, width: size, height: size)).fill()

    let fontSize = CGFloat(size) * 0.34
    let attrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: fontSize, weight: .bold),
        .foregroundColor: NSColor.white
    ]
    let text = "SL" as NSString
    let textSize = text.size(withAttributes: attrs)
    let rect = NSRect(
        x: (CGFloat(size) - textSize.width) / 2,
        y: (CGFloat(size) - textSize.height) / 2,
        width: textSize.width,
        height: textSize.height
    )
    text.draw(in: rect, withAttributes: attrs)
    image.unlockFocus()

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
