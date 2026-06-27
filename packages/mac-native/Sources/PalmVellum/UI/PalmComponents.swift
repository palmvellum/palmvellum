import SwiftUI

// Shared Palm-styled building blocks. No emoji in any content — text labels
// like `+ new`, `delete`, `(TO DO)` only; glyphs are nav chrome.

struct PalmCard: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(10)
            .background(Palm.vellum)
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(Palm.line, lineWidth: 1))
    }
}

extension View {
    func palmCard() -> some View { modifier(PalmCard()) }
}

/// A labelled single-line input.
struct PalmLabeledField: View {
    let label: String
    @Binding var text: String
    var placeholder: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .foregroundColor(Palm.ink)
                .padding(6)
                .background(Palm.field)
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.line, lineWidth: 1))
                .font(.system(size: 13))
        }
    }
}

/// A labelled multi-line input.
struct PalmLabeledEditor: View {
    let label: String
    @Binding var text: String
    var minHeight: CGFloat = 70

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label).font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
            TextEditor(text: $text)
                .font(.system(size: 13))
                .foregroundColor(Palm.ink)
                .frame(minHeight: minHeight)
                .padding(4)
                .background(Palm.field)
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.line, lineWidth: 1))
        }
    }
}

/// Editor header row: title + Cancel/Save (or back/edit) actions.
struct EditorBar: View {
    let title: String
    var cancelLabel: String? = nil
    var saveLabel: String? = nil
    var saveEnabled: Bool = true
    let onCancel: () -> Void
    let onSave: () -> Void
    @EnvironmentObject private var i18n: I18n

    var body: some View {
        HStack {
            Text(title).font(.system(size: 13, weight: .semibold)).foregroundColor(Palm.ink)
            Spacer()
            Button(cancelLabel ?? i18n.t("cancel"), action: onCancel).buttonStyle(.plain).foregroundColor(Palm.link)
            Button(saveLabel ?? i18n.t("save"), action: onSave)
                .buttonStyle(.plain)
                .foregroundColor(saveEnabled ? Palm.link : Palm.line)
                .disabled(!saveEnabled)
        }
        .padding(.bottom, 4)
    }
}

/// Horizontal filter chips (e.g. Open / Done / All).
struct PalmFilterStrip: View {
    let options: [String]
    @Binding var selection: String
    var labelFor: (String) -> String = { $0 }

    var body: some View {
        HStack(spacing: 6) {
            ForEach(options, id: \.self) { opt in
                Button(action: { selection = opt }) {
                    Text(labelFor(opt))
                        .font(.system(size: 11, weight: .medium))
                        .padding(.horizontal, 9).padding(.vertical, 4)
                        .background(selection == opt ? Palm.titlebar : Palm.vellum)
                        .foregroundColor(selection == opt ? Palm.onDark : Palm.ink)
                        .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.line, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// "+ new" style action used in title bars.
struct PalmTitleButton: View {
    let label: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label).font(.system(size: 12, weight: .medium)).foregroundColor(Palm.onDark)
        }
        .buttonStyle(.plain)
    }
}

struct DeleteButton: View {
    let action: () -> Void
    @EnvironmentObject private var i18n: I18n
    var body: some View {
        Button(action: action) {
            Text(i18n.t("delete"))
                .font(.system(size: 12, weight: .medium))
                .padding(.horizontal, 10).padding(.vertical, 4)
                .foregroundColor(Palm.offline)
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.offline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct EmptyHint: View {
    let text: String
    var body: some View {
        VStack { Spacer(); Text(text).font(.system(size: 12)).foregroundColor(Palm.accentInk); Spacer() }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
