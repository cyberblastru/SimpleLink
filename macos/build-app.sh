#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

APP_NAME="SimpleLink"
DIST_DIR="$ROOT/dist"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"

echo "Building release binary…"
swift build -c release
BIN_DIR="$(swift build -c release --show-bin-path)"
BINARY="$BIN_DIR/$APP_NAME"

if [[ ! -f "$BINARY" ]]; then
  echo "Binary not found: $BINARY" >&2
  exit 1
fi

echo "Generating app icon…"
swift scripts/generate-icon.swift
iconutil -c icns Resources/AppIcon.iconset -o Resources/AppIcon.icns

echo "Creating app bundle…"
rm -rf "$APP_BUNDLE"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
mkdir -p "$APP_BUNDLE/Contents/Resources"

cp "$BINARY" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
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
