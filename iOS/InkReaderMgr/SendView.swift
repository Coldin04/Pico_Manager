import SwiftUI
import UniformTypeIdentifiers

private enum SendCategory: String, CaseIterable, Identifiable {
    case book = "图书"
    case font = "字体"

    var id: String { rawValue }
}

struct SendView: View {
    @ObservedObject var manager: DeviceManager
    @Binding var incomingFile: ImportedFile?
    var showDevices: () -> Void

    @State private var files: [ImportedFile] = []
    @State private var category: SendCategory = .book
    @State private var familyName = ""
    @State private var selectedDirectory: String?
    @State private var showingImporter = false
    @State private var showingDirectoryPicker = false
    @State private var showingFormatReview = false
    @State private var pendingBookOverwrite: ImportedFile?
    @State private var pendingFontOverwrite: ImportedFile?
    @State private var error: String?
    @State private var completion: String?

    private var profile: SdkDeviceProfile? { manager.profile }
    private var supportedFontExtensions: Set<String> {
        Set(profile?.fileFormats.fontUploadExtensions.map(normalizeExtension) ?? [])
    }
    private var canUploadFonts: Bool {
        manager.supports("fonts.upload") && !supportedFontExtensions.isEmpty
    }
    private var fontFamilyRequired: Bool { manager.supports("fonts.upload.family") }
    private var canChooseDirectory: Bool {
        guard let profile else { return false }
        return profile.capabilities.contains("upload.target-directory") &&
            profile.capabilities.contains("files.list") &&
            profile.constraints.canChooseUploadDirectory &&
            profile.constraints.canListDirectories
    }
    private var acceptableBookFiles: [ImportedFile] {
        guard let formats = profile?.fileFormats else { return [] }
        if formats.acceptsAnyUploadFormat { return files }
        let supported = Set(formats.uploadExtensions.map(normalizeExtension))
        return files.filter { supported.contains($0.fileExtension) }
    }
    private var unsupportedBookFiles: [ImportedFile] {
        files.filter { !acceptableBookFiles.contains($0) }
    }
    private var unreadableBookFiles: [ImportedFile] {
        let readable = Set(profile?.fileFormats.readableExtensions.map(normalizeExtension) ?? [])
        return acceptableBookFiles.filter { !readable.contains($0.fileExtension) }
    }
    private var fontCandidate: ImportedFile? {
        guard files.count == 1, let file = files.first, supportedFontExtensions.contains(file.fileExtension) else { return nil }
        return file
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                selectionList

                if !files.isEmpty {
                    Picker("类型", selection: $category) {
                        ForEach(availableCategories) { item in Text(item.rawValue).tag(item) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityLabel("类型")
                }

                if hasAdditionalFields {
                    additionalFieldsList
                }

                if let formatHint {
                    Text(formatHint)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 16)
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
                            Text("\(manager.activeOperation ?? "发送中") · \(ByteCountFormatter.string(fromByteCount: Int64(progress.sent), countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: Int64(progress.total), countStyle: .file))")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else {
                            ProgressView(manager.activeOperation ?? "处理中…")
                        }
                    }
                    .frame(maxWidth: .infinity)
                } else {
                    Button(action: send) {
                        Text(buttonTitle)
                            .frame(maxWidth: .infinity)
                    }
                        .font(.body)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .disabled(!canSend)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 12)
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea(edges: .bottom))
        }
        .systemGroupedPageBackground()
        .fileImporter(
            isPresented: $showingImporter,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true,
            onCompletion: receivePickedFiles
        )
        .sheet(isPresented: $showingDirectoryPicker) {
            NavigationStack {
                TargetDirectoryPicker(manager: manager, selectedPath: $selectedDirectory)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .onChange(of: incomingFile) { _, incoming in
            guard let incoming else { return }
            replaceSelection(with: [incoming])
            incomingFile = nil
            chooseDefaultCategory(for: [incoming])
        }
        .confirmationDialog("文件格式提示", isPresented: $showingFormatReview, titleVisibility: .visible) {
            Button("继续发送") { startBookUpload() }
            Button("取消", role: .cancel) {}
        } message: {
            Text(reviewMessage)
        }
        .alert("替换现有文件？", isPresented: Binding(
            get: { pendingBookOverwrite != nil },
            set: { if !$0 { pendingBookOverwrite = nil } }
        )) {
            Button("替换", role: .destructive) { startBookUpload(overwriting: pendingBookOverwrite) }
            Button("取消", role: .cancel) { pendingBookOverwrite = nil; finishSelection() }
        } message: { Text(pendingBookOverwrite?.name ?? "") }
        .alert("替换现有字体？", isPresented: Binding(
            get: { pendingFontOverwrite != nil },
            set: { if !$0 { pendingFontOverwrite = nil } }
        )) {
            Button("替换", role: .destructive) { startFontUpload(overwrite: true) }
            Button("取消", role: .cancel) { pendingFontOverwrite = nil; finishSelection() }
        } message: { Text(pendingFontOverwrite?.name ?? "") }
        .alert("发送完成", isPresented: Binding(
            get: { completion != nil },
            set: { if !$0 { completion = nil } }
        )) {
            Button("完成", role: .cancel) { completion = nil }
        } message: { Text(completion ?? "") }
        .alert("操作失败", isPresented: Binding(
            get: { error != nil },
            set: { if !$0 { error = nil } }
        )) {
            Button("关闭", role: .cancel) { error = nil }
        } message: { Text(error ?? "") }
    }

    private var availableCategories: [SendCategory] {
        var result: [SendCategory] = [.book]
        if canUploadFonts { result.append(.font) }
        return result
    }

    private var hasAdditionalFields: Bool {
        (category == .font && canUploadFonts) || (category == .book && canChooseDirectory)
    }

    private var formatHint: String? {
        if category == .book && !unsupportedBookFiles.isEmpty {
            return "设备不支持上传所选文件格式"
        }
        if category == .book && !unreadableBookFiles.isEmpty {
            return unreadableBookFiles.count == 1 ? "此文件可能无法由设备直接阅读" : "部分文件可能无法由设备直接阅读"
        }
        if category == .font && !files.isEmpty && fontCandidate == nil {
            return "请选择设备支持的字体文件"
        }
        return nil
    }

    private var selectionList: some View {
        VStack(spacing: 0) {
            Button(action: showDevices) {
                LabeledContent("设备", value: manager.activeDevice.map(manager.address(for:)) ?? "连接设备")
                    .padding(.horizontal, 16)
                    .padding(.vertical, 16)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(.primary)

            Divider().padding(.leading, 16)

            Button { showingImporter = true } label: {
                VStack(alignment: .leading, spacing: 8) {
                    Text("选择发送的文件")
                        .foregroundStyle(.tint)

                    if files.isEmpty {
                        Text("选择文件")
                            .foregroundStyle(.secondary)
                    } else {
                        VStack(alignment: .leading, spacing: 4) {
                            ForEach(files) { file in
                                Text(file.name)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                                    .truncationMode(.middle)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(manager.isMutating)
        }
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 28, style: .continuous)
        )
    }

    private var additionalFieldsList: some View {
        VStack(spacing: 0) {
            if category == .font && canUploadFonts {
                TextField(fontFamilyRequired ? "字体族" : "字体族（可选）", text: $familyName)
                    .textInputAutocapitalization(.never)
                    .padding(16)
            }

            if category == .book && canChooseDirectory {
                Button { showingDirectoryPicker = true } label: {
                    LabeledContent("目标目录", value: selectedDirectory ?? "默认位置")
                        .padding(.horizontal, 16)
                        .padding(.vertical, 16)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.primary)
            }
        }
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 28, style: .continuous)
        )
    }

    private var buttonTitle: String {
        switch category {
        case .book: "发送"
        case .font: "上传字体"
        }
    }

    private var canSend: Bool {
        guard !files.isEmpty else { return false }
        return switch category {
        case .book:
            manager.supports("files.upload") && !acceptableBookFiles.isEmpty
        case .font:
            fontCandidate != nil && (!fontFamilyRequired || !familyName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private var reviewMessage: String {
        var messages: [String] = []
        if !unsupportedBookFiles.isEmpty { messages.append("將跳过 \(unsupportedBookFiles.count) 个设备不支持的文件") }
        if !unreadableBookFiles.isEmpty { messages.append("\(unreadableBookFiles.count) 个文件可能无法由设备直接阅读") }
        return messages.joined(separator: "；")
    }

    private func send() {
        switch category {
        case .book:
            guard manager.supports("files.upload") else {
                error = manager.isConnected ? "设备不支持上传" : "请先连接设备"
                return
            }
            if !unsupportedBookFiles.isEmpty || !unreadableBookFiles.isEmpty {
                showingFormatReview = true
            } else {
                startBookUpload()
            }
        case .font:
            startFontUpload(overwrite: false)
        }
    }

    private func startBookUpload(overwriting fileToOverwrite: ImportedFile? = nil) {
        let eligible = acceptableBookFiles
        guard !eligible.isEmpty else { return }
        Task {
            for file in eligible {
                do {
                    _ = try await manager.upload(file, to: uploadLocation, overwrite: file.id == fileToOverwrite?.id)
                    removeStagedFiles([file])
                    files.removeAll { $0.id == file.id }
                } catch let sdkError as SdkOperationError {
                    if case .Conflict = sdkError,
                       !manager.supports("upload.backup-replace"),
                       manager.supports("upload.explicit-overwrite"),
                       file.id != fileToOverwrite?.id {
                        pendingBookOverwrite = file
                        return
                    }
                    self.error = errorMessage(sdkError)
                    finishSelection()
                    return
                } catch {
                    self.error = errorMessage(error)
                    finishSelection()
                    return
                }
            }
            completion = "图书发送完成"
            finishSelection()
        }
    }

    private var uploadLocation: SdkFileLocation {
        guard canChooseDirectory, let selectedDirectory else { return .root }
        return .directory(path: selectedDirectory)
    }

    private func startFontUpload(overwrite: Bool) {
        guard let file = fontCandidate else { return }
        Task {
            do {
                try await manager.uploadFont(file, family: familyName.trimmingCharacters(in: .whitespacesAndNewlines), overwrite: overwrite)
                completion = "字体上传完成"
                finishSelection()
            } catch let sdkError as SdkOperationError {
                if case .Conflict = sdkError,
                   manager.supports("upload.explicit-overwrite"),
                   !overwrite {
                    pendingFontOverwrite = file
                } else {
                    error = errorMessage(sdkError)
                    finishSelection()
                }
            } catch {
                self.error = errorMessage(error)
                finishSelection()
            }
        }
    }

    private func receivePickedFiles(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            Task {
                do {
                    let staged = try await Task.detached(priority: .userInitiated) {
                        try urls.map(stageImportedFile(from:))
                    }.value
                    replaceSelection(with: staged)
                    chooseDefaultCategory(for: staged)
                } catch {
                    self.error = errorMessage(error)
                }
            }
        case .failure(let error):
            self.error = errorMessage(error)
        }
    }

    private func chooseDefaultCategory(for files: [ImportedFile]) {
        guard files.count == 1, let file = files.first else { category = .book; return }
        if canUploadFonts && supportedFontExtensions.contains(file.fileExtension) { category = .font }
        else { category = .book }
    }

    private func replaceSelection(with next: [ImportedFile]) {
        removeStagedFiles(files)
        files = next
        selectedDirectory = nil
    }

    private func finishSelection() {
        removeStagedFiles(files)
        files = []
        manager.uploadProgress = nil
    }
}

private func normalizeExtension(_ extensionName: String) -> String {
    extensionName.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ".", with: "").lowercased()
}

struct TargetDirectoryPicker: View {
    @ObservedObject var manager: DeviceManager
    @Binding var selectedPath: String?
    var onChoose: ((String?) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var path = ""
    @State private var entries: [SdkFileEntry] = []
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        List {
            Button("默认位置") {
                choose(nil)
                dismiss()
            }
            ForEach(entries.filter { if case .directory = $0.kind { return true }; return false }, id: \.path) { entry in
                Button {
                    path = entry.path
                    refresh()
                } label: {
                    Label(entry.name, systemImage: "folder")
                }
            }
        }
        .systemInsetGroupedList()
        .overlay {
            if loading { ProgressView() }
            else if entries.isEmpty { ContentUnavailableView("此目录为空", systemImage: "folder") }
        }
        .navigationTitle(path.isEmpty ? "目标目录" : URL(filePath: "/\(path)").lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("选择") { choose(path.isEmpty ? nil : path); dismiss() }
            }
        }
        .task { refresh() }
        .alert("无法读取目录", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("关闭", role: .cancel) { error = nil }
        } message: { Text(error ?? "") }
    }

    private func refresh() {
        loading = true
        Task {
            defer { loading = false }
            do { entries = try await manager.listFiles(path.isEmpty ? .root : .directory(path: path)) }
            catch { self.error = errorMessage(error) }
        }
    }

    private func choose(_ nextPath: String?) {
        selectedPath = nextPath
        onChoose?(nextPath)
    }
}
