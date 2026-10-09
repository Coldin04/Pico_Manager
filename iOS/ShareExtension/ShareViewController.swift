import SwiftUI
import UniformTypeIdentifiers
import UIKit

final class ShareViewController: UIViewController {
    private var model: ShareSendModel!

    override func viewDidLoad() {
        super.viewDidLoad()
        model = ShareSendModel(context: extensionContext)

        let host = UIHostingController(rootView: ShareSendView(model: model, manager: model.manager))
        addChild(host)
        view.addSubview(host.view)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)

        Task { await model.load(inputItems: extensionContext?.inputItems ?? []) }
    }
}

@MainActor
private final class ShareSendModel: ObservableObject {
    @Published var files: [ImportedFile] = []
    @Published var selectedDeviceID = ""
    @Published var selectedDirectory: String?
    @Published var isLoading = true
    @Published var isConnecting = false
    @Published var showingDirectoryPicker = false
    @Published var showingFormatReview = false
    @Published var showingCompletion = false
    @Published var error: String?

    let manager = DeviceManager()
    private weak var extensionContext: NSExtensionContext?

    init(context: NSExtensionContext?) {
        extensionContext = context
        selectedDeviceID = manager.savedDevices.first?.id ?? ""
    }

    var acceptableFiles: [ImportedFile] {
        guard let formats = manager.profile?.fileFormats else { return [] }
        if formats.acceptsAnyUploadFormat { return files }
        let accepted = Set(formats.uploadExtensions.map(normalizeExtension))
        return files.filter { accepted.contains($0.fileExtension) }
    }

    var unsupportedFiles: [ImportedFile] { files.filter { !acceptableFiles.contains($0) } }

    var unreadableFiles: [ImportedFile] {
        let readable = Set(manager.profile?.fileFormats.readableExtensions.map(normalizeExtension) ?? [])
        return acceptableFiles.filter { !readable.contains($0.fileExtension) }
    }

    var canChooseDirectory: Bool {
        guard let profile = manager.profile else { return false }
        return profile.capabilities.contains("upload.target-directory") &&
            profile.capabilities.contains("files.list") &&
            profile.constraints.canChooseUploadDirectory &&
            profile.constraints.canListDirectories
    }

    var uploadLocation: SdkFileLocation {
        guard canChooseDirectory, let selectedDirectory else { return .root }
        return .directory(path: selectedDirectory)
    }

    var formatReviewMessage: String {
        var messages: [String] = []
        if !unsupportedFiles.isEmpty { messages.append("将跳过 \(unsupportedFiles.count) 个设备不支持的文件") }
        if !unreadableFiles.isEmpty { messages.append("\(unreadableFiles.count) 个文件可能无法由设备直接阅读") }
        return messages.joined(separator: "；")
    }

    func load(inputItems: [Any]) async {
        defer { isLoading = false }
        let providers = inputItems
            .compactMap { $0 as? NSExtensionItem }
            .flatMap { $0.attachments ?? [] }
        guard !providers.isEmpty else {
            error = "没有可发送的文件"
            return
        }

        var staged: [ImportedFile] = []
        do {
            for provider in providers {
                staged.append(try await stageFile(from: provider))
            }
            files = staged
        } catch {
            removeStagedFiles(staged)
            files = []
            self.error = errorMessage(error)
        }
    }

    func connectSelectedDevice() async {
        guard let device = manager.savedDevices.first(where: { $0.id == selectedDeviceID }) else {
            error = "请先在 Pico Manager 的设备页面添加设备"
            return
        }
        isConnecting = true
        error = nil
        defer { isConnecting = false }
        do {
            if manager.isConnected { manager.disconnect() }
            try await manager.connect(device)
        } catch {
            self.error = errorMessage(error)
        }
    }

    func beginUpload() async {
        guard manager.supports("files.upload"), !acceptableFiles.isEmpty else {
            error = manager.isConnected ? "设备不支持上传这些文件" : "请先连接设备"
            return
        }
        if !unsupportedFiles.isEmpty || !unreadableFiles.isEmpty {
            showingFormatReview = true
            return
        }
        await uploadFiles()
    }

    func confirmUpload() {
        Task { await uploadFiles() }
    }

    func uploadFiles() async {
        guard manager.supports("files.upload"), !acceptableFiles.isEmpty else { return }
        error = nil
        for file in acceptableFiles {
            do {
                _ = try await manager.upload(file, to: uploadLocation)
                removeStagedFiles([file])
                files.removeAll { $0.id == file.id }
            } catch {
                self.error = errorMessage(error)
                removeStagedFiles(files)
                files = []
                return
            }
        }
        removeStagedFiles(files)
        files = []
        showingCompletion = true
    }

    func finish() {
        removeStagedFiles(files)
        files = []
        extensionContext?.completeRequest(returningItems: nil)
    }

    func cancel() {
        removeStagedFiles(files)
        files = []
        extensionContext?.cancelRequest(withError: NSError(
            domain: "com.cold04.InkReaderMgr.ShareExtension",
            code: NSUserCancelledError
        ))
    }

    func openDeviceConfiguration() {
        guard let url = URL(string: "inkreadermgr://devices"),
              let extensionContext else {
            error = "请先取消分享，再打开 Pico Manager 添加设备"
            return
        }

        extensionContext.open(url) { [weak self] didOpen in
            guard !didOpen else { return }
            Task { @MainActor [weak self] in
                self?.error = "系统未能从分享页打开 Pico Manager，请取消分享后手动添加设备"
            }
        }
    }

    private func stageFile(from provider: NSItemProvider) async throws -> ImportedFile {
        guard let typeIdentifier = provider.registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .item) == true
        }) ?? provider.registeredTypeIdentifiers.first else {
            throw ShareImportError.unsupportedItem
        }

        return try await withCheckedThrowingContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, error in
                guard let url else {
                    continuation.resume(throwing: error ?? ShareImportError.unavailableItem)
                    return
                }
                do {
                    continuation.resume(returning: try stageImportedFile(from: url))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}

private enum ShareImportError: LocalizedError {
    case unsupportedItem
    case unavailableItem

    var errorDescription: String? {
        switch self {
        case .unsupportedItem: "不支持此分享内容"
        case .unavailableItem: "无法读取分享的文件"
        }
    }
}

private struct ShareSendView: View {
    @ObservedObject var model: ShareSendModel
    @ObservedObject var manager: DeviceManager

    private var canUpload: Bool {
        manager.supports("files.upload") && !model.acceptableFiles.isEmpty && !manager.isMutating
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("设备") {
                    if manager.savedDevices.isEmpty {
                        Text("请先在 Pico Manager 的设备页面添加设备")
                            .foregroundStyle(.secondary)
                    } else {
                        Picker("设备", selection: $model.selectedDeviceID) {
                            ForEach(manager.savedDevices) { device in
                                Text(manager.address(for: device).isEmpty
                                     ? manager.deviceName(for: device.deviceType)
                                     : manager.address(for: device))
                                    .tag(device.id)
                            }
                        }
                        if manager.isConnected {
                            Label("已连接", systemImage: "checkmark.circle.fill")
                                .foregroundStyle(.tint)
                        } else {
                            Button(model.isConnecting ? "正在连接…" : "连接设备") {
                                Task { await model.connectSelectedDevice() }
                            }
                            .disabled(model.isConnecting || manager.isMutating)
                        }
                    }

                    Button("添加设备", systemImage: "plus") {
                        model.openDeviceConfiguration()
                    }
                }

                Section("文件") {
                    if model.isLoading {
                        ProgressView("正在读取文件…")
                    } else if model.files.isEmpty {
                        Text("没有可发送的文件")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(model.files) { file in
                            Label(file.name, systemImage: "doc")
                                .lineLimit(1)
                        }
                    }

                    if manager.isConnected && model.unsupportedFiles.count > 0 {
                        Text("设备不支持上传所选文件格式")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else if manager.isConnected && !model.unreadableFiles.isEmpty {
                        Text("此文件可能无法由设备直接阅读")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    if model.canChooseDirectory {
                        Button {
                            model.showingDirectoryPicker = true
                        } label: {
                            LabeledContent("目标目录", value: model.selectedDirectory ?? "默认位置")
                        }
                        .foregroundStyle(.primary)
                    }
                }

            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Group {
                    if manager.isMutating {
                        VStack(spacing: 8) {
                            if let progress = manager.uploadProgress, progress.total > 0 {
                                ProgressView(value: Double(progress.sent), total: Double(progress.total))
                                    .progressViewStyle(.linear)
                                    .accessibilityLabel("发送进度")
                                    .accessibilityValue("\(Int(Double(progress.sent) / Double(progress.total) * 100))%")
                                Text("发送中 · \(ByteCountFormatter.string(fromByteCount: Int64(progress.sent), countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: Int64(progress.total), countStyle: .file))")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            } else {
                                ProgressView("\(manager.activeOperation ?? "发送")中…")
                            }
                        }
                        .frame(maxWidth: .infinity)
                    } else {
                        Button {
                            Task { await model.beginUpload() }
                        } label: {
                            Text("发送")
                                .frame(maxWidth: .infinity)
                        }
                        .font(.body)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .disabled(!canUpload || model.isLoading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 12)
                .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea(edges: .bottom))
            }
            .navigationTitle("推书")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: model.selectedDeviceID) { _, selectedID in
                if manager.activeDevice?.id != selectedID {
                    manager.disconnect()
                    model.selectedDirectory = nil
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { model.cancel() }
                        .disabled(manager.isMutating || model.isConnecting)
                }
            }
            .confirmationDialog("文件格式提示", isPresented: $model.showingFormatReview, titleVisibility: .visible) {
                Button("继续发送") { model.confirmUpload() }
                Button("取消", role: .cancel) {}
            } message: {
                Text(model.formatReviewMessage)
            }
            .sheet(isPresented: $model.showingDirectoryPicker) {
                ShareDirectoryPicker(manager: model.manager, selectedPath: $model.selectedDirectory)
            }
            .alert("发送完成", isPresented: $model.showingCompletion) {
                Button("完成") { model.finish() }
            }
            .alert("无法继续", isPresented: Binding(
                get: { model.error != nil },
                set: { if !$0 { model.error = nil } }
            )) {
                Button("关闭", role: .cancel) { model.error = nil }
            } message: {
                Text(model.error ?? "")
            }
        }
    }
}

private struct ShareDirectoryPicker: View {
    @ObservedObject var manager: DeviceManager
    @Binding var selectedPath: String?
    @Environment(\.dismiss) private var dismiss
    @State private var path = ""
    @State private var entries: [SdkFileEntry] = []
    @State private var error: String?
    @State private var loading = false

    var body: some View {
        NavigationStack {
            List {
                Button {
                    choose(nil)
                } label: {
                    Text("默认位置")
                        .foregroundStyle(.primary)
                }
                .buttonStyle(.plain)
                ForEach(entries.filter { if case .directory = $0.kind { return true }; return false }, id: \.path) { entry in
                    Button {
                        path = entry.path
                        Task { await refresh() }
                    } label: {
                        Label(entry.name, systemImage: "folder")
                            .foregroundStyle(.primary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .overlay { if loading { ProgressView() } }
            .navigationTitle(path.isEmpty ? "目标目录" : URL(filePath: "/\(path)").lastPathComponent)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("选择") { choose(path.isEmpty ? nil : path) } }
            }
            .task { await refresh() }
            .alert("无法读取目录", isPresented: Binding(
                get: { error != nil },
                set: { if !$0 { error = nil } }
            )) {
                Button("关闭", role: .cancel) { error = nil }
            } message: { Text(error ?? "") }
        }
    }

    @MainActor
    private func refresh() async {
        loading = true
        defer { loading = false }
        do { entries = try await manager.listFiles(path.isEmpty ? .root : .directory(path: path)) }
        catch let failure { error = errorMessage(failure) }
    }

    private func choose(_ next: String?) {
        selectedPath = next
        dismiss()
    }
}

private func normalizeExtension(_ value: String) -> String {
    value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ".", with: "").lowercased()
}
