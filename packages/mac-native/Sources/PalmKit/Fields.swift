import Foundation

// Type-specific structured fields live in the record's `metadata` JSON blob
// under `palm_*` / `mail_*` keys, exactly matching the PWA + Android so the
// three clients interoperate. `body` carries the free text.

// MARK: - JSON helpers

public enum PalmJSON {
    public static func dict(_ json: String) -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return [:] }
        return obj
    }

    public static func string(_ dict: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys]),
              let s = String(data: data, encoding: .utf8)
        else { return "{}" }
        return s
    }
}

extension Encodable {
    /// Encode self into a `[String: Any]` (using the type's CodingKeys → palm_* names).
    func asDict() -> [String: Any] {
        guard let data = try? JSONEncoder().encode(self),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return [:] }
        return obj
    }

    /// Overlay self onto an existing metadata JSON string, preserving any
    /// keys we don't manage (e.g. AI / upload metadata).
    public func merged(into existingJSON: String) -> String {
        var base = PalmJSON.dict(existingJSON)
        for (k, v) in asDict() { base[k] = v }
        return PalmJSON.string(base)
    }
}

// MARK: - Todo

public struct TodoFields: Codable, Equatable {
    public var palmDueDate: String = ""        // yyyy-MM-dd or ""
    public var palmPriority: Int = 3           // 1..5
    public var palmCompleted: Bool = false
    public var palmNotes: String = ""
    public var palmCategoryName: String = "Unfiled"

    enum CodingKeys: String, CodingKey {
        case palmDueDate = "palm_due_date"
        case palmPriority = "palm_priority"
        case palmCompleted = "palm_completed"
        case palmNotes = "palm_notes"
        case palmCategoryName = "palm_category_name"
    }

    public init() {}

    public init(from json: String) {
        let d = PalmJSON.dict(json)
        palmDueDate = d["palm_due_date"] as? String ?? ""
        palmPriority = (d["palm_priority"] as? Int) ?? Int(d["palm_priority"] as? Double ?? 3)
        palmCompleted = d["palm_completed"] as? Bool ?? false
        palmNotes = d["palm_notes"] as? String ?? ""
        palmCategoryName = d["palm_category_name"] as? String ?? "Unfiled"
    }
}

// MARK: - Contact

public struct ContactFields: Codable, Equatable {
    public var palmFirstName: String = ""
    public var palmLastName: String = ""
    public var palmCompany: String = ""
    public var palmTitle: String = ""
    public var palmPhone: String = ""
    public var palmEmail: String = ""
    public var palmNotes: String = ""

    enum CodingKeys: String, CodingKey {
        case palmFirstName = "palm_first_name"
        case palmLastName = "palm_last_name"
        case palmCompany = "palm_company"
        case palmTitle = "palm_title"
        case palmPhone = "palm_phone"
        case palmEmail = "palm_email"
        case palmNotes = "palm_notes"
    }

    public init() {}

    public init(from json: String) {
        let d = PalmJSON.dict(json)
        palmFirstName = d["palm_first_name"] as? String ?? ""
        palmLastName = d["palm_last_name"] as? String ?? ""
        palmCompany = d["palm_company"] as? String ?? ""
        palmTitle = d["palm_title"] as? String ?? ""
        palmPhone = d["palm_phone"] as? String ?? ""
        palmEmail = d["palm_email"] as? String ?? ""
        palmNotes = d["palm_notes"] as? String ?? ""
    }

    public var displayName: String {
        let last = palmLastName.trimmingCharacters(in: .whitespaces)
        let first = palmFirstName.trimmingCharacters(in: .whitespaces)
        if !last.isEmpty && !first.isEmpty { return "\(last), \(first)" }
        if !last.isEmpty { return last }
        if !first.isEmpty { return first }
        if !palmCompany.isEmpty { return palmCompany }
        return "(no name)"
    }
}

// MARK: - Expense

public struct ExpenseFields: Codable, Equatable {
    public var palmAmount: Double = 0
    public var palmCurrency: String = "HKD"
    public var palmVendor: String = ""
    public var palmExpenseType: String = "Unfiled"
    public var palmPayment: String = "Unfiled"
    public var palmExpenseDate: String = ""
    public var palmCity: String = ""
    public var palmAttendees: String = ""
    public var palmNotes: String = ""
    public var palmCategoryName: String = "Unfiled"

    enum CodingKeys: String, CodingKey {
        case palmAmount = "palm_amount"
        case palmCurrency = "palm_currency"
        case palmVendor = "palm_vendor"
        case palmExpenseType = "palm_expense_type"
        case palmPayment = "palm_payment"
        case palmExpenseDate = "palm_expense_date"
        case palmCity = "palm_city"
        case palmAttendees = "palm_attendees"
        case palmNotes = "palm_notes"
        case palmCategoryName = "palm_category_name"
    }

    public init() {}

    public init(from json: String) {
        let d = PalmJSON.dict(json)
        palmAmount = (d["palm_amount"] as? Double) ?? Double(d["palm_amount"] as? Int ?? 0)
        palmCurrency = d["palm_currency"] as? String ?? "HKD"
        palmVendor = d["palm_vendor"] as? String ?? ""
        palmExpenseType = d["palm_expense_type"] as? String ?? "Unfiled"
        palmPayment = d["palm_payment"] as? String ?? "Unfiled"
        palmExpenseDate = d["palm_expense_date"] as? String ?? ""
        palmCity = d["palm_city"] as? String ?? ""
        palmAttendees = d["palm_attendees"] as? String ?? ""
        palmNotes = d["palm_notes"] as? String ?? ""
        palmCategoryName = d["palm_category_name"] as? String ?? "Unfiled"
    }
}
