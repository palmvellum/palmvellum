import SwiftUI
import PalmKit

struct MailScreen: View {
    @StateObject private var vm: MailVM
    @EnvironmentObject private var i18n: I18n
    @State private var tab = "Inbox"
    @State private var selection: String?

    init(repo: Repository, cloud: CloudClient) {
        _vm = StateObject(wrappedValue: MailVM(repo: repo, cloud: cloud))
    }

    var body: some View {
        PalmScaffold(
            title: i18n.t("mail"),
            titleAction: AnyView(deleteAllAction)
        ) {
            VStack(spacing: 0) {
                HStack {
                    PalmFilterStrip(options: ["Inbox", "Sources"], selection: $tab,
                                    labelFor: { i18n.t($0.lowercased()) })
                    Spacer()
                }
                .padding(.horizontal, 10).padding(.vertical, 6).background(Palm.desk)
                Divider()
                if tab == "Inbox" { inbox } else { sources }
            }
        }
        .task { await vm.loadSources() }
    }

    @ViewBuilder private var deleteAllAction: some View {
        if tab == "Inbox" && !vm.inbox.isEmpty {
            PalmTitleButton(label: i18n.t("deleteAll")) { vm.deleteAll(); selection = nil }
        } else { EmptyView() }
    }

    // MARK: Inbox

    private var inbox: some View {
        HStack(spacing: 0) {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(vm.inbox) { mail in
                        let meta = PalmJSON.dict(mail.metadata)
                        Button(action: { selection = mail.id }) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(meta["mail_subject"] as? String ?? "(no subject)")
                                    .font(.system(size: 13)).foregroundColor(Palm.ink).lineLimit(1)
                                Text(meta["mail_from"] as? String ?? meta["mail_source_name"] as? String ?? "")
                                    .font(.system(size: 10)).foregroundColor(Palm.accentInk).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 10).padding(.vertical, 7)
                            .background(selection == mail.id ? Palm.field : Color.clear)
                        }
                        .buttonStyle(.plain)
                        Divider()
                    }
                }
            }
            .frame(width: 260).background(Palm.desk)
            Divider()
            if let id = selection, let mail = vm.inbox.first(where: { $0.id == id }) {
                mailReader(mail)
            } else if vm.inbox.isEmpty {
                EmptyHint(text: "(inbox empty — add a source to receive AI digests)")
            } else {
                EmptyHint(text: "(select a message)")
            }
        }
    }

    private func mailReader(_ mail: PalmRecord) -> some View {
        let meta = PalmJSON.dict(mail.metadata)
        return ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(meta["mail_subject"] as? String ?? "(no subject)")
                        .font(.system(size: 15, weight: .semibold)).foregroundColor(Palm.ink)
                    Spacer()
                    DeleteButton { vm.delete(mail.id); selection = nil }
                }
                if let from = meta["mail_from"] as? String ?? meta["mail_source_name"] as? String {
                    Text(from).font(.system(size: 11)).foregroundColor(Palm.accentInk)
                }
                Divider()
                Text(mail.body ?? "").font(.system(size: 13)).foregroundColor(Palm.ink).textSelection(.enabled)
                if let refs = meta["mail_references"] as? [String], !refs.isEmpty {
                    Divider()
                    Text("references").font(.system(size: 10, weight: .semibold)).foregroundColor(Palm.accentInk)
                    ForEach(refs, id: \.self) { r in
                        Text(r).font(.system(size: 11)).foregroundColor(Palm.link).textSelection(.enabled)
                    }
                }
            }
            .padding(14)
        }
    }

    // MARK: Sources

    @ViewBuilder private var sources: some View {
        if !vm.isSignedIn {
            EmptyHint(text: "(sign in to manage mail subscriptions)")
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if let err = vm.sourcesError {
                        Text(err).font(.system(size: 10)).foregroundColor(Palm.offline)
                    }
                    ForEach(vm.sources) { src in
                        sourceRow(src)
                    }
                    Divider()
                    AddSourceForm { push in Task { await vm.saveSource(push) } }
                }
                .padding(14).frame(maxWidth: 520).frame(maxWidth: .infinity)
            }
        }
    }

    private func sourceRow(_ src: MailSource) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(src.name).font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
                Spacer()
                Text(src.source_type).font(.system(size: 10)).foregroundColor(Palm.accentInk)
            }
            if let u = src.url, !u.isEmpty { Text(u).font(.system(size: 10)).foregroundColor(Palm.link).lineLimit(1) }
            if let t = src.topic, !t.isEmpty { Text("topic: \(t)").font(.system(size: 10)).foregroundColor(Palm.accentInk) }
            HStack(spacing: 12) {
                Text(src.enabled ? "enabled" : "disabled").font(.system(size: 10)).foregroundColor(Palm.accentInk)
                Button("fetch now") { Task { await vm.fetchNow(src.id) } }.buttonStyle(.plain).foregroundColor(Palm.link).font(.system(size: 11))
                Button("delete") { Task { await vm.deleteSource(src.id) } }.buttonStyle(.plain).foregroundColor(Palm.offline).font(.system(size: 11))
            }
        }
        .palmCard()
    }
}

private struct AddSourceForm: View {
    let onAdd: (MailSourcePush) -> Void
    @State private var name = ""
    @State private var type = "url"
    @State private var url = ""
    @State private var topic = ""
    @State private var language = "auto"

    private let languages = ["auto", "English", "繁體中文", "简体中文", "日本語", "한국어", "Русский"]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("add subscription").font(.system(size: 12, weight: .semibold)).foregroundColor(Palm.ink)
            PalmLabeledField(label: "name", text: $name)
            Picker("", selection: $type) { Text("url").tag("url"); Text("topic").tag("topic") }
                .pickerStyle(.segmented).labelsHidden()
            if type == "url" {
                PalmLabeledField(label: "feed url", text: $url, placeholder: "https://…")
            } else {
                PalmLabeledField(label: "topic", text: $topic, placeholder: "what to research")
            }
            HStack {
                Text("language").font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
                Picker("", selection: $language) { ForEach(languages, id: \.self) { Text($0).tag($0) } }.labelsHidden()
            }
            Button("add") { add() }.buttonStyle(.plain).foregroundColor(Palm.link)
                .disabled(name.isEmpty || (type == "url" ? url.isEmpty : topic.isEmpty))
        }
        .palmCard()
    }

    private func add() {
        let push = MailSourcePush(
            id: Ulid.new(), user_id: "", name: name,
            url: type == "url" ? url : nil, topic: type == "topic" ? topic : nil,
            source_type: type, enabled: true, fetch_time: "07:00:00", timezone: "Asia/Hong_Kong",
            output_language: language == "auto" ? nil : language)
        onAdd(push)
        name = ""; url = ""; topic = ""
    }
}
