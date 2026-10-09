import SwiftUI

extension View {
    func systemGroupedPageBackground() -> some View {
        background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
    }

    func systemGroupedForm() -> some View {
        scrollContentBackground(.hidden)
            .systemGroupedPageBackground()
    }

    func systemInsetGroupedList() -> some View {
        listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .systemGroupedPageBackground()
    }
}
