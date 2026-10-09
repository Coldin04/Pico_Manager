# iOS SDK integration

The iOS app and the native Share Extension link the locally built `InkReaderLinkFFI.xcframework` and compile the same generated `InkReaderLink.swift` UniFFI bindings from `Shared/Generated/InkReaderLink`. Device management and staged-file handling live in `Shared/DeviceManager.swift`, so both targets call the same SDK interface and capability checks. The framework and generated FFI headers are build outputs and are ignored by Git.

The app and Share Extension use the `group.com.cold04.InkReaderMgr` App Group to share saved device configuration. Development and distribution provisioning profiles must include this App Group. The extension presents its own device connection and send flow because iOS does not allow a Share Extension to directly launch its containing app.

When the sibling `picobook_sdk` source changes, rebuild and import it with:

```sh
./scripts/build-ios-sdk-local.sh
```

The script builds the SDK from the local Cargo workspace, regenerates the Swift bindings, and replaces the app's local XCFramework. Keep the SDK source revision and generated bindings/library from the same build together.

To build from the pinned upstream SDK commit instead of a sibling checkout, install the Rust targets `aarch64-apple-ios` and `aarch64-apple-ios-sim`, install UniFFI bindgen 0.32.2, then run:

```sh
./scripts/build-ios-sdk-from-git.sh "$(cat iOS/inkreaderlink-sdk.version)"
```

This fetches only that full commit SHA from `INKREADERLINK_SDK_REPOSITORY` (defaulting to the InkReaderLink GitHub repository), builds device and simulator static libraries, regenerates the Swift bindings, and creates the ignored XCFramework. iOS pins its SDK independently in `iOS/inkreaderlink-sdk.version`; it does not inherit the Android Maven coordinate or Android SDK commit. The iOS CI workflow caches generated outputs by the iOS commit SHA.

An `iOS-vX.Y.Z` tag builds a Release archive with code signing disabled, packages it as an unsigned IPA, verifies that the app and Share Extension contain no provisioning profile or signature, and publishes the IPA with build metadata and a SHA-256 checksum. The unsigned IPA must be signed by its distributor before it can be installed on a device.
