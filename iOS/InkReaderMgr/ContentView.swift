import SwiftUI
import UniformTypeIdentifiers

private enum MainTab: Hashable {
    case send
    case files
    case features
}

struct ContentView: View {
    @StateObject private var manager = DeviceManager()
    @State private var selectedTab: MainTab = .send
    @State private var showingSendDevices = false
    @State private var showingFilesDevices = false
    @State private var showingFeaturesDevices = false
    @State private var incomingFile: ImportedFile?
    @State private var importError: String?

    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationStack {
                SendView(manager: manager, incomingFile: $incomingFile, showDevices: { showingSendDevices = true })
                    .navigationTitle("推书")
                    .toolbarTitleDisplayMode(.large)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            DeviceStatusButton(manager: manager) { showingSendDevices = true }
                        }
                    }
                    .navigationDestination(isPresented: $showingSendDevices) {
                        DeviceListView(manager: manager)
                    }
            }
            .tabItem { Label("推书", systemImage: "paperplane") }
            .tag(MainTab.send)

            NavigationStack {
                FileManagementView(manager: manager, showDevices: { showingFilesDevices = true })
                    .navigationDestination(isPresented: $showingFilesDevices) {
                        DeviceListView(manager: manager)
                    }
            }
            .tabItem { Label("文件", systemImage: "folder") }
            .tag(MainTab.files)

            NavigationStack {
                FeatureListView(manager: manager)
                    .navigationTitle("功能")
                    .toolbarTitleDisplayMode(.large)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            DeviceStatusButton(manager: manager) { showingFeaturesDevices = true }
                        }
                    }
                    .navigationDestination(isPresented: $showingFeaturesDevices) {
                        DeviceListView(manager: manager)
                    }
            }
            .tabItem { Label("功能", systemImage: "ellipsis") }
            .tag(MainTab.features)
        }
        .onOpenURL(perform: handleIncomingURL)
        .alert("无法打开文件", isPresented: Binding(
            get: { importError != nil },
            set: { if !$0 { importError = nil } }
        )) {
            Button("关闭", role: .cancel) { importError = nil }
        } message: {
            Text(importError ?? "")
        }
    }

    private func handleIncomingURL(_ url: URL) {
        guard url.scheme?.lowercased() == "inkreadermgr" else {
            receiveFileURL(url)
            return
        }
        guard url.host?.lowercased() == "devices" else { return }
        selectedTab = .send
        showingSendDevices = true
    }

    private func receiveFileURL(_ url: URL) {
        guard url.isFileURL else { return }
        Task {
            do {
                let imported = try await Task.detached(priority: .userInitiated) {
                    try stageImportedFile(from: url)
                }.value
                if let incomingFile { removeStagedFiles([incomingFile]) }
                incomingFile = imported
                selectedTab = .send
            } catch {
                importError = errorMessage(error)
            }
        }
    }
}

struct DeviceStatusButton: View {
    @ObservedObject var manager: DeviceManager
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "link")
                .foregroundStyle(manager.isConnected ? Color.accentColor : Color.secondary)
        }
        .accessibilityLabel("设备连接")
        .accessibilityValue(manager.isConnected ? "已连接" : "未连接")
        .accessibilityHint("打开设备列表")
    }
}

struct FeatureListView: View {
    @ObservedObject var manager: DeviceManager

    var body: some View {
        List {
            if manager.supports("wifi.list") {
                NavigationLink {
                    WifiManagementView(manager: manager)
                        .toolbar(.visible, for: .navigationBar)
                } label: {
                    FeatureRow(title: "Wi-Fi 管理", icon: "wifi")
                }
            }
            if manager.supports("settings.list") {
                NavigationLink {
                    DeviceSettingsView(manager: manager)
                        .toolbar(.visible, for: .navigationBar)
                } label: {
                    FeatureRow(title: "设备设置", icon: "gearshape")
                }
            }
            if manager.supports("fonts.list") {
                NavigationLink {
                    FontManagementView(manager: manager)
                        .toolbar(.visible, for: .navigationBar)
                } label: {
                    FeatureRow(title: "字体管理", icon: "textformat")
                }
            }
            if manager.supports("opds.list") {
                NavigationLink {
                    OpdsManagementView(manager: manager)
                        .toolbar(.visible, for: .navigationBar)
                } label: {
                    FeatureRow(title: "OPDS 管理", icon: "dot.radiowaves.left.and.right")
                }
            }
            if manager.supports("device.info") {
                NavigationLink {
                    DeviceInformationView(manager: manager)
                        .toolbar(.visible, for: .navigationBar)
                } label: {
                    FeatureRow(title: "设备信息", icon: "info.circle")
                }
            }
            if manager.supportsAny(["wallpapers.upload", "wallpapers.manage"]) {
                NavigationLink {
                    WallpaperManagementView(manager: manager)
                        .toolbar(.visible, for: .navigationBar)
                } label: {
                    FeatureRow(title: "壁纸管理", icon: "photo")
                }
            }
            NavigationLink {
                SoftwareSettingsView()
                    .toolbar(.visible, for: .navigationBar)
            } label: {
                FeatureRow(title: "软件设置", icon: "slider.horizontal.3")
            }
        }
        .systemInsetGroupedList()
        .overlay {
            if !manager.isConnected && manager.supportedDevices.isEmpty {
                ContentUnavailableView("没有可用设备", systemImage: "iphone")
            }
        }
    }
}

private struct FeatureRow: View {
    var title: String
    var icon: String

    var body: some View {
        Label(title, systemImage: icon)
    }
}

struct SoftwareSettingsView: View {
    var body: some View {
        List {
            Section {
                LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                LabeledContent("项目", value: "Pico Manager")
            }
        }
        .systemInsetGroupedList()
        .navigationTitle("软件设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}
