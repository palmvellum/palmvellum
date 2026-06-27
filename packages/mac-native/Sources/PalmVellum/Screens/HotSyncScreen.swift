import SwiftUI

@MainActor
final class HotSyncVM: ObservableObject {
    @Published var running = false
    @Published var status: DaemonStatus?
    @Published var message: String?
    @Published var busy = false

    private let client = DaemonClient()

    func refresh() async {
        running = await client.isRunning()
        status = running ? (try? await client.status()) : nil
    }

    func cardSync() async {
        busy = true; message = nil
        defer { busy = false }
        do {
            let r = try await client.cardSync()
            message = r.message ?? (r.ok ? "card synced" : "card sync failed")
        } catch {
            message = "card sync failed — is a backup card inserted?"
        }
        await refresh()
    }

    func openDaemonApp() {
        if !client.launchDaemonApp() {
            message = "\"PalmVellum Sync on Mac\" not found in /Applications"
        }
    }
}

/// Control surface for the "PalmVellum Sync on Mac" daemon — the Go HotSync engine
/// that bridges physical Palm hardware + Memory Stick cards to the cloud. This
/// native app and the daemon stay loosely coupled: data converges through
/// Supabase; here we just show device state and trigger syncs over :7733.
struct HotSyncScreen: View {
    @StateObject private var vm = HotSyncVM()
    @EnvironmentObject private var i18n: I18n

    var body: some View {
        PalmScaffold(
            title: i18n.t("hotsync"),
            titleAction: AnyView(PalmTitleButton(label: "refresh") { Task { await vm.refresh() } })
        ) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if vm.running {
                        runningCard
                        cardSyncCard
                    } else {
                        notRunningCard
                    }
                    infoCard
                }
                .padding(16)
                .frame(maxWidth: 520, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
        }
        .task { await vm.refresh() }
    }

    private var runningCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Circle().fill(Palm.online).frame(width: 9, height: 9)
                Text("PalmVellum Sync on Mac is running").font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
            }
            statusRow("version", vm.status?.version ?? "—")
            statusRow("account", vm.status?.user ?? "(not signed in)")
            statusRow("Palm on USB", (vm.status?.palmPresent ?? false) ? "yes" : "no")
            statusRow("backup card", vm.status?.card ?? "(none)")
        }
        .palmCard()
    }

    private var cardSyncCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Memory Stick card").font(.system(size: 12, weight: .semibold)).foregroundColor(Palm.ink)
            Text("Insert a Palm backup card, then sync it with the cloud.")
                .font(.system(size: 11)).foregroundColor(Palm.accentInk)
            HStack(spacing: 12) {
                Button("sync card now") { Task { await vm.cardSync() } }
                    .buttonStyle(.plain).foregroundColor(Palm.link).disabled(vm.busy)
                if vm.busy { ProgressView().scaleEffect(0.6) }
            }
            Divider()
            Text("USB cradle HotSync").font(.system(size: 12, weight: .semibold)).foregroundColor(Palm.ink)
            Text("Cradle HotSync is interactive (you press HotSync on the Palm). Open PalmVellum Sync on Mac and use its USB tab.")
                .font(.system(size: 11)).foregroundColor(Palm.accentInk)
            Button("open PalmVellum Sync on Mac") { vm.openDaemonApp() }
                .buttonStyle(.plain).foregroundColor(Palm.link)
            if let m = vm.message {
                Text(m).font(.system(size: 11)).foregroundColor(Palm.darkRed)
            }
        }
        .palmCard()
    }

    private var notRunningCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Circle().fill(Palm.offline).frame(width: 9, height: 9)
                Text("PalmVellum Sync on Mac is not running").font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
            }
            Text("\"PalmVellum Sync on Mac\" is the companion that HotSyncs physical Palm devices and Memory Stick cards. Open it to enable HotSync from here.")
                .font(.system(size: 11)).foregroundColor(Palm.accentInk)
            Button("open PalmVellum Sync on Mac") { vm.openDaemonApp() }
                .buttonStyle(.plain).foregroundColor(Palm.link)
            if let m = vm.message {
                Text(m).font(.system(size: 11)).foregroundColor(Palm.darkRed)
            }
        }
        .palmCard()
    }

    private var infoCard: some View {
        Text("This app and PalmVellum Sync on Mac sync through the same cloud account — anything HotSynced from a Palm appears here automatically after the next sync.")
            .font(.system(size: 10)).foregroundColor(Palm.accentInk)
    }

    private func statusRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.system(size: 11)).foregroundColor(Palm.accentInk).frame(width: 100, alignment: .leading)
            Text(value).font(.system(size: 12)).foregroundColor(Palm.ink).textSelection(.enabled)
            Spacer()
        }
    }
}
