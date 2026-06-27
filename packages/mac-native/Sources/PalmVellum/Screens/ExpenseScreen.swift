import SwiftUI
import PalmKit

enum ExpenseLists {
    static let types = [
        "Unfiled", "Airfare", "Breakfast", "Bus", "Business Meals", "Car Rental", "Dinner",
        "Entertainment", "Fax", "Gas", "Gifts", "Hotel", "Incidentals", "Laundry", "Limo",
        "Lodging", "Lunch", "Mileage", "Other", "Parking", "Postage", "Snack", "Subway",
        "Supplies", "Taxi", "Telephone", "Tips", "Tolls", "Train"
    ]
    static let payments = ["Unfiled", "Amex", "Cash", "Check", "Credit Card", "MasterCard", "Prepaid", "VISA"]
    static let currencies = ["HKD", "USD", "EUR", "GBP", "JPY", "CNY", "TWD", "KRW"]
}

struct ExpenseScreen: View {
    @StateObject private var vm: ExpenseVM
    @EnvironmentObject private var i18n: I18n
    @State private var selection: String?
    @State private var search = ""

    init(repo: Repository) { _vm = StateObject(wrappedValue: ExpenseVM(repo: repo)) }

    private var filtered: [PalmRecord] {
        guard !search.trimmingCharacters(in: .whitespaces).isEmpty else { return vm.expenses }
        let q = search.lowercased()
        return vm.expenses.filter { rec in
            let f = ExpenseFields(from: rec.metadata)
            return [f.palmVendor, f.palmExpenseType, f.palmCity, f.palmAttendees]
                .joined(separator: " ").lowercased().contains(q)
        }
    }

    private var totals: [(String, Double)] {
        var byCur: [String: Double] = [:]
        for r in vm.expenses {
            let f = ExpenseFields(from: r.metadata)
            byCur[f.palmCurrency, default: 0] += f.palmAmount
        }
        return byCur.sorted { $0.key < $1.key }
    }

    var body: some View {
        PalmScaffold(
            title: i18n.t("expense"),
            titleAction: AnyView(PalmTitleButton(label: i18n.t("new")) { selection = "new" })
        ) {
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    TextField("search", text: $search)
                        .textFieldStyle(.plain).padding(6).background(Palm.field)
                        .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.line, lineWidth: 1)).padding(8)
                    Divider()
                    masterList
                    Divider()
                    totalsBar
                }
                .frame(width: 270)
                Divider()
                detail.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private var masterList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(filtered) { exp in
                    let f = ExpenseFields(from: exp.metadata)
                    Button(action: { selection = exp.id }) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(f.palmVendor.isEmpty ? f.palmExpenseType : f.palmVendor)
                                    .font(.system(size: 13)).foregroundColor(Palm.ink).lineLimit(1)
                                Text("\(f.palmExpenseType)\(f.palmExpenseDate.isEmpty ? "" : "  ·  \(f.palmExpenseDate)")")
                                    .font(.system(size: 10)).foregroundColor(Palm.accentInk).lineLimit(1)
                            }
                            Spacer()
                            Text(String(format: "%@ %.2f", f.palmCurrency, f.palmAmount))
                                .font(.system(size: 11, weight: .medium)).foregroundColor(Palm.darkRed)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .background(selection == exp.id ? Palm.field : Color.clear)
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
        }
        .background(Palm.desk)
    }

    private var totalsBar: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("totals").font(.system(size: 10, weight: .semibold)).foregroundColor(Palm.accentInk)
            ForEach(totals, id: \.0) { cur, amt in
                Text(String(format: "%@  %.2f", cur, amt)).font(.system(size: 11)).foregroundColor(Palm.ink)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8).background(Palm.desk)
    }

    @ViewBuilder private var detail: some View {
        if selection == "new" {
            ExpenseEditor(existing: nil,
                          onSave: { vm.save($0); selection = $0.id },
                          onCancel: { selection = nil })
                .id("new")
        } else if let id = selection, let exp = vm.expenses.first(where: { $0.id == id }) {
            ExpenseEditor(existing: exp,
                          onSave: { vm.save($0) },
                          onCancel: { selection = nil },
                          onDelete: { vm.delete(exp.id); selection = nil })
                .id(exp.id)
        } else {
            EmptyHint(text: "(no expense selected — + new to start)")
        }
    }
}

private struct ExpenseEditor: View {
    let existing: PalmRecord?
    let onSave: (PalmRecord) -> Void
    let onCancel: () -> Void
    var onDelete: (() -> Void)?

    @State private var vendor: String
    @State private var amount: String
    @State private var currency: String
    @State private var type: String
    @State private var payment: String
    @State private var hasDate: Bool
    @State private var date: Date
    @State private var city: String
    @State private var attendees: String
    @State private var notes: String

    init(existing: PalmRecord?,
         onSave: @escaping (PalmRecord) -> Void,
         onCancel: @escaping () -> Void,
         onDelete: (() -> Void)? = nil) {
        self.existing = existing
        self.onSave = onSave
        self.onCancel = onCancel
        self.onDelete = onDelete
        let f = ExpenseFields(from: existing?.metadata ?? "{}")
        _vendor = State(initialValue: f.palmVendor)
        _amount = State(initialValue: f.palmAmount == 0 ? "" : String(format: "%.2f", f.palmAmount))
        _currency = State(initialValue: f.palmCurrency)
        _type = State(initialValue: f.palmExpenseType)
        _payment = State(initialValue: f.palmPayment)
        _hasDate = State(initialValue: !f.palmExpenseDate.isEmpty)
        _date = State(initialValue: Self.parse(f.palmExpenseDate))
        _city = State(initialValue: f.palmCity)
        _attendees = State(initialValue: f.palmAttendees)
        _notes = State(initialValue: f.palmNotes)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                EditorBar(title: existing == nil ? "new expense" : "expense",
                          saveEnabled: !(amount.isEmpty && vendor.isEmpty),
                          onCancel: onCancel, onSave: save)
                PalmLabeledField(label: "vendor", text: $vendor)
                HStack(spacing: 8) {
                    PalmLabeledField(label: "amount", text: $amount, placeholder: "0.00")
                    picker("currency", $currency, ExpenseLists.currencies)
                }
                picker("type", $type, ExpenseLists.types)
                picker("payment", $payment, ExpenseLists.payments)
                Toggle(isOn: $hasDate) { Text("has date").font(.system(size: 12)) }.toggleStyle(.checkbox)
                if hasDate {
                    DatePicker("", selection: $date, displayedComponents: .date).labelsHidden()
                }
                PalmLabeledField(label: "city", text: $city)
                PalmLabeledField(label: "attendees", text: $attendees)
                PalmLabeledEditor(label: "notes", text: $notes, minHeight: 50)
                if let onDelete { HStack { Spacer(); DeleteButton(action: onDelete) } }
            }
            .padding(12)
        }
    }

    private func picker(_ label: String, _ sel: Binding<String>, _ options: [String]) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
            Picker("", selection: sel) { ForEach(options, id: \.self) { Text($0).tag($0) } }
                .labelsHidden().frame(maxWidth: 220, alignment: .leading)
        }
    }

    private func save() {
        var f = ExpenseFields(from: existing?.metadata ?? "{}")
        f.palmVendor = vendor
        f.palmAmount = Double(amount.replacingOccurrences(of: ",", with: "")) ?? 0
        f.palmCurrency = currency
        f.palmExpenseType = type
        f.palmPayment = payment
        f.palmExpenseDate = hasDate ? Self.fmt(date) : ""
        f.palmCity = city
        f.palmAttendees = attendees
        f.palmNotes = notes
        var rec = existing ?? PalmRecord.new(type: RecordType.expense)
        rec.body = vendor
        rec.metadata = f.merged(into: rec.metadata)
        onSave(rec)
    }

    private static let df: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.timeZone = .current; return f
    }()
    private static func parse(_ s: String) -> Date { df.date(from: s) ?? Date() }
    private static func fmt(_ d: Date) -> String { df.string(from: d) }
}
