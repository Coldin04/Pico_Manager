import SwiftUI
import UIKit

struct NativeSwipeTableAction {
    let title: String?
    let symbol: String
    let style: UIContextualAction.Style
    let backgroundColor: UIColor
    let handler: (UIViewController, @escaping (Bool) -> Void) -> Void
}

struct NativeSwipeTable<Item, Content: View>: UIViewControllerRepresentable {
    let items: [Item]
    let content: (Item) -> Content
    let onSelect: (Item) -> Void
    var onRefresh: (() async -> Void)? = nil
    let trailingActions: (Item) -> [NativeSwipeTableAction]

    func makeUIViewController(context: Context) -> UITableViewController {
        let controller = UITableViewController(style: .insetGrouped)
        context.coordinator.controller = controller
        controller.view.backgroundColor = .systemGroupedBackground
        controller.tableView.register(UITableViewCell.self, forCellReuseIdentifier: "row")
        controller.tableView.dataSource = context.coordinator
        controller.tableView.delegate = context.coordinator
        controller.tableView.backgroundColor = .systemGroupedBackground
        if onRefresh != nil {
            controller.refreshControl = UIRefreshControl()
            controller.refreshControl?.addTarget(context.coordinator, action: #selector(Coordinator.refresh), for: .valueChanged)
        }
        return controller
    }

    func updateUIViewController(_ controller: UITableViewController, context: Context) {
        context.coordinator.parent = self
        controller.tableView.reloadData()
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, UITableViewDataSource, UITableViewDelegate {
        var parent: NativeSwipeTable
        weak var controller: UITableViewController?

        init(parent: NativeSwipeTable) { self.parent = parent }

        func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
            parent.items.count
        }

        func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
            let cell = tableView.dequeueReusableCell(withIdentifier: "row", for: indexPath)
            cell.contentConfiguration = UIHostingConfiguration {
                parent.content(parent.items[indexPath.row])
            }
            return cell
        }

        func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
            tableView.deselectRow(at: indexPath, animated: true)
            parent.onSelect(parent.items[indexPath.row])
        }

        @objc func refresh() {
            Task { @MainActor [weak self] in
                guard let self, let onRefresh = parent.onRefresh else { return }
                await onRefresh()
                controller?.refreshControl?.endRefreshing()
            }
        }

        func tableView(
            _ tableView: UITableView,
            trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
        ) -> UISwipeActionsConfiguration? {
            let actions = parent.trailingActions(parent.items[indexPath.row]).map { descriptor in
                let action = UIContextualAction(style: descriptor.style, title: descriptor.title) { _, _, completion in
                    guard let presenter = self.controller else { completion(false); return }
                    descriptor.handler(presenter, completion)
                }
                action.image = UIImage(systemName: descriptor.symbol)
                action.backgroundColor = descriptor.backgroundColor
                return action
            }
            guard !actions.isEmpty else { return nil }
            let configuration = UISwipeActionsConfiguration(actions: actions)
            configuration.performsFirstActionWithFullSwipe = false
            return configuration
        }
    }
}

func presentDestructiveConfirmation(
    from presenter: UIViewController,
    title: String,
    message: String,
    actionTitle: String = "删除",
    onConfirm: @escaping () -> Void
) {
    let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
    alert.addAction(UIAlertAction(title: actionTitle, style: .destructive) { _ in onConfirm() })
    alert.addAction(UIAlertAction(title: "取消", style: .cancel))
    presenter.present(alert, animated: true)
}
