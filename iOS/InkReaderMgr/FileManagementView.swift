import SwiftUI
import UniformTypeIdentifiers

struct FileManagementView: View {
    @ObservedObject var manager: DeviceManager
    var showDevices: () -> Void
    var currentPath: String = ""

    @State private var entries: [SdkFileEntry] = []
    @State private var loading = false
    @State private var error: String?
    @State private var pendingRename: SdkFileEntry?
    @State private var pendingMove: SdkFileEntry?
    @State private var editValue = ""
    @State private var showingCreateDirectory = false
    @State private var showingMovePicker = false
    @State private var moveDestination: String?
    @State private var downloadedFile: URL?
    @State private var showingUploadPicker = false
    @State private var stagedUploadFiles: [ImportedFile] = []
    @State private var showingUploadReview = false
    @State private var pendingUploadOverwrite: ImportedFile?
    @State private var uploadInProgress = false
    @State private var uploadError: String?
    @State private var selectedDirectoryPath: String?

    private var canChooseUploadDirectory: Bool {
        guard let profile = manager.profile else { return false }
        return profile.capabilities.contains("upload.target-directory") &&
            profile.capabilities.contains("files.list") &&
            profile.constraints.canChooseUploadDirectory &&
            profile.constraints.canListDirectories
    }

    private var uploadLocation: SdkFileLocation {
        guard canChooseUploadDirectory, !currentPath.isEmpty else { return .root }
        return .directory(path: currentPath)
    }

    private var acceptableUploadFiles: [ImportedFile] {
        guard let formats = manager.profile?.fileFormats else { return [] }
        if formats.acceptsAnyUploadFormat { return stagedUploadFiles }
        let extensions = Set(formats.uploadExtensions.map(normalizeFileExtension))
        return stagedUploadFiles.filter { extensions.contains(normalizeFileExtension($0.fileExtension)) }
    }

    private var unsupportedUploadFiles: [ImportedFile] {
        stagedUploadFiles.filter { !acceptableUploadFiles.contains($0) }
    }

    private var unreadableUploadFiles: [ImportedFile] {
        let extensions = Set(manager.profile?.fileFormats.readableExtensions.map(normalizeFileExtension) ?? [])
        return acceptableUploadFiles.filter { !extensions.contains(normalizeFileExtension($0.fileExtension)) }
    }

    private var uploadReviewMessage: String {
        var messages: [String] = []
        if !unsupportedUploadFiles.isEmpty {
            messages.append("将跳过 \(unsupportedUploadFiles.count) 个设备不支持的文件")
        }
        if !unreadableUploadFiles.isEmpty {
            messages.append("\(unreadableUploadFiles.count) 个文件可能无法由设备直接阅读")
        }
        return messages.joined(separator: "；")
    }

    private var overwriteAlertIsPresented: Binding<Bool> {
        Binding<Bool>(
            get: { pendingUploadOverwrite != nil },
            set: { isPresented in
                guard !isPresented else { return }
                pendingUploadOverwrite = nil
            }
        )
    }

    var body: some View {
        Group {
            if !manager.isConnected {
                ContentUnavailableView {
                    Label {
                        Text("设备未连接")
                            .font(.body)
                    } icon: {
                        Image(systemName: "iphone")
                    }
                } description: {
                    Text("连接设备后查看文件")
                        .font(.body)
                        .foregroundStyle(.secondary)
                } actions: {
                    Button("连接设备", action: showDevices)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.regular)
                }
            } else if !manager.supports("files.list") {
                ContentUnavailableView("设备不支持文件管理", systemImage: "folder")
            } else if loading && entries.isEmpty {
                ProgressView()
            } else if let error, entries.isEmpty {
                ContentUnavailableView {
                    Label {
                        Text("无法读取文件")
                            .font(.body)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle")
                    }
                } description: {
                    Text(error)
                        .font(.body)
                        .foregroundStyle(.secondary)
                } actions: {
                    Button(action: refresh) {
                        Text("重试")
                            .font(.body)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.regular)
                }
            } else {
                NativeFileList(
                    entries: visibleEntries,
                    canRename: manager.supports("files.rename"),
                    canDelete: manager.supports("files.delete"),
                    canDownload: manager.supports("files.download"),
                    canMove: manager.supports("files.move"),
                    isMutating: manager.isMutating,
                    onRefresh: refreshAsync,
                    onOpenDirectory: { selectedDirectoryPath = $0 },
                    onRename: { entry in
                        pendingRename = entry
                        editValue = entry.name
                    },
                    onDelete: delete,
                    onDownload: download,
                    onMove: { entry in pendingMove = entry; moveDestination = nil; showingMovePicker = true }
                )
                .overlay {
                    if entries.isEmpty && !loading {
                        ContentUnavailableView("此目录为空", systemImage: "folder")
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .systemGroupedPageBackground()
        .navigationTitle(currentPath.isEmpty ? "文件管理" : currentPath.split(separator: "/").last.map(String.init) ?? "文件")
        .toolbarTitleDisplayMode(currentPath.isEmpty ? .large : .inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if currentPath.isEmpty {
                    DeviceStatusButton(manager: manager, action: showDevices)
                }
                if manager.isConnected && manager.supports("files.list") {
                    if manager.supports("files.upload") {
                        if uploadInProgress {
                            if let progress = manager.uploadProgress, progress.total > 0 {
                                ProgressView(value: Double(progress.sent), total: Double(progress.total))
                                    .progressViewStyle(.circular)
                                    .accessibilityLabel("上传进度")
                                    .accessibilityValue("\(Int(Double(progress.sent) / Double(progress.total) * 100))%")
                            } else {
                                ProgressView()
                                    .accessibilityLabel("正在上传")
                            }
                        } else {
                            Button { showingUploadPicker = true } label: { Image(systemName: "arrow.up.doc") }
                                .disabled(manager.isMutating)
                                .accessibilityLabel("上传文件")
                        }
                    }
                    if manager.supports("directories.create") {
                        Button { editValue = ""; showingCreateDirectory = true } label: { Image(systemName: "folder.badge.plus") }
                            .disabled(manager.isMutating)
                            .accessibilityLabel("新建目录")
                    }
                }
            }
        }
        .toolbar(currentPath.isEmpty ? .automatic : .visible, for: .navigationBar)
        .navigationDestination(item: $selectedDirectoryPath) { path in
            FileManagementView(manager: manager, showDevices: showDevices, currentPath: path)
        }
        .task(id: currentPath) { await refreshAsync() }
        .fileImporter(
            isPresented: $showingUploadPicker,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true,
            onCompletion: receiveUploadFiles
        )
        .alert("重命名", isPresented: Binding(
            get: { pendingRename != nil },
            set: { if !$0 { pendingRename = nil } }
        )) {
            TextField("名称", text: $editValue)
            Button("取消", role: .cancel) { pendingRename = nil }
            Button("保存") { renamePending() }
        }
        .alert("新建目录", isPresented: $showingCreateDirectory) {
            TextField("目录名称", text: $editValue)
            Button("取消", role: .cancel) {}
            Button("创建") { createDirectory() }
        }
        .sheet(isPresented: $showingMovePicker) {
            NavigationStack {
                TargetDirectoryPicker(manager: manager, selectedPath: $moveDestination) { destination in
                    moveDestination = destination ?? "/"
                    movePending()
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: Binding(
            get: { downloadedFile != nil },
            set: { if !$0 { cleanupDownload() } }
        )) {
            if let downloadedFile {
                NavigationStack {
                    VStack(spacing: 20) {
                        Image(systemName: "checkmark.circle.fill").font(.largeTitle).foregroundStyle(.green)
                        Text("下载完成")
                        ShareLink(item: downloadedFile) {
                            Label("保存或分享", systemImage: "square.and.arrow.up")
                        }
                        .font(.body)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.regular)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .systemGroupedPageBackground()
                    .navigationTitle("下载")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { cleanupDownload() } } }
                }
                .presentationDetents([.medium])
            }
        }
        .alert("文件操作失败", isPresented: Binding(
            get: { error != nil && !entries.isEmpty },
            set: { if !$0 { error = nil } }
        )) {
            Button("关闭", role: .cancel) { error = nil }
        } message: { Text(error ?? "") }
        .alert("文件格式提示", isPresented: $showingUploadReview) {
            Button("继续上传") { uploadSelectedFiles() }
            Button("取消", role: .cancel) { cancelUploadSelection() }
        } message: { Text(uploadReviewMessage) }
        .alert("替换现有文件？", isPresented: overwriteAlertIsPresented) {
            Button("替换", role: .destructive) {
                let file = pendingUploadOverwrite
                pendingUploadOverwrite = nil
                uploadSelectedFiles(overwriting: file)
            }
            Button("取消", role: .cancel) { pendingUploadOverwrite = nil; cancelUploadSelection() }
        } message: { Text(pendingUploadOverwrite?.name ?? "") }
        .alert("上传失败", isPresented: Binding(
            get: { uploadError != nil },
            set: { if !$0 { uploadError = nil } }
        )) {
            Button("关闭", role: .cancel) { uploadError = nil }
        } message: { Text(uploadError ?? "") }
    }

    private var visibleEntries: [SdkFileEntry] {
        entries.sorted { lhs, rhs in
            if lhs.kind.isDirectory != rhs.kind.isDirectory { return lhs.kind.isDirectory }
            return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
        }
    }

    private func refresh() {
        Task { await refreshAsync() }
    }

    @MainActor
    private func refreshAsync() async {
        guard manager.supports("files.list") else { return }
        loading = true
        defer { loading = false }
        do {
            entries = try await manager.listFiles(currentPath.isEmpty ? .root : .directory(path: currentPath))
            error = nil
        } catch {
            self.error = errorMessage(error)
        }
    }

    private func delete(_ entry: SdkFileEntry) {
        Task {
            do { try await manager.deleteFile(entry); await refreshAsync() }
            catch { self.error = errorMessage(error) }
        }
    }

    private func renamePending() {
        guard let entry = pendingRename else { return }
        pendingRename = nil
        let name = editValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { error = "名称不能为空"; return }
        Task {
            do { try await manager.renameFile(entry, to: name); await refreshAsync() }
            catch { self.error = errorMessage(error) }
        }
    }

    private func createDirectory() {
        let name = editValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { error = "目录名称不能为空"; return }
        Task {
            do { try await manager.createDirectory(parent: currentPath.isEmpty ? "/" : currentPath, name: name); await refreshAsync() }
            catch { self.error = errorMessage(error) }
        }
    }

    private func movePending() {
        guard let entry = pendingMove, let destination = moveDestination else { return }
        pendingMove = nil
        Task {
            do { try await manager.moveFile(entry, to: destination); await refreshAsync() }
            catch { self.error = errorMessage(error) }
        }
    }

    private func download(_ entry: SdkFileEntry) {
        Task {
            do { downloadedFile = try await manager.download(entry) }
            catch { self.error = errorMessage(error) }
        }
    }

    private func cleanupDownload() {
        if let downloadedFile { try? FileManager.default.removeItem(at: downloadedFile) }
        downloadedFile = nil
    }

    private func receiveUploadFiles(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            Task {
                do {
                    let staged = try await Task.detached(priority: .userInitiated) {
                        var copied: [ImportedFile] = []
                        do {
                            for url in urls { copied.append(try stageImportedFile(from: url)) }
                            return copied
                        } catch {
                            copied.forEach { try? FileManager.default.removeItem(at: $0.url) }
                            throw error
                        }
                    }.value
                    removeStagedFiles(stagedUploadFiles)
                    stagedUploadFiles = staged
                    guard !acceptableUploadFiles.isEmpty else {
                        uploadError = "设备不支持所选文件格式"
                        cancelUploadSelection()
                        return
                    }
                    if !unsupportedUploadFiles.isEmpty || !unreadableUploadFiles.isEmpty {
                        showingUploadReview = true
                    } else {
                        uploadSelectedFiles()
                    }
                } catch {
                    uploadError = errorMessage(error)
                }
            }
        case .failure(let error):
            let nsError = error as NSError
            if nsError.code != NSUserCancelledError { uploadError = errorMessage(error) }
        }
    }

    private func uploadSelectedFiles(overwriting fileToOverwrite: ImportedFile? = nil) {
        guard manager.supports("files.upload"), !acceptableUploadFiles.isEmpty else { return }
        uploadInProgress = true
        Task {
            for file in acceptableUploadFiles {
                do {
                    _ = try await manager.upload(file, to: uploadLocation, overwrite: file.id == fileToOverwrite?.id)
                    removeStagedFiles([file])
                    stagedUploadFiles.removeAll { $0.id == file.id }
                } catch let sdkError as SdkOperationError {
                    if case .Conflict = sdkError,
                       !manager.supports("upload.backup-replace"),
                       manager.supports("upload.explicit-overwrite"),
                       file.id != fileToOverwrite?.id {
                        uploadInProgress = false
                        pendingUploadOverwrite = file
                        return
                    }
                    uploadError = errorMessage(sdkError)
                    await refreshAsync()
                    finishUploadSelection()
                    return
                } catch {
                    uploadError = errorMessage(error)
                    await refreshAsync()
                    finishUploadSelection()
                    return
                }
            }
            await refreshAsync()
            finishUploadSelection()
        }
    }

    private func cancelUploadSelection() {
        guard !uploadInProgress else { return }
        finishUploadSelection()
    }

    private func finishUploadSelection() {
        removeStagedFiles(stagedUploadFiles)
        stagedUploadFiles = []
        pendingUploadOverwrite = nil
        showingUploadReview = false
        uploadInProgress = false
        manager.uploadProgress = nil
    }
}

private func normalizeFileExtension(_ extensionName: String) -> String {
    extensionName.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: ".", with: "")
        .lowercased()
}

extension SdkFileKind {
    var isDirectory: Bool {
        if case .directory = self { return true }
        return false
    }
}
