import SwiftUI
import UIKit

struct NativeFileList: UIViewControllerRepresentable {
    let entries: [SdkFileEntry]
    let canRename: Bool
    let canDelete: Bool
    let canDownload: Bool
    let canMove: Bool
    let isMutating: Bool
    let onRefresh: () async -> Void
    let onOpenDirectory: (String) -> Void
    let onRename: (SdkFileEntry) -> Void
    let onDelete: (SdkFileEntry) -> Void
    let onDownload: (SdkFileEntry) -> Void
    let onMove: (SdkFileEntry) -> Void

    func makeUIViewController(context: Context) -> UITableViewController {
        let controller = UITableViewController(style: .insetGrouped)
        controller.view.backgroundColor = .systemGroupedBackground
        controller.tableView.register(UITableViewCell.self, forCellReuseIdentifier: "file")
        controller.tableView.dataSource = context.coordinator
        controller.tableView.delegate = context.coordinator
        controller.tableView.backgroundColor = .systemGroupedBackground
        controller.refreshControl = UIRefreshControl()
        controller.refreshControl?.addTarget(context.coordinator, action: #selector(Coordinator.refresh), for: .valueChanged)
        context.coordinator.controller = controller
        return controller
    }

    func updateUIViewController(_ controller: UITableViewController, context: Context) {
        context.coordinator.parent = self
        context.coordinator.controller = controller
        controller.tableView.reloadData()
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, UITableViewDataSource, UITableViewDelegate {
        var parent: NativeFileList
        weak var controller: UITableViewController?

        init(parent: NativeFileList) { self.parent = parent }

        func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
            parent.entries.count
        }

        func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
            let entry = parent.entries[indexPath.row]
            let cell = tableView.dequeueReusableCell(withIdentifier: "file", for: indexPath)
            cell.accessoryType = entry.kind.isDirectory ? .disclosureIndicator : .none
            cell.contentConfiguration = UIHostingConfiguration {
                if entry.kind.isDirectory {
                    Label(entry.name, systemImage: "folder")
                } else {
                    HStack {
                        Label(entry.name, systemImage: "doc")
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        Text(ByteCountFormatter.string(fromByteCount: Int64(entry.size), countStyle: .file))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            return cell
        }

        func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
            let entry = parent.entries[indexPath.row]
            tableView.deselectRow(at: indexPath, animated: true)
            if entry.kind.isDirectory { parent.onOpenDirectory(entry.path) }
        }

        func tableView(
            _ tableView: UITableView,
            trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
        ) -> UISwipeActionsConfiguration? {
            let entry = parent.entries[indexPath.row]
            var actions: [UIContextualAction] = []

            if parent.canDelete && !parent.isMutating {
                let delete = UIContextualAction(style: .destructive, title: nil) { [weak self] _, _, completion in
                    guard let self, let controller = self.controller else { completion(false); return }
                    completion(false)
                    self.presentDeleteConfirmation(for: entry, from: controller)
                }
                delete.image = UIImage(systemName: "trash")
                delete.backgroundColor = .systemRed
                actions.append(delete)
            }

            if parent.canRename && !parent.isMutating {
                let rename = UIContextualAction(style: .normal, title: nil) { [weak self] _, _, completion in
                    self?.parent.onRename(entry)
                    completion(false)
                }
                rename.image = UIImage(systemName: "pencil")
                rename.backgroundColor = .systemBlue
                actions.append(rename)
            }

            guard !actions.isEmpty else { return nil }
            let configuration = UISwipeActionsConfiguration(actions: actions)
            configuration.performsFirstActionWithFullSwipe = false
            return configuration
        }

        func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
            let entry = parent.entries[indexPath.row]
            var actions: [UIAction] = []
            if !parent.isMutating && parent.canDownload && !entry.kind.isDirectory {
                actions.append(UIAction(title: "下载", image: UIImage(systemName: "arrow.down.doc")) { [weak self] _ in self?.parent.onDownload(entry) })
            }
            if !parent.isMutating && parent.canMove {
                actions.append(UIAction(title: "移动", image: UIImage(systemName: "folder")) { [weak self] _ in self?.parent.onMove(entry) })
            }
            guard !actions.isEmpty else { return nil }
            return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in UIMenu(children: actions) }
        }

        @objc func refresh() {
            Task { @MainActor [weak self] in
                guard let self else { return }
                await parent.onRefresh()
                controller?.refreshControl?.endRefreshing()
            }
        }

        private func presentDeleteConfirmation(for entry: SdkFileEntry, from controller: UIViewController) {
            let alert = UIAlertController(title: "删除文件？", message: entry.name, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "删除", style: .destructive) { [weak self] _ in
                self?.parent.onDelete(entry)
            })
            alert.addAction(UIAlertAction(title: "取消", style: .cancel))
            controller.present(alert, animated: true)
        }
    }
}
