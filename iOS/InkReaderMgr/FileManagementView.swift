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
    @State private var pendingMoveFiles: [SdkFileEntry] = []
    @State private var editValue = ""
    @State private var showingCreateDirectory = false
    @State private var showingMovePicker = false
    @State private var moveDestination: String?
    @State private var downloadedFiles: [URL]?
    @State private var isEditingEntries = false
    @State private var selectedEntryPaths: Set<String> = []
    @State private var showingBatchDeleteConfirmation = false
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

    private var selectedEntries: [SdkFileEntry] {
        visibleEntries.filter { selectedEntryPaths.contains($0.path) }
    }

    private var canSelectEntries: Bool {
        manager.supports("files.delete") || manager.supports("files.move") ||
            manager.supports("files.rename") ||
            (manager.supports("files.download") && visibleEntries.contains { !$0.kind.isDirectory })
    }

    private var selectedEntriesCanDownload: Bool {
        !selectedEntries.isEmpty && selectedEntries.allSatisfy { !$0.kind.isDirectory }
    }

    private var pageTitle: String {
        if isEditingEntries { return "已选 \(selectedEntryPaths.count) 项" }
        if currentPath.isEmpty { return "文件管理" }
        return currentPath.split(separator: "/").last.map(String.init) ?? "文件"
    }

    @ViewBuilder
    private var selectionActionsMenu: some View {
        Menu {
            if manager.supports("files.download"), selectedEntriesCanDownload {
                Button("下载", systemImage: "arrow.down.doc") {
                    downloadSelectedEntries(selectedEntries)
                }
            }
            if manager.supports("files.move") {
                Button("移动", systemImage: "folder") {
                    beginMove(with: selectedEntries)
                }
            }
            if manager.supports("files.rename"), selectedEntries.count == 1, let entry = selectedEntries.first {
                Button("重命名", systemImage: "pencil") {
                    pendingRename = entry
                    editValue = entry.name
                    endEntrySelection()
                }
            }
            if manager.supports("files.delete") {
                Button(role: .destructive) {
                    showingBatchDeleteConfirmation = true
                } label: {
                    Label("删除", systemImage: "trash")
                }
            }
        } label: {
            Label("操作", systemImage: "ellipsis.circle")
        }
        .disabled(manager.isMutating)
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
                    isEditing: $isEditingEntries,
                    selectedPaths: $selectedEntryPaths,
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
                    onMove: { entry in beginMove(with: [entry]) }
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
        .navigationTitle(pageTitle)
        .toolbarTitleDisplayMode(isEditingEntries || !currentPath.isEmpty ? .inline : .large)
        .toolbar {
            ToolbarItem(placement: currentPath.isEmpty ? .topBarLeading : .topBarTrailing) {
                if canSelectEntries && !visibleEntries.isEmpty {
                    Button(isEditingEntries ? "完成" : "选择") {
                        if isEditingEntries {
                            endEntrySelection()
                        } else {
                            isEditingEntries = true
                        }
                    }
                    .disabled(manager.isMutating)
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if currentPath.isEmpty && !isEditingEntries {
                    DeviceStatusButton(manager: manager, action: showDevices)
                }
                if isEditingEntries {
                    if !selectedEntries.isEmpty {
                        selectionActionsMenu
                    }
                } else if manager.isConnected && manager.supports("files.list") {
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
        .onChange(of: showingMovePicker) { _, isPresented in
            if !isPresented { pendingMoveFiles = [] }
        }
        .sheet(isPresented: Binding(
            get: { downloadedFiles != nil },
            set: { if !$0 { cleanupDownloads() } }
        )) {
            if let downloadedFiles {
                NavigationStack {
                    VStack(spacing: 20) {
                        Image(systemName: "checkmark.circle.fill").font(.largeTitle).foregroundStyle(.green)
                        Text("下载完成")
                        if downloadedFiles.count == 1, let file = downloadedFiles.first {
                            ShareLink(item: file) {
                                Label("保存或分享", systemImage: "square.and.arrow.up")
                            }
                            .font(.body)
                            .buttonStyle(.borderedProminent)
                            .controlSize(.regular)
                        } else if !downloadedFiles.isEmpty {
                            ShareLink(items: downloadedFiles) {
                                Label("保存或分享 \(downloadedFiles.count) 个文件", systemImage: "square.and.arrow.up")
                            }
                            .font(.body)
                            .buttonStyle(.borderedProminent)
                            .controlSize(.regular)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .systemGroupedPageBackground()
                    .navigationTitle("下载")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { cleanupDownloads() } } }
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
        .alert("删除所选项目？", isPresented: $showingBatchDeleteConfirmation) {
            Button("删除", role: .destructive) { deleteSelectedEntries() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将删除所选的 \(selectedEntries.count) 个项目。")
        }
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
            let listedEntries = try await manager.listFiles(currentPath.isEmpty ? .root : .directory(path: currentPath))
            entries = listedEntries
            selectedEntryPaths.formIntersection(Set(listedEntries.map(\.path)))
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

    private func endEntrySelection() {
        isEditingEntries = false
        selectedEntryPaths = []
    }

    private func deleteSelectedEntries() {
        let files = selectedEntries
        endEntrySelection()
        guard !files.isEmpty else { return }
        Task {
            do { try await manager.deleteFiles(files) }
            catch { self.error = errorMessage(error) }
            await refreshAsync()
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
        let files = pendingMoveFiles
        guard !files.isEmpty, let destination = moveDestination else { return }
        pendingMoveFiles = []
        Task {
            do {
                if files.count == 1, let file = files.first {
                    try await manager.moveFile(file, to: destination)
                } else {
                    try await manager.moveFiles(files, to: destination)
                }
            } catch {
                self.error = errorMessage(error)
            }
            await refreshAsync()
        }
    }

    private func beginMove(with files: [SdkFileEntry]) {
        guard !files.isEmpty else { return }
        pendingMoveFiles = files
        moveDestination = nil
        endEntrySelection()
        showingMovePicker = true
    }

    private func download(_ entry: SdkFileEntry) {
        downloadSelectedEntries([entry])
    }

    private func downloadSelectedEntries(_ files: [SdkFileEntry]) {
        guard !files.isEmpty else { return }
        endEntrySelection()
        Task {
            do {
                downloadedFiles = files.count == 1
                    ? [try await manager.download(files[0])]
                    : try await manager.downloadFiles(files)
            }
            catch { self.error = errorMessage(error) }
        }
    }

    private func cleanupDownloads() {
        downloadedFiles?.forEach { try? FileManager.default.removeItem(at: $0) }
        downloadedFiles = nil
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
