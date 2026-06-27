import SwiftUI
import PalmKit

struct AppTile: Identifiable {
    let id = UUID()
    let glyph: String
    let label: String
    let route: Route
}

/// The classic Palm app launcher. Tile glyphs are the one sanctioned glyph
/// set (structural navigation), never emoji.
struct LauncherScreen: View {
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var i18n: I18n
    @StateObject private var conflicts: ConflictsVM

    init(repo: Repository) {
        _conflicts = StateObject(wrappedValue: ConflictsVM(repo: repo))
    }

    // `label` is an i18n key, resolved at render.
    private let tiles: [AppTile] = [
        .init(glyph: "\u{25EB}", label: "datebook", route: .datebook), // ◫
        .init(glyph: "\u{2611}", label: "todo", route: .todo),         // ☑
        .init(glyph: "\u{2726}", label: "address", route: .address),   // ✦
        .init(glyph: "\u{25A4}", label: "memo", route: .memo),         // ▤
        .init(glyph: "\u{270E}", label: "notepad", route: .notepad),   // ✎
        .init(glyph: "\u{00A4}", label: "expense", route: .expense),   // ¤
        .init(glyph: "\u{2709}", label: "mail", route: .mail),         // ✉
        .init(glyph: "\u{21C4}", label: "hotsync", route: .hotsync),   // ⇄
        .init(glyph: "\u{2699}", label: "settings", route: .settings), // ⚙
    ]

    var body: some View {
        PalmScaffold(title: i18n.t("app"), showHome: false) {
            ScrollView {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 130), spacing: 14)],
                    spacing: 14
                ) {
                    ForEach(tiles) { tile in
                        Button(action: { router.go(tile.route) }) {
                            VStack(spacing: 8) {
                                Text(tile.glyph)
                                    .font(.system(size: 30))
                                    .foregroundColor(Palm.accentInk)
                                Text(i18n.t(tile.label))
                                    .font(.system(size: 12))
                                    .foregroundColor(Palm.ink)
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 92)
                            .background(Palm.vellum)
                            .overlay(
                                RoundedRectangle(cornerRadius: 4)
                                    .stroke(Palm.line, lineWidth: 1)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(16)

                if !conflicts.conflicts.isEmpty {
                    Button(action: { router.go(.conflicts) }) {
                        Text("conflicts (\(conflicts.conflicts.count)) — tap to resolve")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(Palm.offline)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.offline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .padding(.bottom, 6)
                }

                Text("PalmVellum Organizers 0.1.0")
                    .font(.system(size: 11))
                    .foregroundColor(Palm.accentInk)
                    .padding(.bottom, 10)
            }
        }
    }
}
