import SwiftUI
import PalmKit
import UniformTypeIdentifiers
import AppKit

struct SettingsScreen: View {
    let env: AppEnv
    @EnvironmentObject private var i18n: I18n
    @ObservedObject private var sync: SyncEngine
    @StateObject private var calSubs: CalSubsVM

    @State private var signedIn: Bool
    @State private var email = ""
    @State private var code = ""
    @State private var phase: AuthPhase = .enterEmail
    @State private var busy = false
    @State private var message: String?
    @AppStorage("weekStartsMonday") private var weekStartsMonday = false
    @AppStorage("calRefreshHours") private var calRefreshHours = 0

    @State private var newSubName = ""
    @State private var newSubUrl = ""
    @State private var calMessage: String?
    @State private var showImporter = false
    @State private var icalToken: String?

    enum AuthPhase { case enterEmail, enterCode }

    init(env: AppEnv) {
        self.env = env
        _sync = ObservedObject(wrappedValue: env.sync)
        _calSubs = StateObject(wrappedValue: CalSubsVM(repo: env.repo))
        _signedIn = State(initialValue: env.cloud.isSignedIn)
    }

    var body: some View {
        PalmScaffold(title: i18n.t("settings")) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    accountSection
                    Divider()
                    prefsSection
                    Divider()
                    calendarSection
                    if signedIn {
                        Divider()
                        icalSection
                    }
                    Divider()
                    aiSection
                }
                .padding(16)
                .frame(maxWidth: 480, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
        }
        .task { if signedIn { icalToken = try? await env.cloud.currentIcalToken() } }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [UTType(filenameExtension: "ics") ?? .data],
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                if let text = try? String(contentsOf: url, encoding: .utf8) {
                    let n = env.calendar.importIcs(text: text)
                    calMessage = "imported \(n) events"
                }
            }
        }
    }

    // MARK: Calendar subscriptions

    @ViewBuilder private var calendarSection: some View {
        Text(i18n.t("calendarSubs")).font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
        VStack(alignment: .leading, spacing: 8) {
            Text("Subscribe to a Google Calendar \"secret iCal address\" or any .ics feed (read-only).")
                .font(.system(size: 11)).foregroundColor(Palm.accentInk)
            ForEach(calSubs.subs) { sub in
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(sub.name).font(.system(size: 12)).foregroundColor(Palm.ink).lineLimit(1)
                        Text(sub.url).font(.system(size: 10)).foregroundColor(Palm.link).lineLimit(1)
                    }
                    Spacer()
                    Button("remove") { env.calendar.removeSubscription(url: sub.url) }
                        .buttonStyle(.plain).foregroundColor(Palm.offline).font(.system(size: 11))
                }
            }
            Divider()
            PalmLabeledField(label: "name", text: $newSubName, placeholder: "Work calendar")
            PalmLabeledField(label: "iCal url", text: $newSubUrl, placeholder: "https://…/basic.ics")
            HStack(spacing: 12) {
                Button("add") {
                    let url = newSubUrl.trimmingCharacters(in: .whitespaces)
                    guard !url.isEmpty else { return }
                    env.calendar.addSubscription(name: newSubName.isEmpty ? url : newSubName, url: url)
                    newSubName = ""; newSubUrl = ""
                }
                .buttonStyle(.plain).foregroundColor(Palm.link)
                .disabled(newSubUrl.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("refresh now") {
                    Task { let r = await env.calendar.refreshSubscriptions()
                        calMessage = r.error ?? "refreshed \(r.changed) events" }
                }
                .buttonStyle(.plain).foregroundColor(Palm.link)
                Button("import .ics file…") { showImporter = true }
                    .buttonStyle(.plain).foregroundColor(Palm.link)
            }
            HStack {
                Text("auto-refresh").font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
                Picker("", selection: $calRefreshHours) {
                    Text("off").tag(0); Text("6h").tag(6); Text("12h").tag(12); Text("daily").tag(24)
                }.pickerStyle(.segmented).labelsHidden().frame(maxWidth: 240)
            }
            if let calMessage {
                Text(calMessage).font(.system(size: 11)).foregroundColor(Palm.accentInk)
            }
        }
        .palmCard()
    }

    // MARK: Account / sync

    @ViewBuilder private var accountSection: some View {
        Text(i18n.t("account")).font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)

        if signedIn {
            VStack(alignment: .leading, spacing: 8) {
                if let e = env.cloud.email {
                    Text("Signed in as \(e)").font(.system(size: 12)).foregroundColor(Palm.accentInk)
                }
                Text(syncStatusText).font(.system(size: 11)).foregroundColor(Palm.accentInk)
                HStack(spacing: 10) {
                    Button(i18n.t("syncNow")) { Task { await env.sync.syncNow() } }
                        .buttonStyle(.plain).foregroundColor(Palm.link).disabled(sync.status == .syncing)
                    Button(i18n.t("signOut")) { Task { await signOut() } }
                        .buttonStyle(.plain).foregroundColor(Palm.offline)
                }
            }
            .palmCard()
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text("Sync is optional — the app works fully offline. Sign in to sync with the cloud and your other devices.")
                    .font(.system(size: 11)).foregroundColor(Palm.accentInk)
                if phase == .enterEmail {
                    PalmLabeledField(label: "e-mail", text: $email, placeholder: "you@example.com")
                    Button("send code") { Task { await sendCode() } }
                        .buttonStyle(.plain).foregroundColor(Palm.link)
                        .disabled(busy || !email.contains("@"))
                } else {
                    Text("Enter the 6-digit code sent to \(email).")
                        .font(.system(size: 11)).foregroundColor(Palm.accentInk)
                    PalmLabeledField(label: "code", text: $code, placeholder: "123456")
                    HStack(spacing: 10) {
                        Button("verify") { Task { await verify() } }
                            .buttonStyle(.plain).foregroundColor(Palm.link)
                            .disabled(busy || code.count < 6)
                        Button("change e-mail") { phase = .enterEmail; code = "" }
                            .buttonStyle(.plain).foregroundColor(Palm.accentInk)
                    }
                }
                if let message {
                    Text(message).font(.system(size: 11)).foregroundColor(Palm.offline)
                }
            }
            .palmCard()
        }
    }

    private var syncStatusText: String {
        switch sync.status {
        case .idle: return "idle"
        case .syncing: return "syncing…"
        case .success:
            if let t = sync.lastSyncedAt { return "last synced \(DTU.timeLabel(t))" }
            return "synced"
        case .error: return "sync error: \(sync.lastError ?? "unknown")"
        }
    }

    // MARK: Preferences

    @ViewBuilder private var prefsSection: some View {
        Text(i18n.t("preferences")).font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(i18n.t("language")).font(.system(size: 12)).foregroundColor(Palm.ink)
                Picker("", selection: Binding(get: { i18n.lang }, set: { i18n.lang = $0 })) {
                    ForEach(Lang.allCases) { Text($0.display).tag($0) }
                }
                .labelsHidden().frame(maxWidth: 200)
            }
            Toggle(isOn: $weekStartsMonday) {
                Text(i18n.t("weekStartsMonday")).font(.system(size: 12)).foregroundColor(Palm.ink)
            }
            .toggleStyle(.checkbox)
        }
        .palmCard()
    }

    // MARK: iCal feed (publish)

    @ViewBuilder private var icalSection: some View {
        Text("Publish calendar (iCal feed)").font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
        VStack(alignment: .leading, spacing: 8) {
            if let token = icalToken, !token.isEmpty {
                let url = env.cloud.icalFeedURL(token: token)
                Text("Subscribe this URL in Apple Calendar / Google to see your PalmVellum events (read-only).")
                    .font(.system(size: 11)).foregroundColor(Palm.accentInk)
                Text(url).font(.system(size: 10)).foregroundColor(Palm.link).textSelection(.enabled).lineLimit(2)
                HStack(spacing: 12) {
                    Button("copy") { copyToClipboard(url) }.buttonStyle(.plain).foregroundColor(Palm.link)
                    Button("revoke") {
                        Task { try? await env.cloud.revokeIcalToken(); icalToken = nil }
                    }.buttonStyle(.plain).foregroundColor(Palm.offline)
                }
            } else {
                Text("Generate a secret feed URL to subscribe your calendar elsewhere.")
                    .font(.system(size: 11)).foregroundColor(Palm.accentInk)
                Button("generate feed url") {
                    Task { icalToken = try? await env.cloud.mintIcalToken() }
                }.buttonStyle(.plain).foregroundColor(Palm.link)
            }
        }
        .palmCard()
    }

    private func copyToClipboard(_ s: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
    }

    // MARK: AI / BYOK

    @ViewBuilder private var aiSection: some View {
        Text("AI keys & credits").font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
        VStack(alignment: .leading, spacing: 6) {
            Text("AI features use your own provider key (BYOK), managed on the web settings page. Keys never touch this app.")
                .font(.system(size: 11)).foregroundColor(Palm.accentInk)
            Link("open web settings", destination: URL(string: SupabaseConfig.webSettingsURL)!)
                .font(.system(size: 12)).foregroundColor(Palm.link)
        }
        .palmCard()
    }

    // MARK: Actions

    private func sendCode() async {
        busy = true; message = nil
        defer { busy = false }
        do { try await env.cloud.sendOtp(email: email.trimmingCharacters(in: .whitespaces)); phase = .enterCode }
        catch { message = "Could not send code: \(error.localizedDescription)" }
    }

    private func verify() async {
        busy = true; message = nil
        defer { busy = false }
        do {
            try await env.cloud.verifyOtp(email: email.trimmingCharacters(in: .whitespaces),
                                          token: code.trimmingCharacters(in: .whitespaces))
            signedIn = env.cloud.isSignedIn
            code = ""
            await env.sync.syncNow()
        } catch {
            message = "Invalid or expired code."
        }
    }

    private func signOut() async {
        try? await env.cloud.signOut()
        signedIn = env.cloud.isSignedIn
        phase = .enterEmail
        email = ""; code = ""
    }
}
