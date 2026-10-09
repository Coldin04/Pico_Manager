import SwiftUI
import UniformTypeIdentifiers

struct WifiManagementView: View {
    @ObservedObject var manager: DeviceManager
    @State private var networks: [SdkWifiNetwork] = []
    @State private var loading = false
    @State private var showingEditor = false
    @State private var editingNetwork: SdkWifiNetwork?
    @State private var error: String?

    var body: some View {
        NativeSwipeTable(items: networks, content: wifiRow, onSelect: { _ in }, onRefresh: { await refresh() }) { network in
            guard !manager.isMutating else { return [] }
            var actions: [NativeSwipeTableAction] = []
            if manager.supports("wifi.delete") {
                actions.append(NativeSwipeTableAction(title: "删除", symbol: "trash", style: .destructive, backgroundColor: .systemRed) { presenter, completion in
                    completion(false)
                    presentDestructiveConfirmation(from: presenter, title: "删除 Wi-Fi？", message: network.ssid) {
                        deleteNetwork(network)
                    }
                })
            }
            if manager.supports("wifi.save") {
                actions.append(NativeSwipeTableAction(title: "编辑", symbol: "pencil", style: .normal, backgroundColor: .systemBlue) { _, completion in
                    completion(false)
                    editingNetwork = network
                    showingEditor = true
                })
            }
            return actions
        }
        .overlay {
            if loading && networks.isEmpty {
                ProgressView().allowsHitTesting(false)
            } else if networks.isEmpty {
                ContentUnavailableView("没有已保存的 Wi-Fi 网络", systemImage: "wifi")
                    .fixedSize(horizontal: false, vertical: true)
                    .allowsHitTesting(false)
            }
        }
        .systemGroupedPageBackground()
        .navigationTitle("Wi-Fi 管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if manager.supports("wifi.save") {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { editingNetwork = nil; showingEditor = true } label: { Image(systemName: "plus") }
                        .disabled(manager.isMutating)
                        .accessibilityLabel("添加 Wi-Fi")
                }
            }
        }
        .task { await refresh() }
        .sheet(isPresented: $showingEditor, onDismiss: { editingNetwork = nil }) {
            WifiEditorView(manager: manager, network: editingNetwork) { Task { await refresh() } }
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
        }
        .managerErrorAlert(error: $error)
    }

    @MainActor
    private func refresh() async {
        loading = true
        defer { loading = false }
        do { networks = try await manager.listWifiNetworks(); error = nil }
        catch { self.error = errorMessage(error) }
    }

    private func wifiRow(_ network: SdkWifiNetwork) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(network.ssid)
            if network.isLastConnected { Text("最近连接").font(.caption).foregroundStyle(.secondary) }
            else if network.hasPassword { Text("已设置密码").font(.caption).foregroundStyle(.secondary) }
        }
    }

    private func deleteNetwork(_ network: SdkWifiNetwork) {
        Task {
            do { try await manager.deleteWifiNetwork(network); await refresh() }
            catch { self.error = errorMessage(error) }
        }
    }
}

private struct WifiEditorView: View {
    @ObservedObject var manager: DeviceManager
    var network: SdkWifiNetwork?
    var onSaved: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var ssid: String
    @State private var password = ""
    @State private var error: String?

    init(manager: DeviceManager, network: SdkWifiNetwork?, onSaved: @escaping () -> Void) {
        self.manager = manager
        self.network = network
        self.onSaved = onSaved
        _ssid = State(initialValue: network?.ssid ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("网络名称", text: $ssid).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("密码", text: $password)
            }
            .systemGroupedForm()
            .navigationTitle(network == nil ? "添加 Wi-Fi" : "编辑 Wi-Fi")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save).disabled(ssid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || manager.isMutating)
                }
            }
        }
        .alert("Wi-Fi 操作失败", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("关闭", role: .cancel) { error = nil }
        } message: { Text(error ?? "") }
    }

    private func save() {
        let trimmedPassword = password.isEmpty ? nil : password
        Task {
            do {
                try await manager.saveWifiNetwork(SdkWifiCredential(
                    index: network?.index,
                    ssid: ssid.trimmingCharacters(in: .whitespacesAndNewlines),
                    password: trimmedPassword
                ))
                onSaved()
                dismiss()
            } catch { self.error = errorMessage(error) }
        }
    }
}

struct DeviceInformationView: View {
    @ObservedObject var manager: DeviceManager
    @State private var fields: [SdkDeviceInfoField] = []
    @State private var error: String?
    @State private var loading = false

    var body: some View {
        List {
            ForEach(fields, id: \.key) { field in
                LabeledContent(deviceInfoLabel(field.key), value: formatDeviceInfo(field))
            }
        }
        .systemInsetGroupedList()
        .overlay {
            if loading && fields.isEmpty {
                ProgressView().allowsHitTesting(false)
            } else if fields.isEmpty {
                ContentUnavailableView("没有设备信息", systemImage: "info.circle")
                    .fixedSize(horizontal: false, vertical: true)
                    .allowsHitTesting(false)
            }
        }
        .refreshable { await refresh() }
        .navigationTitle("设备信息")
        .navigationBarTitleDisplayMode(.inline)
        .task { await refresh() }
        .managerErrorAlert(error: $error)
    }

    @MainActor
    private func refresh() async {
        loading = true
        defer { loading = false }
        do { fields = try await manager.deviceInfo(); error = nil }
        catch { self.error = errorMessage(error) }
    }
}

struct DeviceSettingsView: View {
    @ObservedObject var manager: DeviceManager
    @State private var snapshot: SdkSettingsSnapshot?
    @State private var edits: [String: SdkSettingValue] = [:]
    @State private var error: String?
    @State private var loading = false

    private var categories: [String] {
        Array(Set(snapshot?.settings.map(\.category) ?? [])).sorted()
    }

    var body: some View {
        Form {
            if let snapshot, snapshot.settings.isEmpty {
                ContentUnavailableView("没有可调整的设置", systemImage: "gearshape")
            } else if let snapshot {
                ForEach(categories, id: \.self) { category in
                    Section(category) {
                        ForEach(snapshot.settings.filter { $0.category == category }, id: \.key) { setting in
                            settingRow(setting)
                        }
                    }
                }
            }
        }
        .systemGroupedForm()
        .overlay {
            if loading && snapshot == nil {
                ProgressView().allowsHitTesting(false)
            }
        }
        .navigationTitle("设备设置")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if manager.supports("settings.update") {
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save).disabled(edits.isEmpty || manager.isMutating)
                }
            }
        }
        .task { await refresh() }
        .managerErrorAlert(error: $error)
    }

    @ViewBuilder
    private func settingRow(_ descriptor: SdkSettingDescriptor) -> some View {
        let value = edits[descriptor.key] ?? descriptor.value
        let editable = manager.supports("settings.update")
        switch (descriptor.kind, value) {
        case (.toggle, .toggle(let enabled)):
            Toggle(descriptor.name, isOn: Binding(
                get: { if case .toggle(let next) = edits[descriptor.key] ?? descriptor.value { return next }; return enabled },
                set: { edits[descriptor.key] = .toggle(value: $0) }
            )).disabled(!editable || manager.isMutating)
        case (.choice(let options), .choice(let selected)):
            Picker(descriptor.name, selection: Binding(
                get: { if case .choice(let next) = edits[descriptor.key] ?? descriptor.value { return next }; return selected },
                set: { edits[descriptor.key] = .choice(index: $0) }
            )) {
                ForEach(options.indices, id: \.self) { index in Text(options[index]).tag(UInt32(index)) }
            }
            .disabled(!editable || manager.isMutating)
        case (.number(let minimum, let maximum, let step), .number(let number)):
            HStack {
                Text(descriptor.name)
                Spacer()
                Button { edits[descriptor.key] = .number(value: max(minimum, number - step)) } label: { Image(systemName: "minus.circle") }
                    .disabled(!editable || manager.isMutating || number <= minimum)
                Text("\(number)").monospacedDigit().frame(minWidth: 32)
                Button { edits[descriptor.key] = .number(value: min(maximum, number + step)) } label: { Image(systemName: "plus.circle") }
                    .disabled(!editable || manager.isMutating || number >= maximum)
            }
        case (.text, .text(let text)):
            TextField(descriptor.name, text: Binding(
                get: { if case .text(let next) = edits[descriptor.key] ?? descriptor.value { return next }; return text },
                set: { edits[descriptor.key] = .text(value: $0) }
            )).disabled(!editable || manager.isMutating)
        default:
            LabeledContent(descriptor.name, value: String(describing: value))
        }
    }

    @MainActor
    private func refresh() async {
        loading = true
        defer { loading = false }
        do { snapshot = try await manager.listSettings(); edits = [:]; error = nil }
        catch { self.error = errorMessage(error) }
    }

    private func save() {
        guard let snapshot else { return }
        let changes = snapshot.settings.compactMap { setting -> SdkSettingChange? in
            guard let value = edits[setting.key], value != setting.value else { return nil }
            return SdkSettingChange(key: setting.key, value: value)
        }
        guard !changes.isEmpty else { return }
        Task {
            do { self.snapshot = try await manager.applySettings(snapshot, changes: changes); edits = [:] }
            catch { self.error = errorMessage(error) }
        }
    }
}

struct FontManagementView: View {
    @ObservedObject var manager: DeviceManager
    @State private var catalog: SdkFontCatalog?
    @State private var stagedFont: ImportedFile?
    @State private var familyName = ""
    @State private var showingImporter = false
    @State private var pendingOverwrite = false
    @State private var error: String?
    @State private var loading = false

    private var fontExtensions: [String] { manager.profile?.fileFormats.fontUploadExtensions.map(normalizeFeatureExtension) ?? [] }
    private var fontTypes: [UTType] { fontExtensions.compactMap { UTType(filenameExtension: $0) } }
    private var canUpload: Bool { manager.supports("fonts.upload") && !fontExtensions.isEmpty }
    private var familyRequired: Bool { manager.supports("fonts.upload.family") }

    var body: some View {
        NativeSwipeTable(items: catalog?.families ?? [], content: fontRow, onSelect: { _ in }, onRefresh: { await refresh() }) { family in
            guard manager.supports("fonts.delete"), !manager.isMutating else { return [] }
            return [NativeSwipeTableAction(title: "删除", symbol: "trash", style: .destructive, backgroundColor: .systemRed) { presenter, completion in
                completion(false)
                presentDestructiveConfirmation(from: presenter, title: "删除字体族？", message: family.name) {
                    deleteFont(family)
                }
            }]
        }
        .overlay {
            if loading && catalog == nil {
                ProgressView().allowsHitTesting(false)
            } else if let catalog, catalog.families.isEmpty {
                ContentUnavailableView("没有已安装的字体", systemImage: "textformat")
                    .fixedSize(horizontal: false, vertical: true)
                    .allowsHitTesting(false)
            }
        }
        .systemGroupedPageBackground()
        .navigationTitle("字体管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if canUpload {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingImporter = true } label: { Image(systemName: "plus") }
                        .disabled(manager.isMutating)
                        .accessibilityLabel("上传字体")
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let stagedFont, canUpload {
                VStack(spacing: 8) {
                    TextField(familyRequired ? "字体族" : "字体族（可选）", text: $familyName)
                        .textFieldStyle(.roundedBorder)
                    Button("上传 \(stagedFont.name)") { upload(overwrite: false) }
                        .font(.body)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.regular)
                        .disabled(manager.isMutating || (familyRequired && familyName.isEmpty))
                }
                .padding()
                .background(.bar)
            }
        }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: fontTypes.isEmpty ? [.item] : fontTypes, allowsMultipleSelection: false) { result in
            receiveFont(result)
        }
        .alert("替换现有字体？", isPresented: $pendingOverwrite) {
            Button("替换", role: .destructive) { upload(overwrite: true) }
            Button("取消", role: .cancel) { cleanupStagedFont() }
        } message: { Text(stagedFont?.name ?? "") }
        .task { await refresh() }
        .managerErrorAlert(error: $error)
    }

    @MainActor
    private func refresh() async {
        loading = true
        defer { loading = false }
        do { catalog = try await manager.listFonts(); error = nil }
        catch { self.error = errorMessage(error) }
    }

    private func receiveFont(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else { return }
        guard fontExtensions.contains(normalizeFeatureExtension(url.pathExtension)) else {
            error = "请选择设备支持的字体：\(fontExtensions.map { ".\($0)" }.joined(separator: "、"))"
            return
        }
        Task {
            do {
                let staged = try await Task.detached(priority: .userInitiated) { try stageImportedFile(from: url) }.value
                cleanupStagedFont()
                stagedFont = staged
                familyName = ""
            } catch { self.error = errorMessage(error) }
        }
    }

    private func upload(overwrite: Bool) {
        guard let stagedFont else { return }
        Task {
            do {
                try await manager.uploadFont(stagedFont, family: familyName.trimmingCharacters(in: .whitespacesAndNewlines), overwrite: overwrite)
                cleanupStagedFont()
                await refresh()
            } catch let sdkError as SdkOperationError {
                if case .Conflict = sdkError, !overwrite, manager.supports("upload.explicit-overwrite") {
                    pendingOverwrite = true
                } else { self.error = errorMessage(sdkError); cleanupStagedFont() }
            } catch { self.error = errorMessage(error); cleanupStagedFont() }
        }
    }

    private func fontRow(_ family: SdkFontFamily) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(family.name)
            if !family.files.isEmpty { Text(family.files.map(\.name).joined(separator: "、")).font(.footnote).foregroundStyle(.secondary) }
        }
    }

    private func deleteFont(_ family: SdkFontFamily) {
        Task {
            do { try await manager.deleteFontFamily(family); await refresh() }
            catch { self.error = errorMessage(error) }
        }
    }

    private func cleanupStagedFont() {
        if let stagedFont { removeStagedFiles([stagedFont]) }
        stagedFont = nil
    }
}

struct OpdsManagementView: View {
    @ObservedObject var manager: DeviceManager
    @State private var servers: [SdkOpdsServer] = []
    @State private var loading = false
    @State private var showingEditor = false
    @State private var editingServer: SdkOpdsServer?
    @State private var error: String?

    var body: some View {
        NativeSwipeTable(items: servers, content: opdsRow, onSelect: { _ in }, onRefresh: { await refresh() }) { server in
            guard !manager.isMutating else { return [] }
            var actions: [NativeSwipeTableAction] = []
            if manager.supports("opds.delete") {
                actions.append(NativeSwipeTableAction(title: "删除", symbol: "trash", style: .destructive, backgroundColor: .systemRed) { presenter, completion in
                    completion(false)
                    presentDestructiveConfirmation(from: presenter, title: "删除 OPDS 书库？", message: server.name) {
                        deleteServer(server)
                    }
                })
            }
            if manager.supports("opds.save") {
                actions.append(NativeSwipeTableAction(title: "编辑", symbol: "pencil", style: .normal, backgroundColor: .systemBlue) { _, completion in
                    completion(false)
                    editingServer = server
                    showingEditor = true
                })
            }
            return actions
        }
        .overlay {
            if loading && servers.isEmpty {
                ProgressView().allowsHitTesting(false)
            } else if servers.isEmpty {
                ContentUnavailableView("没有已保存的 OPDS 书库", systemImage: "books.vertical")
                    .fixedSize(horizontal: false, vertical: true)
                    .allowsHitTesting(false)
            }
        }
        .systemGroupedPageBackground()
        .navigationTitle("OPDS 管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if manager.supports("opds.save") {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { editingServer = nil; showingEditor = true } label: { Image(systemName: "plus") }
                        .disabled(manager.isMutating)
                        .accessibilityLabel("添加 OPDS")
                }
            }
        }
        .sheet(isPresented: $showingEditor, onDismiss: { editingServer = nil }) {
            OpdsEditorView(manager: manager, server: editingServer) { Task { await refresh() } }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .task { await refresh() }
        .managerErrorAlert(error: $error)
    }

    @MainActor
    private func refresh() async {
        loading = true
        defer { loading = false }
        do { servers = try await manager.listOpdsServers(); error = nil }
        catch { self.error = errorMessage(error) }
    }

    private func opdsRow(_ server: SdkOpdsServer) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(server.name)
            Text(server.url).font(.subheadline).foregroundStyle(.secondary)
            if !server.username.isEmpty { Text(server.username).font(.caption).foregroundStyle(.secondary) }
            if server.hasPassword { Text("已设置密码").font(.caption).foregroundStyle(.secondary) }
        }
    }

    private func deleteServer(_ server: SdkOpdsServer) {
        Task {
            do { try await manager.deleteOpdsServer(server); await refresh() }
            catch { self.error = errorMessage(error) }
        }
    }
}

private struct OpdsEditorView: View {
    @ObservedObject var manager: DeviceManager
    var server: SdkOpdsServer?
    var onSaved: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var url: String
    @State private var username: String
    @State private var password = ""
    @State private var error: String?

    init(manager: DeviceManager, server: SdkOpdsServer?, onSaved: @escaping () -> Void) {
        self.manager = manager
        self.server = server
        self.onSaved = onSaved
        _name = State(initialValue: server?.name ?? "")
        _url = State(initialValue: server?.url ?? "")
        _username = State(initialValue: server?.username ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("名称", text: $name)
                TextField("URL", text: $url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("用户名", text: $username).textInputAutocapitalization(.never)
                SecureField("密码", text: $password)
            }
            .systemGroupedForm()
            .navigationTitle(server == nil ? "添加 OPDS" : "编辑 OPDS")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save).disabled(name.isEmpty || url.isEmpty || manager.isMutating)
                }
            }
        }
        .managerErrorAlert(error: $error)
    }

    private func save() {
        Task {
            do {
                try await manager.saveOpdsServer(SdkOpdsCredential(
                    index: server?.index,
                    name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                    url: url.trimmingCharacters(in: .whitespacesAndNewlines),
                    username: username,
                    password: password.isEmpty ? nil : password
                ))
                onSaved()
                dismiss()
            } catch { self.error = errorMessage(error) }
        }
    }
}

struct WallpaperManagementView: View {
    @ObservedObject var manager: DeviceManager
    @State private var wallpapers: [SdkFileEntry] = []
    @State private var showingImporter = false
    @State private var pendingOverwrite: ImportedFile?
    @State private var error: String?
    @State private var loading = false

    private var canList: Bool { manager.supports("wallpapers.manage") }
    private var canUpload: Bool {
        manager.supports("wallpapers.upload") && !(manager.profile?.fileFormats.wallpaperUploadExtensions.isEmpty ?? true)
    }
    private var canDelete: Bool { manager.supports("wallpapers.delete") }
    private var supportedExtensions: Set<String> {
        Set(manager.profile?.fileFormats.wallpaperUploadExtensions.map(normalizeFeatureExtension) ?? [])
    }

    var body: some View {
        NativeSwipeTable(items: canList ? wallpapers : [], content: wallpaperRow, onSelect: { _ in }, onRefresh: { await refresh() }) { wallpaper in
            guard canDelete, !manager.isMutating else { return [] }
            return [NativeSwipeTableAction(title: "删除", symbol: "trash", style: .destructive, backgroundColor: .systemRed) { presenter, completion in
                completion(false)
                presentDestructiveConfirmation(from: presenter, title: "删除壁纸？", message: wallpaper.name) {
                    deleteWallpaper(wallpaper)
                }
            }]
        }
        .systemGroupedPageBackground()
        .overlay {
            if canList && loading && wallpapers.isEmpty {
                ProgressView().allowsHitTesting(false)
            }
        }
        .navigationTitle("壁纸管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if canUpload {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingImporter = true } label: { Image(systemName: "plus") }
                        .disabled(manager.isMutating)
                        .accessibilityLabel("上传壁纸")
                }
            }
        }
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.image], allowsMultipleSelection: false) { result in
            receiveWallpaper(result)
        }
        .overlay {
            if canList && loading && wallpapers.isEmpty { ProgressView().allowsHitTesting(false) }
            else if canList && wallpapers.isEmpty { ContentUnavailableView("没有已保存的壁纸", systemImage: "photo").allowsHitTesting(false) }
            else if !canList && canUpload { ContentUnavailableView("尚无可浏览的壁纸列表", systemImage: "photo").allowsHitTesting(false) }
        }
        .alert("替换现有壁纸？", isPresented: Binding(
            get: { pendingOverwrite != nil },
            set: { if !$0 { pendingOverwrite = nil } }
        )) {
            Button("替换", role: .destructive) { uploadWallpaper(overwrite: true) }
            Button("取消", role: .cancel) { cleanupWallpaper() }
        } message: { Text(pendingOverwrite?.name ?? "") }
        .task { await refresh() }
        .managerErrorAlert(error: $error)
    }

    @MainActor
    private func refresh() async {
        guard canList else { return }
        loading = true
        defer { loading = false }
        do { wallpapers = try await manager.listWallpapers(); error = nil }
        catch { self.error = errorMessage(error) }
    }

    private func receiveWallpaper(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else { return }
        guard supportedExtensions.contains(normalizeFeatureExtension(url.pathExtension)) else {
            error = "设备不支持此壁纸格式"
            return
        }
        Task {
            do {
                let staged = try await Task.detached(priority: .userInitiated) { try stageImportedFile(from: url) }.value
                pendingOverwrite = nil
                uploadWallpaperFile(staged, overwrite: false)
            } catch { self.error = errorMessage(error) }
        }
    }

    private func uploadWallpaperFile(_ file: ImportedFile, overwrite: Bool) {
        Task {
            do {
                _ = try await manager.uploadWallpaper(file, overwrite: overwrite, applyToLockScreen: false)
                removeStagedFiles([file])
                await refresh()
            } catch let sdkError as SdkOperationError {
                if case .Conflict = sdkError, !overwrite { pendingOverwrite = file }
                else { self.error = errorMessage(sdkError); removeStagedFiles([file]) }
            } catch { self.error = errorMessage(error); removeStagedFiles([file]) }
        }
    }

    private func uploadWallpaper(overwrite: Bool) {
        guard let pendingOverwrite else { return }
        uploadWallpaperFile(pendingOverwrite, overwrite: overwrite)
        self.pendingOverwrite = nil
    }

    private func wallpaperRow(_ wallpaper: SdkFileEntry) -> some View {
        Label(wallpaper.name, systemImage: "photo")
    }

    private func deleteWallpaper(_ file: SdkFileEntry) {
        Task {
            do { try await manager.deleteWallpaper(file); await refresh() }
            catch { self.error = errorMessage(error) }
        }
    }

    private func cleanupWallpaper() {
        if let pendingOverwrite { removeStagedFiles([pendingOverwrite]) }
        pendingOverwrite = nil
    }
}

private extension View {
    func managerErrorAlert(error: Binding<String?>) -> some View {
        alert("操作失败", isPresented: Binding(
            get: { error.wrappedValue != nil },
            set: { if !$0 { error.wrappedValue = nil } }
        )) {
            Button("关闭", role: .cancel) { error.wrappedValue = nil }
        } message: { Text(error.wrappedValue ?? "") }
    }
}

private func deviceInfoLabel(_ key: String) -> String {
    switch key {
    case "storage_is_flash": "存储介质"
    case "storage_free_bytes": "可用存储空间"
    case "storage_file_limit": "文件数量上限"
    case "storage_root": "存储目录"
    case "network_mode": "网络模式"
    case "wifi_configured": "Wi-Fi"
    case "wifi_ssid": "Wi-Fi 名称"
    case "firmware_version": "固件版本"
    case "ip_address": "IP 地址"
    case "wifi_rssi": "Wi-Fi 信号"
    case "free_heap": "可用内存"
    case "uptime": "运行时间"
    case "device_model": "设备型号"
    default: key
    }
}

private func formatDeviceInfo(_ field: SdkDeviceInfoField) -> String {
    return switch field.key {
    case "storage_is_flash": field.value == "true" ? "闪存" : "非闪存"
    case "storage_free_bytes", "free_heap":
        if let bytes = UInt64(field.value) { ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .memory) }
        else { field.value }
    case "wifi_configured": field.value == "true" ? "已配置" : "未配置"
    case "network_mode":
        switch field.value.lowercased() {
        case "ap": "热点"
        case "sta", "station": "Wi-Fi"
        default: field.value
        }
    case "wifi_rssi": Int(field.value).map { "\($0) dBm" } ?? field.value
    case "uptime": formatUptime(field.value)
    default: field.value
    }
}

private func formatUptime(_ value: String) -> String {
    guard let seconds = UInt64(value) else { return value }
    let days = seconds / 86_400
    let hours = seconds % 86_400 / 3_600
    let minutes = seconds % 3_600 / 60
    let remaining = seconds % 60
    var parts: [String] = []
    if days > 0 { parts.append("\(days)天") }
    if hours > 0 || !parts.isEmpty { parts.append("\(hours)小时") }
    if minutes > 0 || !parts.isEmpty { parts.append("\(minutes)分") }
    parts.append("\(remaining)秒")
    return parts.joined()
}

private func normalizeFeatureExtension(_ value: String) -> String {
    value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ".", with: "").lowercased()
}
