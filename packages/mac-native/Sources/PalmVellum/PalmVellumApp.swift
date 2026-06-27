import SwiftUI

@main
struct PalmVellumApp: App {
    @StateObject private var env = AppEnv()
    @StateObject private var router = Router()
    @StateObject private var net = NetStatus()
    @StateObject private var i18n = I18n()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(env)
                .environmentObject(router)
                .environmentObject(net)
                .environmentObject(i18n)
                .frame(minWidth: 640, minHeight: 560)
                .preferredColorScheme(.light) // Palm OS 5 silver theme is light;
                                              // forces dark text on light fields
                .tint(Palm.link)
        }
        .windowStyle(.titleBar)
    }
}

struct RootView: View {
    @EnvironmentObject private var env: AppEnv
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var net: NetStatus

    var body: some View {
        content
            .task { env.bootstrap(net: net) }
            .onChange(of: router.route) { _ in
                if env.cloud.isSignedIn { Task { await env.sync.syncNow() } }
            }
    }

    @ViewBuilder private var content: some View {
        switch router.route {
        case .launcher: LauncherScreen(repo: env.repo)
        case .datebook: DateBookScreen(repo: env.repo, sync: env.sync)
        case .todo: TodoScreen(repo: env.repo)
        case .address: AddressScreen(repo: env.repo)
        case .memo: MemoScreen(repo: env.repo, cloud: env.cloud, sync: env.sync)
        case .notepad: NotePadScreen(repo: env.repo)
        case .expense: ExpenseScreen(repo: env.repo)
        case .mail: MailScreen(repo: env.repo, cloud: env.cloud)
        case .hotsync: HotSyncScreen()
        case .settings: SettingsScreen(env: env)
        case .conflicts: ConflictsScreen(env: env)
        }
    }
}
