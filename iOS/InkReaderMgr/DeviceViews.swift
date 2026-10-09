import AVFoundation
import SwiftUI

struct DeviceListView: View {
    @ObservedObject var manager: DeviceManager
    @State private var showingEditor = false
    @State private var editingDevice: SavedDevice?
    @State private var connectingID: String?
    @State private var error: String?

    var body: some View {
        NativeSwipeTable(items: manager.savedDevices, content: deviceRow, onSelect: connectOrDisconnect) { device in
            guard connectingID == nil, !manager.isMutating else { return [] }
            return [
                NativeSwipeTableAction(title: "移除", symbol: "trash", style: .destructive, backgroundColor: .systemRed) { presenter, completion in
                    completion(false)
                    presentDestructiveConfirmation(from: presenter, title: "移除设备？", message: manager.address(for: device), actionTitle: "移除") {
                        manager.removeDevice(device)
                    }
                },
                NativeSwipeTableAction(title: "编辑", symbol: "pencil", style: .normal, backgroundColor: .systemBlue) { _, completion in
                    completion(false)
                    editingDevice = device
                    showingEditor = true
                }
            ]
        }
        .systemGroupedPageBackground()
        .overlay {
            if manager.savedDevices.isEmpty { ContentUnavailableView("尚未保存设备", systemImage: "iphone") }
        }
        .overlay {
            if manager.savedDevices.isEmpty {
                ContentUnavailableView("尚未保存设备", systemImage: "iphone")
            }
        }
        .navigationTitle("设备")
        .toolbarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { editingDevice = nil; showingEditor = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("添加设备")
            }
        }
        .sheet(isPresented: $showingEditor, onDismiss: { editingDevice = nil }) {
            NavigationStack {
                DeviceEditorView(manager: manager, existing: editingDevice)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .alert("设备操作失败", isPresented: Binding(
            get: { error != nil },
            set: { if !$0 { error = nil } }
        )) {
            Button("关闭", role: .cancel) { error = nil }
        } message: { Text(error ?? "") }
    }

    private func connectOrDisconnect(_ device: SavedDevice) {
        if manager.activeDevice?.id == device.id {
            manager.disconnect()
            return
        }
        connectingID = device.id
        Task {
            defer { connectingID = nil }
            do {
                try await manager.connect(device)
            } catch {
                self.error = errorMessage(error)
            }
        }
    }

    private func deviceRow(_ device: SavedDevice) -> some View {
        let isConnected = manager.activeDevice?.id == device.id
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(manager.address(for: device).isEmpty ? manager.deviceName(for: device.deviceType) : manager.address(for: device)).lineLimit(1)
                Text(manager.deviceName(for: device.deviceType)).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            if isConnected { Image(systemName: "checkmark").foregroundStyle(.tint).accessibilityHidden(true) }
            else if connectingID == device.id { ProgressView().accessibilityLabel("正在连接") }
        }
        .contentShape(Rectangle())
    }
}

private struct DeviceEditorView: View {
    @ObservedObject var manager: DeviceManager
    var existing: SavedDevice?
    @Environment(\.dismiss) private var dismiss
    @State private var deviceType: String
    @State private var values: [String: String]
    @State private var showingScanner = false
    @State private var error: String?

    init(manager: DeviceManager, existing: SavedDevice?) {
        self.manager = manager
        self.existing = existing
        _deviceType = State(initialValue: existing?.deviceType ?? manager.supportedDevices.first?.deviceType ?? "")
        _values = State(initialValue: existing?.values ?? [:])
    }

    private var definition: SdkSupportedDevice? {
        manager.supportedDevices.first { $0.deviceType == deviceType }
    }

    var body: some View {
        Form {
            Section {
                Picker("设备类型", selection: $deviceType) {
                    ForEach(manager.supportedDevices, id: \.deviceType) { device in
                        Text(device.displayName).tag(device.deviceType)
                    }
                }
                .disabled(existing != nil)
            }

            if let definition {
                Section {
                    ForEach(definition.connectionFields, id: \.key) { field in
                        connectionField(field)
                    }
                }
            }
        }
        .systemGroupedForm()
        .navigationTitle(existing == nil ? "添加设备" : "编辑设备")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { save() }
                    .disabled(definition == nil)
            }
        }
        .sheet(isPresented: $showingScanner) {
            QRScannerView { scannedValue in
                if let addressField = definition?.connectionFields.first(where: { if case .address = $0.kind { return true }; return false }) {
                    values[addressField.key] = scannedValue
                }
            }
        }
        .alert("无法保存设备", isPresented: Binding(
            get: { error != nil },
            set: { if !$0 { error = nil } }
        )) {
            Button("关闭", role: .cancel) { error = nil }
        } message: { Text(error ?? "") }
    }

    @ViewBuilder
    private func connectionField(_ field: SdkConnectionField) -> some View {
        switch field.kind {
        case .text:
            TextField(field.label, text: valueBinding(field), prompt: Text(field.required ? "必填" : "可选"))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        case .address:
            HStack {
                TextField(field.label, text: valueBinding(field), prompt: Text("IP、主机名或完整 URL"))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Button { showingScanner = true } label: { Image(systemName: "qrcode.viewfinder") }
                    .accessibilityLabel("扫描设备二维码")
            }
        case .choice(let options):
            Picker(field.label, selection: valueBinding(field, defaultValue: "0")) {
                ForEach(options.indices, id: \.self) { index in
                    Text(options[index]).tag(String(index))
                }
            }
        case .toggle:
            Toggle(field.label, isOn: Binding(
                get: { values[field.key] == "true" },
                set: { values[field.key] = $0 ? "true" : "false" }
            ))
        }
    }

    private func valueBinding(_ field: SdkConnectionField, defaultValue: String = "") -> Binding<String> {
        Binding(get: { values[field.key, default: defaultValue] }, set: { values[field.key] = $0 })
    }

    private func save() {
        do {
            _ = try manager.saveDevice(id: existing?.id, deviceType: deviceType, values: values)
            dismiss()
        } catch {
            self.error = errorMessage(error)
        }
    }
}

struct QRScannerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?
    var onScan: (String) -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            QRScannerCamera(onCode: { code in
                onScan(code)
                dismiss()
            }, onError: { error = $0 })
                .ignoresSafeArea()
            Button("取消") { dismiss() }
                .padding()
                .foregroundStyle(.white)
        }
        .alert("无法使用相机", isPresented: Binding(
            get: { error != nil },
            set: { if !$0 { error = nil } }
        )) {
            Button("关闭", role: .cancel) { dismiss() }
        } message: { Text(error ?? "") }
    }
}

private struct QRScannerCamera: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    var onError: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode, onError: onError) }

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        context.coordinator.configure(on: controller.previewView)
        return controller
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {}

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        private let session = AVCaptureSession()
        private var configured = false
        private var didReportError = false
        private let onCode: (String) -> Void
        private let onError: (String) -> Void

        init(onCode: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
            self.onCode = onCode
            self.onError = onError
        }

        func configure(on preview: QRPreviewView) {
            preview.previewLayer.session = session
            preview.previewLayer.videoGravity = .resizeAspectFill
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            if status == .authorized {
                configureSession()
            } else if status == .notDetermined {
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    DispatchQueue.main.async {
                        guard let self else { return }
                        if granted { self.configureSession() }
                        else { self.report("需要相机权限才能扫码") }
                    }
                }
            } else {
                report("需要在系统设置中允许 Pico Manager 使用相机")
            }
        }

        private func configureSession() {
            guard !configured else { return }
            configured = true
            do {
                guard let camera = AVCaptureDevice.default(for: .video) else {
                    report("此设备没有可用相机")
                    return
                }
                let input = try AVCaptureDeviceInput(device: camera)
                let output = AVCaptureMetadataOutput()
                guard session.canAddInput(input), session.canAddOutput(output) else {
                    report("无法启动扫码相机")
                    return
                }
                session.beginConfiguration()
                session.addInput(input)
                session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
                session.commitConfiguration()
                DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
            } catch {
                report(error.localizedDescription)
            }
        }

        private func report(_ message: String) {
            guard !didReportError else { return }
            didReportError = true
            onError(message)
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first?.stringValue else { return }
            session.stopRunning()
            onCode(code)
        }
    }
}

private final class ScannerController: UIViewController {
    let previewView = QRPreviewView()
    override func loadView() { view = previewView }
}

private final class QRPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
}
