import SwiftUI
import PalmKit
import UniformTypeIdentifiers

struct MemoScreen: View {
    @StateObject private var vm: MemoVM
    @EnvironmentObject private var i18n: I18n
    @State private var selection: String?
    @State private var showImporter = false

    init(repo: Repository, cloud: CloudClient, sync: SyncEngine) {
        _vm = StateObject(wrappedValue: MemoVM(repo: repo, cloud: cloud, sync: sync))
    }

    var body: some View {
        PalmScaffold(
            title: i18n.t("memo"),
            titleAction: AnyView(titleActions)
        ) {
            HStack(spacing: 0) {
                masterList.frame(width: 240)
                Divider()
                detail.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [.pdf, .image, UTType(filenameExtension: "docx") ?? .data],
                      allowsMultipleSelection: false) { result in
            handleImport(result)
        }
    }

    private var titleActions: some View {
        HStack(spacing: 10) {
            if vm.canUpload {
                PalmTitleButton(label: "+ file") { showImporter = true }
            }
            PalmTitleButton(label: i18n.t("new")) { selection = "new" }
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else { return }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), data.count <= 20 * 1024 * 1024 else { return }
        let filename = url.lastPathComponent
        let mime = mimeType(for: url.pathExtension)
        Task { await vm.uploadFile(data: data, filename: filename, mimetype: mime) }
    }

    private func mimeType(for ext: String) -> String {
        switch ext.lowercased() {
        case "pdf": return "application/pdf"
        case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        default: return "application/octet-stream"
        }
    }

    private var masterList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(vm.memos) { memo in
                    Button(action: { selection = memo.id }) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(firstLine(memo.body))
                                .font(.system(size: 13))
                                .foregroundColor(Palm.ink)
                                .lineLimit(1)
                            Text(AIRequest.isBusy(memo.aiStatus) ? "AI thinking…" : preview(memo.body))
                                .font(.system(size: 11))
                                .foregroundColor(AIRequest.isBusy(memo.aiStatus) ? Palm.darkRed : Palm.accentInk)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .background(selection == memo.id ? Palm.field : Color.clear)
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
        }
        .background(Palm.desk)
    }

    @ViewBuilder private var detail: some View {
        if selection == "new" {
            MemoEditor(existing: nil,
                       onSave: { vm.save($0); selection = $0.id },
                       onCancel: { selection = nil })
                .id("new")
        } else if let id = selection, let memo = vm.memos.first(where: { $0.id == id }) {
            MemoEditor(existing: memo,
                       onSave: { vm.save($0) },
                       onCancel: { selection = nil },
                       onDelete: { vm.delete(memo.id); selection = nil })
                .id(memo.id)
        } else {
            EmptyHint(text: "(no memo selected — + new to start)")
        }
    }

    private func firstLine(_ s: String?) -> String {
        let line = (s ?? "").split(separator: "\n", maxSplits: 1).first.map(String.init) ?? ""
        return line.isEmpty ? "(untitled)" : line
    }

    private func preview(_ s: String?) -> String {
        let parts = (s ?? "").split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
        return parts.count > 1 ? String(parts[1]).trimmingCharacters(in: .whitespacesAndNewlines) : ""
    }
}

private struct MemoEditor: View {
    let existing: PalmRecord?
    let onSave: (PalmRecord) -> Void
    let onCancel: () -> Void
    var onDelete: (() -> Void)?

    @State private var text: String

    init(existing: PalmRecord?,
         onSave: @escaping (PalmRecord) -> Void,
         onCancel: @escaping () -> Void,
         onDelete: (() -> Void)? = nil) {
        self.existing = existing
        self.onSave = onSave
        self.onCancel = onCancel
        self.onDelete = onDelete
        _text = State(initialValue: existing?.body ?? "")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            EditorBar(
                title: existing == nil ? "new memo" : "memo",
                saveEnabled: !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                onCancel: onCancel,
                onSave: save
            )
            TextEditor(text: $text)
                .font(.system(size: 13))
                .foregroundColor(Palm.ink)
                .padding(6)
                .background(Palm.field)
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.line, lineWidth: 1))
            if let onDelete {
                HStack { Spacer(); DeleteButton(action: onDelete) }
            }
        }
        .padding(12)
    }

    private func save() {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var rec = existing ?? PalmRecord.new(type: RecordType.thought)
        rec.body = text
        if AIRequest.isAiRequest(text) { rec.aiStatus = "pending" }
        onSave(rec)
    }
}
