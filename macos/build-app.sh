#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

APP_NAME="SimpleLink"
DIST_DIR="$ROOT/dist"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"
UNIVERSAL_BIN="$DIST_DIR/$APP_NAME-universal"

echo "Building release binaries…"
swift build -c release --arch x86_64
swift build -c release --arch arm64

BIN_X86="$(swift build -c release --arch x86_64 --show-bin-path)/$APP_NAME"
BIN_ARM="$(swift build -c release --arch arm64 --show-bin-path)/$APP_NAME"

if [[ ! -f "$BIN_X86" || ! -f "$BIN_ARM" ]]; then
  echo "Failed to build one or both architectures" >&2
  exit 1
fi

mkdir -p "$DIST_DIR"
lipo -create "$BIN_X86" "$BIN_ARM" -output "$UNIVERSAL_BIN"
echo "Universal binary:"
lipo -info "$UNIVERSAL_BIN"

echo "Generating app icon…"
swift scripts/generate-icon.swift
iconutil -c icns Resources/AppIcon.iconset -o Resources/AppIcon.icns

echo "Creating app bundle…"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
mkdir -p "$APP_BUNDLE/Contents/Resources"

cp "$UNIVERSAL_BIN" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
cp Info.plist "$APP_BUNDLE/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP_BUNDLE/Contents/Resources/AppIcon.icns"

chmod +x "$APP_BUNDLE/Contents/MacOS/$APP_NAME"

echo ""
echo "Built: $APP_BUNDLE"
echo ""
echo "Install to Applications:"
echo "  cp -R \"$APP_BUNDLE\" /Applications/"
echo ""
echo "Or open directly:"
echo "  open \"$APP_BUNDLE\""
