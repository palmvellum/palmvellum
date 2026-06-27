import SwiftUI
import PalmKit

struct ConflictsScreen: View {
    let env: AppEnv
    @EnvironmentObject private var i18n: I18n
    @StateObject private var vm: ConflictsVM

    init(env: AppEnv) {
        self.env = env
        _vm = StateObject(wrappedValue: ConflictsVM(repo: env.repo))
    }

    var body: some View {
        PalmScaffold(title: i18n.t("conflicts")) {
            if vm.conflicts.isEmpty {
                EmptyHint(text: "(no conflicts — everything is in sync)")
            } else {
                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(vm.conflicts) { c in
                            ConflictCard(conflict: c) { keepLocal in
                                Task { await env.sync.resolveConflict(c, keepLocal: keepLocal) }
                            }
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

private struct ConflictCard: View {
    let conflict: ConflictRow
    let resolve: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(conflict.titleHint.isEmpty ? "(untitled)" : conflict.titleHint)
                    .font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink).lineLimit(1)
                Spacer()
                Text(conflict.entityType ?? conflict.entityTable)
                    .font(.system(size: 10)).foregroundColor(Palm.accentInk)
            }
            HStack(alignment: .top, spacing: 10) {
                column("this device", conflict.localUpdatedAt, conflict.localJson)
                Divider()
                column("cloud", conflict.remoteUpdatedAt, conflict.remoteJson)
            }
            HStack(spacing: 10) {
                Button("keep mine") { resolve(true) }
                    .buttonStyle(.plain).foregroundColor(Palm.link)
                Button("keep cloud") { resolve(false) }
                    .buttonStyle(.plain).foregroundColor(Palm.link)
                Spacer()
            }
        }
        .palmCard()
    }

    private func column(_ label: String, _ updated: String, _ json: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 10, weight: .semibold)).foregroundColor(Palm.accentInk)
            Text("updated \(short(updated))").font(.system(size: 10)).foregroundColor(Palm.darkRed)
            Text(json).font(.system(size: 10, design: .monospaced)).foregroundColor(Palm.ink)
                .lineLimit(5).frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func short(_ iso: String) -> String {
        guard let d = Clock.parse(iso) else { return iso }
        return DTU.dateLabel(d) + " " + DTU.timeLabel(d)
    }
}
