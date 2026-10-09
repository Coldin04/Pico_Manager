#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="$(cd "$APP_ROOT/../picobook_sdk" && pwd)"
GENERATED="$APP_ROOT/iOS/Shared/Generated/InkReaderLink"
FRAMEWORKS="$APP_ROOT/iOS/Frameworks"
HEADERS="$FRAMEWORKS/InkReaderLinkFFIHeaders"

if [[ ! -f "$SDK_ROOT/Cargo.toml" ]]; then
  echo "Local InkReaderLink SDK not found at $SDK_ROOT" >&2
  exit 1
fi
if ! command -v uniffi-bindgen >/dev/null 2>&1; then
  echo "Install or expose uniffi-bindgen matching the SDK's UniFFI version (0.32.2)." >&2
  exit 1
fi

cargo build --locked --release -p inkreaderlink-uniffi --manifest-path "$SDK_ROOT/Cargo.toml"
cargo build --locked --release -p inkreaderlink-uniffi --target aarch64-apple-ios --manifest-path "$SDK_ROOT/Cargo.toml"
cargo build --locked --release -p inkreaderlink-uniffi --target aarch64-apple-ios-sim --manifest-path "$SDK_ROOT/Cargo.toml"

mkdir -p "$GENERATED" "$HEADERS"
(
  cd "$SDK_ROOT"
  uniffi-bindgen generate target/release/libinkreaderlink_uniffi.dylib \
    --language swift \
    --out-dir "$GENERATED"
)
cp "$GENERATED/InkReaderLinkFFI.h" "$HEADERS/InkReaderLinkFFI.h"
cp "$GENERATED/InkReaderLinkFFI.modulemap" "$HEADERS/module.modulemap"

rm -rf "$FRAMEWORKS/InkReaderLinkFFI.xcframework"
xcodebuild -create-xcframework \
  -library "$SDK_ROOT/target/aarch64-apple-ios/release/libinkreaderlink_uniffi.a" \
  -headers "$HEADERS" \
  -library "$SDK_ROOT/target/aarch64-apple-ios-sim/release/libinkreaderlink_uniffi.a" \
  -headers "$HEADERS" \
  -output "$FRAMEWORKS/InkReaderLinkFFI.xcframework"
