import SwiftUI
import PalmKit

private struct EventEdit: Identifiable {
    let id: String
    var event: EventRecord
    let isNew: Bool
}

struct DateBookScreen: View {
    @StateObject private var vm: DateBookVM
    @EnvironmentObject private var router: Router
    @EnvironmentObject private var i18n: I18n
    @AppStorage("weekStartsMonday") private var weekStartsMonday = false
    @State private var mode = "month"            // agenda | week | month
    @State private var anchor = Date()           // navigated month/week
    @State private var selectedDay = DTU.startOfDay(Date())
    @State private var editing: EventEdit?
    @State private var planText = ""

    init(repo: Repository, sync: SyncEngine) {
        _vm = StateObject(wrappedValue: DateBookVM(repo: repo, sync: sync))
    }

    var body: some View {
        PalmScaffold(title: i18n.t("datebook"), titleAction: AnyView(titleControls)) {
            VStack(spacing: 0) {
                periodBar
                planBar
                Divider()
                switch mode {
                case "agenda": agendaView
                case "week": weekView
                default: monthView
                }
            }
        }
        .sheet(item: $editing) { edit in
            EventEditorSheet(
                event: edit.event,
                isNew: edit.isNew,
                onSave: { vm.save($0); editing = nil },
                onDelete: edit.isNew ? nil : { vm.delete(edit.event.id); editing = nil },
                onCancel: { editing = nil }
            )
            .frame(width: 420, height: 460)
        }
    }

    private var titleControls: some View {
        HStack(spacing: 8) {
            PalmFilterStrip(options: ["agenda", "week", "month"], selection: $mode, labelFor: { i18n.t($0) })
            PalmTitleButton(label: i18n.t("new")) { newEvent(on: selectedDay) }
        }
    }

    private var periodBar: some View {
        HStack {
            Button(action: shiftBack) { Text("\u{2039}").font(.system(size: 18)).foregroundColor(Palm.ink) }
                .buttonStyle(.plain)
            Spacer()
            Text(periodTitle).font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
            Spacer()
            Button(action: shiftFwd) { Text("\u{203A}").font(.system(size: 18)).foregroundColor(Palm.ink) }
                .buttonStyle(.plain)
            Button(i18n.t("today")) { anchor = Date(); selectedDay = DTU.startOfDay(Date()) }
                .buttonStyle(.plain).foregroundColor(Palm.link).font(.system(size: 12))
        }
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(Palm.desk)
    }

    private var periodTitle: String {
        switch mode {
        case "agenda": return "next 60 days"
        case "week": return DTU.dateLabel(weekStart) + " – " + DTU.dateLabel(DTU.addDays(weekStart, 6))
        default: return DTU.monthTitle(anchor)
        }
    }

    // MARK: Windows

    private var planBar: some View {
        HStack(spacing: 8) {
            TextField("plan with AI…", text: $planText)
                .textFieldStyle(.plain).font(.system(size: 12))
                .padding(5).background(Palm.field)
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.line, lineWidth: 1))
                .onSubmit(submitPlan)
            Button("go", action: submitPlan).buttonStyle(.plain).foregroundColor(Palm.link)
                .disabled(planText.trimmingCharacters(in: .whitespaces).isEmpty)
            if !vm.pendingDrafts.isEmpty {
                Text("AI thinking… (\(vm.pendingDrafts.count))")
                    .font(.system(size: 11)).foregroundColor(Palm.darkRed)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 5)
        .background(Palm.desk)
    }

    private func submitPlan() {
        vm.planWithAI(planText)
        planText = ""
    }

    private var weekdayHeaders: [String] {
        let base = DTU.weekdayHeaders // Sun..Sat
        return weekStartsMonday ? Array(base[1...]) + [base[0]] : base
    }

    private var weekStart: Date {
        let wd = DTU.cal.component(.weekday, from: anchor) // 1=Sun..7=Sat
        let shift = weekStartsMonday ? (wd + 5) % 7 : (wd - 1)
        return DTU.startOfDay(DTU.addDays(anchor, -shift))
    }

    private func occurrences(start: Date, end: Date) -> [Occurrence] {
        EventExpansion.expand(events: vm.events, todos: vm.todos, start: start, end: end)
    }

    // MARK: Agenda

    private var agendaView: some View {
        let start = DTU.startOfDay(Date())
        let end = DTU.addDays(start, 60)
        let occ = occurrences(start: start, end: end)
        let days = DTU.nextDays(61, from: start)
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(days, id: \.self) { day in
                    let items = EventExpansion.occurrences(occ, on: day)
                    if !items.isEmpty || DTU.isToday(day) {
                        dayHeader(day)
                        if items.isEmpty {
                            Text("(nothing scheduled)").font(.system(size: 11))
                                .foregroundColor(Palm.accentInk).padding(.horizontal, 14).padding(.bottom, 6)
                        } else {
                            ForEach(items) { occRow($0) }
                        }
                    }
                }
            }
            .padding(.vertical, 6)
        }
    }

    // MARK: Week

    private var weekView: some View {
        let start = weekStart
        let occ = occurrences(start: start, end: DTU.addDays(start, 7))
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(DTU.nextDays(7, from: start), id: \.self) { day in
                    dayHeader(day)
                    let items = EventExpansion.occurrences(occ, on: day)
                    if items.isEmpty {
                        Text("(nothing scheduled)").font(.system(size: 11))
                            .foregroundColor(Palm.accentInk).padding(.horizontal, 14).padding(.bottom, 6)
                    } else {
                        ForEach(items) { occRow($0) }
                    }
                }
            }
            .padding(.vertical, 6)
        }
    }

    // MARK: Month (two-pane: grid + selected day)

    private var monthView: some View {
        let grid = DTU.monthGrid(anchor, weekStartsMonday: weekStartsMonday)
        let occ = occurrences(start: grid.first ?? anchor, end: grid.last ?? anchor)
        return HStack(spacing: 0) {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    ForEach(weekdayHeaders, id: \.self) { h in
                        Text(h).font(.system(size: 10, weight: .semibold))
                            .foregroundColor(Palm.accentInk)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.vertical, 4)
                let rows = stride(from: 0, to: 42, by: 7).map { Array(grid[$0..<$0 + 7]) }
                ForEach(Array(rows.enumerated()), id: \.offset) { _, week in
                    HStack(spacing: 0) {
                        ForEach(week, id: \.self) { day in monthCell(day, occ: occ) }
                    }
                }
            }
            .padding(8)
            Divider()
            dayDetail(occ: occurrences(start: DTU.startOfDay(selectedDay), end: DTU.addDays(selectedDay, 1)))
                .frame(width: 240)
        }
    }

    private func monthCell(_ day: Date, occ: [Occurrence]) -> some View {
        let inMonth = DTU.monthOf(day) == DTU.monthOf(anchor)
        let count = EventExpansion.occurrences(occ, on: day).count
        let isSel = DTU.sameDay(day, selectedDay)
        return Button(action: { selectedDay = DTU.startOfDay(day) }) {
            VStack(spacing: 2) {
                Text(DTU.dayNumber(day))
                    .font(.system(size: 12, weight: DTU.isToday(day) ? .bold : .regular))
                    .foregroundColor(DTU.isToday(day) ? Palm.onDark : (inMonth ? Palm.ink : Palm.line))
                    .frame(width: 22, height: 18)
                    .background(DTU.isToday(day) ? Palm.darkRed : Color.clear)
                    .clipShape(RoundedRectangle(cornerRadius: 3))
                if count > 0 {
                    Text("\(count)").font(.system(size: 9)).foregroundColor(Palm.darkRed)
                } else {
                    Spacer().frame(height: 11)
                }
            }
            .frame(maxWidth: .infinity).frame(height: 44)
            .background(DTU.isWeekend(day) ? Palm.desk.opacity(0.5) : Color.clear)
            .overlay(RoundedRectangle(cornerRadius: 3).stroke(isSel ? Palm.titlebar : Palm.line.opacity(0.4),
                                                              lineWidth: isSel ? 1.5 : 0.5))
        }
        .buttonStyle(.plain)
    }

    private func dayDetail(occ: [Occurrence]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(DTU.dateLabel(selectedDay)).font(.system(size: 12, weight: .semibold)).foregroundColor(Palm.ink)
                Spacer()
                Button("+ add") { newEvent(on: selectedDay) }.buttonStyle(.plain)
                    .foregroundColor(Palm.link).font(.system(size: 11))
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            Divider()
            if occ.isEmpty {
                EmptyHint(text: "(nothing scheduled)")
            } else {
                ScrollView { LazyVStack(spacing: 0) { ForEach(occ) { occRow($0) } } }
            }
        }
        .background(Palm.desk)
    }

    // MARK: Shared rows

    private func dayHeader(_ day: Date) -> some View {
        Text(DTU.longDateLabel(day))
            .font(.system(size: 11, weight: .semibold))
            .foregroundColor(DTU.isToday(day) ? Palm.darkRed : Palm.accentInk)
            .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 2)
    }

    private func occRow(_ occ: Occurrence) -> some View {
        Button(action: { open(occ) }) {
            HStack(alignment: .top, spacing: 8) {
                Text(occ.timeLabel)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(Palm.darkRed)
                    .frame(width: 58, alignment: .leading)
                Text(occ.title)
                    .font(.system(size: 13))
                    .foregroundColor(occ.isTodo ? Palm.accentInk : Palm.ink)
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Actions

    private func open(_ occ: Occurrence) {
        if occ.isTodo {
            router.go(.todo)
        } else if let e = occ.event {
            editing = EventEdit(id: e.id, event: e, isNew: false)
        }
    }

    private func newEvent(on day: Date) {
        let start = DTU.cal.date(bySettingHour: 9, minute: 0, second: 0, of: day) ?? day
        let e = EventRecord.new(title: "", startAt: DTU.iso(start),
                                endAt: DTU.iso(DTU.cal.date(byAdding: .hour, value: 1, to: start) ?? start))
        editing = EventEdit(id: e.id, event: e, isNew: true)
    }

    private func shiftBack() {
        switch mode {
        case "week": anchor = DTU.addDays(anchor, -7)
        case "agenda": break
        default: anchor = DTU.addMonths(anchor, -1)
        }
    }
    private func shiftFwd() {
        switch mode {
        case "week": anchor = DTU.addDays(anchor, 7)
        case "agenda": break
        default: anchor = DTU.addMonths(anchor, 1)
        }
    }
}

// MARK: - Event editor sheet

private struct EventEditorSheet: View {
    @State var event: EventRecord
    let isNew: Bool
    let onSave: (EventRecord) -> Void
    var onDelete: (() -> Void)?
    let onCancel: () -> Void

    @State private var title: String
    @State private var allDay: Bool
    @State private var start: Date
    @State private var end: Date
    @State private var location: String
    @State private var notes: String
    @State private var repeatRule: String

    init(event: EventRecord, isNew: Bool,
         onSave: @escaping (EventRecord) -> Void,
         onDelete: (() -> Void)?,
         onCancel: @escaping () -> Void) {
        _event = State(initialValue: event)
        self.isNew = isNew
        self.onSave = onSave
        self.onDelete = onDelete
        self.onCancel = onCancel
        _title = State(initialValue: event.title)
        _allDay = State(initialValue: event.allDay)
        _start = State(initialValue: DTU.parse(event.startAt))
        _end = State(initialValue: event.endAt.map(DTU.parse) ?? DTU.parse(event.startAt))
        _location = State(initialValue: event.location ?? "")
        _notes = State(initialValue: event.notes ?? "")
        _repeatRule = State(initialValue: Self.tokenFor(event.repeatRule))
    }

    private static let repeatOptions = ["none", "daily", "weekly", "monthly", "yearly"]
    private static func tokenFor(_ rule: String?) -> String {
        guard let rule, let r = rule.range(of: "FREQ=", options: .caseInsensitive) else { return "none" }
        let token = rule[r.upperBound...].prefix { $0.isLetter }.lowercased()
        return repeatOptions.contains(token) ? token : "none"
    }
    private static func ruleFor(_ token: String) -> String? {
        token == "none" ? nil : "FREQ=\(token.uppercased())"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            EditorBar(title: isNew ? "new event" : "event",
                      saveEnabled: !title.trimmingCharacters(in: .whitespaces).isEmpty,
                      onCancel: onCancel, onSave: save)
            PalmLabeledField(label: "title", text: $title, placeholder: "event title")
            Toggle(isOn: $allDay) { Text("all-day").font(.system(size: 12)) }.toggleStyle(.checkbox)
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("start").font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
                    DatePicker("", selection: $start,
                               displayedComponents: allDay ? .date : [.date, .hourAndMinute])
                        .labelsHidden()
                }
                if !allDay {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("end").font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
                        DatePicker("", selection: $end, displayedComponents: [.date, .hourAndMinute])
                            .labelsHidden()
                    }
                }
            }
            PalmLabeledField(label: "location", text: $location)
            VStack(alignment: .leading, spacing: 3) {
                Text("repeats").font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
                Picker("", selection: $repeatRule) {
                    ForEach(Self.repeatOptions, id: \.self) { Text($0).tag($0) }
                }.pickerStyle(.segmented).labelsHidden()
            }
            PalmLabeledEditor(label: "notes", text: $notes, minHeight: 50)
            Spacer()
            if let onDelete {
                HStack { Spacer(); DeleteButton(action: onDelete) }
            }
        }
        .padding(14)
        .background(Palm.desk)
    }

    private func save() {
        let trimmed = title.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        var e = event
        e.title = trimmed
        e.allDay = allDay
        e.startAt = DTU.iso(start)
        e.endAt = allDay ? nil : DTU.iso(max(end, start))
        e.location = location.isEmpty ? nil : location
        e.notes = notes.isEmpty ? nil : notes
        e.repeatRule = Self.ruleFor(repeatRule)
        onSave(e)
    }
}
