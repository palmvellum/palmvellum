import SwiftUI

/// Shared chrome: a dark Palm title bar (home button, title, optional inline
/// action, online dot) over a silver-desk content area.
struct PalmScaffold<Content: View>: View {
    let title: String
    var showHome: Bool
    var titleAction: AnyView?
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var net: NetStatus
    private let content: Content

    init(
        title: String,
        showHome: Bool = true,
        titleAction: AnyView? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.showHome = showHome
        self.titleAction = titleAction
        self.content = content()
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                if showHome {
                    Button(action: { router.home() }) {
                        Text("\u{2302}") // ⌂ home glyph (navigation chrome)
                            .font(.system(size: 15))
                            .foregroundColor(Palm.onDark)
                    }
                    .buttonStyle(.plain)
                    .help("Home")
                }
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Palm.onDark)
                Spacer()
                if let titleAction { titleAction }
                Circle()
                    .fill(net.online ? Palm.online : Palm.offline)
                    .frame(width: 9, height: 9)
                    .help(net.online ? "online" : "offline")
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(Palm.titlebar)

            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Palm.desk)
        }
    }
}
