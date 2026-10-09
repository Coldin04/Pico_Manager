#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf 'Usage: %s <sdk-version-with-git-sha>\n' "$0" >&2
    printf 'Example: %s git.<40-character-commit-sha>\n' "$0" >&2
}

if [[ $# -ne 1 ]]; then
    usage
    exit 2
fi

sdk_version="$1"
if [[ ! "$sdk_version" =~ ^git\.([0-9a-f]{40})$ ]]; then
    printf 'Expected git.<full-commit-sha>, got: %s\n' "$sdk_version" >&2
    exit 2
fi
commit_sha="${BASH_REMATCH[1]}"
sdk_repository="${INKREADERLINK_SDK_REPOSITORY:-https://github.com/Coldin04/InkReaderLink.git}"
app_root="$(cd "$(dirname "$0")/.." && pwd)"
generated_dir="$app_root/iOS/Shared/Generated/InkReaderLink"
frameworks_dir="$app_root/iOS/Frameworks"
headers_dir="$frameworks_dir/InkReaderLinkFFIHeaders"
required_targets=(aarch64-apple-ios aarch64-apple-ios-sim)

for tool in cargo git rustup xcodebuild uniffi-bindgen; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        printf '%s is required to build the iOS InkReaderLink SDK.\n' "$tool" >&2
        exit 1
    fi
done

bindgen_version="$(uniffi-bindgen --version | awk '{print $NF}')"
if [[ "$bindgen_version" != "0.32.2" ]]; then
    printf 'Expected uniffi-bindgen 0.32.2, found %s.\n' "$bindgen_version" >&2
    printf 'Install it with: cargo install uniffi --version 0.32.2 --features cli --bin uniffi-bindgen --locked\n' >&2
    exit 1
fi

installed_targets="$(rustup target list --installed)"
for target in "${required_targets[@]}"; do
    if ! grep -Fxq "$target" <<<"$installed_targets"; then
        printf 'Rust target %s is missing. Install it with: rustup target add %s\n' "$target" "$target" >&2
        exit 1
    fi
done

temp_root="$(mktemp -d "${TMPDIR:-/tmp}/inkreaderlink-ios-sdk.XXXXXX")"
cleanup() {
    rm -rf "$temp_root"
}
trap cleanup EXIT

sdk_checkout="$temp_root/sdk"
git init -q "$sdk_checkout"
git -C "$sdk_checkout" remote add origin "$sdk_repository"
git -C "$sdk_checkout" fetch --quiet --depth=1 origin "$commit_sha"
git -C "$sdk_checkout" checkout --quiet --detach FETCH_HEAD

resolved_sha="$(git -C "$sdk_checkout" rev-parse HEAD)"
if [[ "$resolved_sha" != "$commit_sha" ]]; then
    printf 'Requested SDK commit %s but fetched %s\n' "$commit_sha" "$resolved_sha" >&2
    exit 1
fi

for target in "" "${required_targets[@]}"; do
    cargo_args=(build --locked --release -p inkreaderlink-uniffi --manifest-path "$sdk_checkout/Cargo.toml")
    if [[ -n "$target" ]]; then
        cargo_args+=(--target "$target")
    fi
    cargo "${cargo_args[@]}"
done

mkdir -p "$generated_dir" "$headers_dir"
(
    cd "$sdk_checkout"
    uniffi-bindgen generate target/release/libinkreaderlink_uniffi.dylib \
        --language swift \
        --out-dir "$generated_dir"
)

cp "$generated_dir/InkReaderLinkFFI.h" "$headers_dir/InkReaderLinkFFI.h"
cp "$generated_dir/InkReaderLinkFFI.modulemap" "$headers_dir/module.modulemap"

device_library="$sdk_checkout/target/aarch64-apple-ios/release/libinkreaderlink_uniffi.a"
simulator_library="$sdk_checkout/target/aarch64-apple-ios-sim/release/libinkreaderlink_uniffi.a"
for library in "$device_library" "$simulator_library"; do
    if [[ ! -f "$library" ]]; then
        printf 'Expected SDK library was not produced: %s\n' "$library" >&2
        exit 1
    fi
done

rm -rf "$frameworks_dir/InkReaderLinkFFI.xcframework"
xcodebuild -create-xcframework \
    -library "$device_library" \
    -headers "$headers_dir" \
    -library "$simulator_library" \
    -headers "$headers_dir" \
    -output "$frameworks_dir/InkReaderLinkFFI.xcframework"

printf '\nBuilt InkReaderLink iOS SDK from %s.\n' "$resolved_sha"
printf 'SDK repository: %s\n' "$sdk_repository"
printf 'XCFramework: %s\n' "$frameworks_dir/InkReaderLinkFFI.xcframework"
