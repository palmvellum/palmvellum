import SwiftUI
import PalmKit

struct AddressScreen: View {
    @StateObject private var vm: AddressVM
    @EnvironmentObject private var i18n: I18n
    @State private var selection: String?
    @State private var editMode = false
    @State private var search = ""

    init(repo: Repository) { _vm = StateObject(wrappedValue: AddressVM(repo: repo)) }

    private var filtered: [PalmRecord] {
        let sorted = vm.contacts.sorted {
            ContactFields(from: $0.metadata).displayName.localizedCaseInsensitiveCompare(
                ContactFields(from: $1.metadata).displayName) == .orderedAscending
        }
        guard !search.trimmingCharacters(in: .whitespaces).isEmpty else { return sorted }
        let q = search.lowercased()
        return sorted.filter { rec in
            let f = ContactFields(from: rec.metadata)
            return [f.palmFirstName, f.palmLastName, f.palmCompany, f.palmPhone, f.palmEmail]
                .joined(separator: " ").lowercased().contains(q)
        }
    }

    var body: some View {
        PalmScaffold(
            title: i18n.t("address"),
            titleAction: AnyView(PalmTitleButton(label: i18n.t("new")) { selection = "new"; editMode = true })
        ) {
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    TextField("search", text: $search)
                        .textFieldStyle(.plain)
                        .padding(6).background(Palm.field)
                        .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.line, lineWidth: 1))
                        .padding(8)
                    Divider()
                    masterList
                }
                .frame(width: 250)
                Divider()
                detail.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private var masterList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(filtered) { contact in
                    let f = ContactFields(from: contact.metadata)
                    Button(action: { selection = contact.id; editMode = false }) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.displayName).font(.system(size: 13)).foregroundColor(Palm.ink).lineLimit(1)
                            if !f.palmCompany.isEmpty {
                                Text(f.palmCompany).font(.system(size: 11)).foregroundColor(Palm.accentInk).lineLimit(1)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .background(selection == contact.id ? Palm.field : Color.clear)
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
            ContactEditor(existing: nil,
                          onSave: { vm.save($0); selection = $0.id; editMode = false },
                          onCancel: { selection = nil; editMode = false })
                .id("new")
        } else if let id = selection, let contact = vm.contacts.first(where: { $0.id == id }) {
            if editMode {
                ContactEditor(existing: contact,
                              onSave: { vm.save($0); editMode = false },
                              onCancel: { editMode = false },
                              onDelete: { vm.delete(contact.id); selection = nil; editMode = false })
                    .id(contact.id)
            } else {
                ContactCard(fields: ContactFields(from: contact.metadata),
                            onEdit: { editMode = true })
            }
        } else {
            EmptyHint(text: "(no contact selected — + new to start)")
        }
    }
}

private struct ContactCard: View {
    let fields: ContactFields
    let onEdit: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(fields.displayName).font(.system(size: 17, weight: .semibold)).foregroundColor(Palm.ink)
                    Spacer()
                    Button("edit", action: onEdit).buttonStyle(.plain).foregroundColor(Palm.link)
                }
                if !fields.palmTitle.isEmpty || !fields.palmCompany.isEmpty {
                    Text([fields.palmTitle, fields.palmCompany].filter { !$0.isEmpty }.joined(separator: ", "))
                        .font(.system(size: 13)).foregroundColor(Palm.accentInk)
                }
                Divider()
                cardRow("Phone", fields.palmPhone)
                cardRow("E-mail", fields.palmEmail)
                cardRow("Notes", fields.palmNotes)
            }
            .padding(14)
        }
    }

    @ViewBuilder private func cardRow(_ label: String, _ value: String) -> some View {
        if !value.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.system(size: 10, weight: .semibold)).foregroundColor(Palm.accentInk)
                Text(value).font(.system(size: 13)).foregroundColor(Palm.ink).textSelection(.enabled)
            }
        }
    }
}

private struct ContactEditor: View {
    let existing: PalmRecord?
    let onSave: (PalmRecord) -> Void
    let onCancel: () -> Void
    var onDelete: (() -> Void)?

    @State private var first: String
    @State private var last: String
    @State private var company: String
    @State private var title: String
    @State private var phone: String
    @State private var email: String
    @State private var notes: String

    init(existing: PalmRecord?,
         onSave: @escaping (PalmRecord) -> Void,
         onCancel: @escaping () -> Void,
         onDelete: (() -> Void)? = nil) {
        self.existing = existing
        self.onSave = onSave
        self.onCancel = onCancel
        self.onDelete = onDelete
        let f = ContactFields(from: existing?.metadata ?? "{}")
        _first = State(initialValue: f.palmFirstName)
        _last = State(initialValue: f.palmLastName)
        _company = State(initialValue: f.palmCompany)
        _title = State(initialValue: f.palmTitle)
        _phone = State(initialValue: f.palmPhone)
        _email = State(initialValue: f.palmEmail)
        _notes = State(initialValue: f.palmNotes)
    }

    private var canSave: Bool {
        !(first + last + company).trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                EditorBar(title: existing == nil ? "new contact" : "contact",
                          saveEnabled: canSave, onCancel: onCancel, onSave: save)
                HStack(spacing: 8) {
                    PalmLabeledField(label: "first", text: $first)
                    PalmLabeledField(label: "last", text: $last)
                }
                PalmLabeledField(label: "company", text: $company)
                PalmLabeledField(label: "title", text: $title)
                PalmLabeledField(label: "phone", text: $phone)
                PalmLabeledField(label: "e-mail", text: $email)
                PalmLabeledEditor(label: "notes", text: $notes)
                if let onDelete {
                    HStack { Spacer(); DeleteButton(action: onDelete) }
                }
            }
            .padding(12)
        }
    }

    private func save() {
        guard canSave else { return }
        var f = ContactFields(from: existing?.metadata ?? "{}")
        f.palmFirstName = first; f.palmLastName = last; f.palmCompany = company
        f.palmTitle = title; f.palmPhone = phone; f.palmEmail = email; f.palmNotes = notes
        var rec = existing ?? PalmRecord.new(type: RecordType.contact)
        rec.body = f.displayName
        rec.metadata = f.merged(into: rec.metadata)
        onSave(rec)
    }
}
