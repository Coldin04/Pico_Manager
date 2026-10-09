import Combine
import Foundation
import InkReaderLinkFFI

struct SavedDevice: Codable, Identifiable, Hashable {
    var id: String
    var deviceType: String
    var values: [String: String]
}

enum SharedAppGroup {
    static let identifier = "group.com.cold04.InkReaderMgr"
}

nonisolated struct ImportedFile: Identifiable, Hashable, Sendable {
    var id: UUID = UUID()
    var name: String
    var url: URL

    var fileExtension: String {
        url.pathExtension.lowercased()
    }
}

enum ManagerError: LocalizedError {
    case deviceNotConnected
    case capabilityUnavailable(String)
    case operationInProgress
    case duplicateDevice
    case invalidConnectionField(String)
    case unsupportedFile(String)

    var errorDescription: String? {
        switch self {
        case .deviceNotConnected:
            "设备未连接"
        case .capabilityUnavailable(let capability):
            "设备不支持此功能（\(capability)）"
        case .operationInProgress:
            "设备正在处理其他操作"
        case .duplicateDevice:
            "该设备已保存"
        case .invalidConnectionField(let label):
            "请检查\(label)"
        case .unsupportedFile(let name):
            "设备不支持上传：\(name)"
        }
    }
}

final class UploadProgressObserver: SdkUploadProgressObserver, @unchecked Sendable {
    weak var manager: DeviceManager?

    init(manager: DeviceManager) {
        self.manager = manager
    }

    func onProgress(sentBytes: UInt64, totalBytes: UInt64) {
        Task { @MainActor [weak manager = self.manager] in
            manager?.recordUploadProgress(sent: sentBytes, total: totalBytes)
        }
    }
}

@MainActor
final class DeviceManager: ObservableObject {
    @Published private(set) var savedDevices: [SavedDevice]
    @Published private(set) var activeDevice: SavedDevice?
    @Published private(set) var profile: SdkDeviceProfile?
    @Published private(set) var isMutating = false
    @Published private(set) var activeOperation: String?
    @Published var uploadProgress: (sent: UInt64, total: UInt64)?

    let supportedDevices: [SdkSupportedDevice]

    private let storageKey = "savedDevices"
    private let storage: UserDefaults
    private var client: SdkDeviceClient?

    init() {
        let storage = UserDefaults(suiteName: SharedAppGroup.identifier) ?? .standard
        self.storage = storage
        supportedDevices = BooksendSdk().supportedDevices()
        if let data = storage.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode([SavedDevice].self, from: data) {
            savedDevices = decoded
        } else if let legacyData = UserDefaults.standard.data(forKey: storageKey),
                  let decoded = try? JSONDecoder().decode([SavedDevice].self, from: legacyData) {
            savedDevices = decoded
            storage.set(legacyData, forKey: storageKey)
        } else {
            savedDevices = []
        }
    }

    var isConnected: Bool { client != nil }

    func supports(_ capability: String) -> Bool {
        profile?.capabilities.contains(capability) == true
    }

    func supportsAll(_ capabilities: Set<String>) -> Bool {
        !capabilities.isEmpty && capabilities.allSatisfy(supports)
    }

    func supportsAny(_ capabilities: Set<String>) -> Bool {
        capabilities.contains(where: supports)
    }

    func definition(for device: SavedDevice) -> SdkSupportedDevice? {
        supportedDevices.first { $0.deviceType == device.deviceType }
    }

    func address(for device: SavedDevice) -> String {
        guard let field = definition(for: device)?.connectionFields.first(where: {
            if case .address = $0.kind { return true }
            return false
        }) else { return "" }
        return device.values[field.key, default: ""]
    }

    func deviceName(for deviceType: String) -> String {
        supportedDevices.first { $0.deviceType == deviceType }?.displayName ?? deviceType
    }

    func saveDevice(id: String? = nil, deviceType: String, values: [String: String]) throws -> SavedDevice {
        guard let definition = supportedDevices.first(where: { $0.deviceType == deviceType }) else {
            throw ManagerError.invalidConnectionField("设备类型")
        }

        var normalized = values
        for field in definition.connectionFields {
            let value = normalized[field.key, default: field.kind.isToggle ? "false" : ""].trimmingCharacters(in: .whitespacesAndNewlines)
            if field.required && value.isEmpty {
                throw ManagerError.invalidConnectionField(field.label)
            }
            if case .choice(let options) = field.kind {
                guard let index = Int(value), options.indices.contains(index) else {
                    throw ManagerError.invalidConnectionField(field.label)
                }
            }
            if field.kind.isToggle && value != "true" && value != "false" {
                throw ManagerError.invalidConnectionField(field.label)
            }
            normalized[field.key] = value
        }

        if savedDevices.contains(where: {
            $0.id != id && $0.deviceType == deviceType && $0.values == normalized
        }) {
            throw ManagerError.duplicateDevice
        }

        let saved = SavedDevice(id: id ?? UUID().uuidString, deviceType: deviceType, values: normalized)
        if let id, let index = savedDevices.firstIndex(where: { $0.id == id }) {
            let wasActive = activeDevice?.id == id
            savedDevices[index] = saved
            if wasActive {
                disconnect()
            }
        } else {
            savedDevices.append(saved)
        }
        persistDevices()
        return saved
    }

    func removeDevice(_ device: SavedDevice) {
        if activeDevice?.id == device.id { disconnect() }
        savedDevices.removeAll { $0.id == device.id }
        persistDevices()
    }

    func connect(_ device: SavedDevice) async throws {
        if activeDevice?.id == device.id { return }
        disconnect()
        guard let definition = definition(for: device) else {
            throw ManagerError.invalidConnectionField("设备类型")
        }
        let parameters = try definition.connectionFields.compactMap { field -> SdkConnectionParameter? in
            guard let value = device.values[field.key] else {
                if field.required { throw ManagerError.invalidConnectionField(field.label) }
                return nil
            }
            let typedValue: SdkConnectionValue
            switch field.kind {
            case .text:
                typedValue = .text(value: value)
            case .address:
                typedValue = .address(value: value)
            case .choice:
                guard let index = UInt32(value) else { throw ManagerError.invalidConnectionField(field.label) }
                typedValue = .choice(index: index)
            case .toggle:
                typedValue = .toggle(value: value == "true")
            }
            return SdkConnectionParameter(key: field.key, value: typedValue)
        }

        let connected = try await SdkDeviceClient.connectAndVerifyWithParameters(
            deviceType: device.deviceType,
            parameters: parameters,
            timeoutMs: 5_000
        )
        client = connected
        profile = connected.profile()
        activeDevice = device
    }

    func disconnect() {
        client = nil
        profile = nil
        activeDevice = nil
        uploadProgress = nil
    }

    func recordUploadProgress(sent: UInt64, total: UInt64) {
        uploadProgress = (sent, total)
    }

    func listFiles(_ location: SdkFileLocation) async throws -> [SdkFileEntry] {
        try await requireClient("files.list").listFiles(location: location)
    }

    func deviceInfo() async throws -> [SdkDeviceInfoField] {
        try await requireClient("device.info").deviceInfo()
    }

    func listWifiNetworks() async throws -> [SdkWifiNetwork] {
        try await requireClient("wifi.list").listWifiNetworks()
    }

    func listFonts() async throws -> SdkFontCatalog {
        try await requireClient("fonts.list").listFonts()
    }

    func listOpdsServers() async throws -> [SdkOpdsServer] {
        try await requireClient("opds.list").listOpdsServers()
    }

    func listSettings() async throws -> SdkSettingsSnapshot {
        try await requireClient("settings.list").listSettings()
    }

    func listWallpapers() async throws -> [SdkFileEntry] {
        try await requireClient("wallpapers.manage").listWallpapers()
    }

    func upload(_ file: ImportedFile, to location: SdkFileLocation, overwrite: Bool = false) async throws -> SdkUploadResult {
        let currentProfile = try requireProfile("files.upload")
        let canReportProgress = currentProfile.capabilities.contains("upload.websocket")
        uploadProgress = nil
        let observer = canReportProgress ? UploadProgressObserver(manager: self) : nil
        return try await mutate("files.upload", operation: "上传") { client in
            try await client.upload(
                localPath: file.url.path,
                fileName: file.name,
                location: location,
                options: SdkUploadOptions(
                    conflictPolicy: overwrite ? .overwriteWhenSupported : self.uploadConflictPolicy,
                    contentType: nil,
                    preferWebsocket: canReportProgress
                ),
                progressObserver: observer
            )
        }
    }

    func uploadFont(_ file: ImportedFile, family: String, overwrite: Bool = false) async throws {
        let currentProfile = try requireProfile("fonts.upload")
        if overwrite && !currentProfile.capabilities.contains("upload.explicit-overwrite") {
            throw ManagerError.capabilityUnavailable("upload.explicit-overwrite")
        }
        let reportProgress = currentProfile.capabilities.contains("fonts.upload.progress")
        let observer = reportProgress ? UploadProgressObserver(manager: self) : nil
        uploadProgress = nil
        try await mutate("fonts.upload", operation: "上传字体") { client in
            if let observer {
                try await client.uploadFontWithOverwriteAndProgress(
                    family: family,
                    localPath: file.url.path,
                    fileName: file.name,
                    overwrite: overwrite,
                    progressObserver: observer
                )
            } else if overwrite || currentProfile.capabilities.contains("upload.explicit-overwrite") {
                try await client.uploadFontWithOverwrite(
                    family: family,
                    localPath: file.url.path,
                    fileName: file.name,
                    overwrite: overwrite
                )
            } else {
                try await client.uploadFont(family: family, localPath: file.url.path, fileName: file.name)
            }
        }
    }

    func uploadWallpaper(_ file: ImportedFile, overwrite: Bool, applyToLockScreen: Bool) async throws -> SdkWallpaperUploadResult {
        try await mutate("wallpapers.upload", operation: "上传壁纸") { client in
            try await client.uploadWallpaper(
                localPath: file.url.path,
                fileName: file.name,
                overwrite: overwrite,
                applyToLockScreen: applyToLockScreen
            )
        }
    }

    func deleteFile(_ file: SdkFileEntry) async throws {
        try await mutate("files.delete", operation: "删除文件") { try await $0.delete(path: file.path) }
    }

    func renameFile(_ file: SdkFileEntry, to name: String) async throws {
        try await mutate("files.rename", operation: "重命名") { try await $0.rename(path: file.path, newName: name) }
    }

    func moveFile(_ file: SdkFileEntry, to destination: String) async throws {
        try await mutate("files.move", operation: "移动文件") { try await $0.moveFile(path: file.path, destination: destination) }
    }

    func createDirectory(parent: String, name: String) async throws {
        try await mutate("directories.create", operation: "新建目录") { try await $0.createDirectory(parent: parent, name: name) }
    }

    func download(_ file: SdkFileEntry) async throws -> URL {
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("PicoManager-\(UUID().uuidString)-\(file.name)")
        try await mutate("files.download", operation: "下载") {
            try await $0.download(path: file.path, destination: destination.path)
        }
        return destination
    }

    func deleteWifiNetwork(_ network: SdkWifiNetwork) async throws {
        try await mutate("wifi.delete", operation: "删除 Wi-Fi") { try await $0.deleteWifiNetwork(index: network.index) }
    }

    func saveWifiNetwork(_ credential: SdkWifiCredential) async throws {
        try await mutate("wifi.save", operation: "保存 Wi-Fi") { try await $0.saveWifiNetwork(credential: credential) }
    }

    func deleteFontFamily(_ family: SdkFontFamily) async throws {
        try await mutate("fonts.delete", operation: "删除字体") { try await $0.deleteFontFamily(family: family.name) }
    }

    func deleteOpdsServer(_ server: SdkOpdsServer) async throws {
        try await mutate("opds.delete", operation: "删除 OPDS") { try await $0.deleteOpdsServer(index: server.index) }
    }

    func saveOpdsServer(_ credential: SdkOpdsCredential) async throws {
        try await mutate("opds.save", operation: "保存 OPDS") { try await $0.saveOpdsServer(credential: credential) }
    }

    func deleteWallpaper(_ file: SdkFileEntry) async throws {
        try await mutate("wallpapers.delete", operation: "删除壁纸") { try await $0.deleteWallpaper(fileName: file.name) }
    }

    func applySettings(_ expected: SdkSettingsSnapshot, changes: [SdkSettingChange]) async throws -> SdkSettingsSnapshot {
        try await mutate("settings.update", operation: "保存设置") {
            try await $0.applySettings(expected: expected, changes: changes)
        }
    }

    private var uploadConflictPolicy: SdkConflictPolicy {
        if supports("upload.backup-replace") { return .replaceWithBackup }
        if supports("upload.explicit-overwrite") { return .overwriteWhenSupported }
        return .fail
    }

    private func requireProfile(_ capability: String) throws -> SdkDeviceProfile {
        guard let profile else { throw ManagerError.deviceNotConnected }
        guard profile.capabilities.contains(capability) else { throw ManagerError.capabilityUnavailable(capability) }
        return profile
    }

    private func requireClient(_ capability: String) throws -> SdkDeviceClient {
        _ = try requireProfile(capability)
        guard let client else { throw ManagerError.deviceNotConnected }
        return client
    }

    private func mutate<T>(
        _ capability: String,
        operation: String,
        body: (SdkDeviceClient) async throws -> T
    ) async throws -> T {
        guard !isMutating else { throw ManagerError.operationInProgress }
        let currentClient = try requireClient(capability)
        isMutating = true
        activeOperation = operation
        defer {
            isMutating = false
            activeOperation = nil
        }
        return try await body(currentClient)
    }

    private func persistDevices() {
        guard let data = try? JSONEncoder().encode(savedDevices) else { return }
        storage.set(data, forKey: storageKey)
    }
}

private extension SdkConnectionFieldKind {
    var isToggle: Bool {
        if case .toggle = self { return true }
        return false
    }
}

func errorMessage(_ error: Error) -> String {
    guard let sdkError = error as? SdkOperationError else {
        return (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }
    return switch sdkError {
    case .InvalidArgument(let detail), .Unsupported(let detail), .Conflict(let detail),
         .RemoteFailure(let detail), .CommittedWithWarning(let detail),
         .CommittedButCleanupFailed(let detail), .RecoveryFailed(let detail):
        detail
    case .Unreachable:
        "无法连接设备，请检查地址和网络"
    case .Timeout:
        "连接超时"
    case .InsufficientStorage:
        "设备存储空间不足"
    }
}

nonisolated func stageImportedFile(from source: URL) throws -> ImportedFile {
    let didStartAccessing = source.startAccessingSecurityScopedResource()
    defer {
        if didStartAccessing { source.stopAccessingSecurityScopedResource() }
    }

    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("PicoManager-Inbox", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let name = source.lastPathComponent
    let destination = folder.appendingPathComponent("\(UUID().uuidString)-\(name)")
    let coordinator = NSFileCoordinator(filePresenter: nil)
    var coordinationError: NSError?
    var copyError: Error?
    coordinator.coordinate(readingItemAt: source, options: [], error: &coordinationError) { readableURL in
        do {
            try FileManager.default.copyItem(at: readableURL, to: destination)
        } catch {
            copyError = error
        }
    }
    if let copyError { throw copyError }
    if let coordinationError { throw coordinationError }
    return ImportedFile(name: name, url: destination)
}

func removeStagedFiles(_ files: [ImportedFile]) {
    files.forEach { try? FileManager.default.removeItem(at: $0.url) }
}
