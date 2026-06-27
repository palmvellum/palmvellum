import SwiftUI
import PalmKit

struct TodoScreen: View {
    @StateObject private var vm: TodoVM
    @EnvironmentObject private var i18n: I18n
    @State private var selection: String?
    @State private var filter: String = "Open"

    init(repo: Repository) { _vm = StateObject(wrappedValue: TodoVM(repo: repo)) }

    private var filtered: [PalmRecord] {
        vm.todos.filter { rec in
            let done = TodoFields(from: rec.metadata).palmCompleted
            switch filter {
            case "Open": return !done
            case "Done": return done
            default: return true
            }
        }
    }

    var body: some View {
        PalmScaffold(
            title: i18n.t("todo"),
            titleAction: AnyView(PalmTitleButton(label: i18n.t("new")) { selection = "new" })
        ) {
            VStack(spacing: 0) {
                HStack {
                    PalmFilterStrip(options: ["Open", "Done", "All"], selection: $filter,
                                    labelFor: { i18n.t($0.lowercased()) })
                    Spacer()
                }
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(Palm.desk)
                Divider()
                HStack(spacing: 0) {
                    masterList.frame(width: 260)
                    Divider()
                    detail.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
    }

    private var masterList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(filtered) { todo in
                    let f = TodoFields(from: todo.metadata)
                    HStack(spacing: 8) {
                        Button(action: { vm.toggleDone(todo) }) {
                            Text(f.palmCompleted ? "[x]" : "[ ]")
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundColor(Palm.ink)
                        }
                        .buttonStyle(.plain)
                        Button(action: { selection = todo.id }) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(todo.body ?? "(untitled)")
                                    .font(.system(size: 13))
                                    .strikethrough(f.palmCompleted)
                                    .foregroundColor(Palm.ink)
                                    .lineLimit(1)
                                if AIRequest.isBusy(todo.aiStatus) {
                                    Text("AI thinking…").font(.system(size: 10)).foregroundColor(Palm.darkRed)
                                } else if !f.palmDueDate.isEmpty {
                                    Text("due \(f.palmDueDate)  ·  P\(f.palmPriority)")
                                        .font(.system(size: 10))
                                        .foregroundColor(Palm.darkRed)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 7)
                    .background(selection == todo.id ? Palm.field : Color.clear)
                    Divider()
                }
            }
        }
        .background(Palm.desk)
    }

    @ViewBuilder private var detail: some View {
        if selection == "new" {
            TodoEditor(existing: nil,
                       onSave: { vm.save($0); selection = $0.id },
                       onCancel: { selection = nil })
                .id("new")
        } else if let id = selection, let todo = vm.todos.first(where: { $0.id == id }) {
            TodoEditor(existing: todo,
                       onSave: { vm.save($0) },
                       onCancel: { selection = nil },
                       onDelete: { vm.delete(todo.id); selection = nil })
                .id(todo.id)
        } else {
            EmptyHint(text: "(no task selected — + new to start)")
        }
    }
}

private struct TodoEditor: View {
    let existing: PalmRecord?
    let onSave: (PalmRecord) -> Void
    let onCancel: () -> Void
    var onDelete: (() -> Void)?

    @State private var desc: String
    @State private var priority: Int
    @State private var hasDue: Bool
    @State private var due: Date
    @State private var notes: String

    init(existing: PalmRecord?,
         onSave: @escaping (PalmRecord) -> Void,
         onCancel: @escaping () -> Void,
         onDelete: (() -> Void)? = nil) {
        self.existing = existing
        self.onSave = onSave
        self.onCancel = onCancel
        self.onDelete = onDelete
        let f = TodoFields(from: existing?.metadata ?? "{}")
        _desc = State(initialValue: existing?.body ?? "")
        _priority = State(initialValue: f.palmPriority)
        _hasDue = State(initialValue: !f.palmDueDate.isEmpty)
        _due = State(initialValue: Self.parseDue(f.palmDueDate))
        _notes = State(initialValue: f.palmNotes)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                EditorBar(
                    title: existing == nil ? "new task" : "task",
                    saveEnabled: !desc.trimmingCharacters(in: .whitespaces).isEmpty,
                    onCancel: onCancel,
                    onSave: save
                )
                PalmLabeledField(label: "description", text: $desc, placeholder: "what to do")

                VStack(alignment: .leading, spacing: 3) {
                    Text("priority").font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
                    Picker("", selection: $priority) {
                        ForEach(1...5, id: \.self) { Text("P\($0)").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                Toggle(isOn: $hasDue) {
                    Text("has due date").font(.system(size: 12)).foregroundColor(Palm.ink)
                }
                .toggleStyle(.checkbox)
                if hasDue {
                    DatePicker("", selection: $due, displayedComponents: .date)
                        .labelsHidden()
                        .datePickerStyle(.field)
                }

                PalmLabeledEditor(label: "notes", text: $notes)

                if let onDelete {
                    HStack { Spacer(); DeleteButton(action: onDelete) }
                }
            }
            .padding(12)
        }
    }

    private func save() {
        let trimmed = desc.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        var f = TodoFields(from: existing?.metadata ?? "{}")
        f.palmPriority = priority
        f.palmDueDate = hasDue ? Self.fmtDue(due) : ""
        f.palmNotes = notes
        var rec = existing ?? PalmRecord.new(type: RecordType.todo)
        rec.body = desc
        rec.metadata = f.merged(into: rec.metadata)
        if AIRequest.isAiRequest(desc) { rec.aiStatus = "pending" }
        onSave(rec)
    }

    private static let dueFmt: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.timeZone = .current; return f
    }()
    private static func parseDue(_ s: String) -> Date { dueFmt.date(from: s) ?? Date() }
    private static func fmtDue(_ d: Date) -> String { dueFmt.string(from: d) }
}
