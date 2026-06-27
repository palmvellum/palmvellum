import SwiftUI
import PalmKit

/// Read-only sketch gallery. Sketches originate from a Palm via HotSync (no
/// drawing on the desktop, matching the PWA). Images live in the public
/// `notepad` storage bucket; `body` holds the AI transcription.
struct NotePadScreen: View {
    @StateObject private var vm: NotePadVM
    @EnvironmentObject private var i18n: I18n
    @State private var selection: String?

    init(repo: Repository) { _vm = StateObject(wrappedValue: NotePadVM(repo: repo)) }

    private static func imageURL(_ rec: PalmRecord) -> URL? {
        let meta = PalmJSON.dict(rec.metadata)
        guard let path = meta["image_path"] as? String else { return nil }
        return URL(string: "\(SupabaseConfig.url.absoluteString)/storage/v1/object/public/notepad/\(path)")
    }

    private static func title(_ rec: PalmRecord) -> String {
        (PalmJSON.dict(rec.metadata)["palm_title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "sketch"
    }

    var body: some View {
        PalmScaffold(title: i18n.t("notepad")) {
            if vm.sketches.isEmpty {
                EmptyHint(text: "(no sketches — they arrive from a Palm via HotSync)")
            } else if let id = selection, let rec = vm.sketches.first(where: { $0.id == id }) {
                detail(rec)
            } else {
                gallery
            }
        }
    }

    private var gallery: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                ForEach(vm.sketches) { rec in
                    Button(action: { selection = rec.id }) {
                        VStack(spacing: 4) {
                            sketchImage(rec, height: 110)
                            Text(Self.title(rec)).font(.system(size: 11)).foregroundColor(Palm.ink).lineLimit(1)
                        }
                        .padding(6).background(Palm.vellum)
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(Palm.line, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(14)
        }
    }

    private func detail(_ rec: PalmRecord) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Button("back") { selection = nil }.buttonStyle(.plain).foregroundColor(Palm.link)
                    Spacer()
                    DeleteButton { vm.delete(rec.id); selection = nil }
                }
                Text(Self.title(rec)).font(.system(size: 15, weight: .semibold)).foregroundColor(Palm.ink)
                sketchImage(rec, height: 280)
                if let body = rec.body, !body.isEmpty {
                    Text("transcription").font(.system(size: 11, weight: .semibold)).foregroundColor(Palm.accentInk)
                    Text(body).font(.system(size: 13)).foregroundColor(Palm.ink).textSelection(.enabled)
                } else if rec.aiStatus == "pending" || rec.aiStatus == "processing" {
                    Text("(transcribing…)").font(.system(size: 12)).foregroundColor(Palm.accentInk)
                }
            }
            .padding(14)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
    }

    private func sketchImage(_ rec: PalmRecord, height: CGFloat) -> some View {
        Group {
            if let url = Self.imageURL(rec) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let img): img.resizable().scaledToFit()
                    case .failure: Text("(image unavailable)").font(.system(size: 10)).foregroundColor(Palm.accentInk)
                    default: ProgressView()
                    }
                }
            } else {
                Text("(no image)").font(.system(size: 10)).foregroundColor(Palm.accentInk)
            }
        }
        .frame(maxWidth: .infinity).frame(height: height)
        .background(Color.white)
        .overlay(RoundedRectangle(cornerRadius: 3).stroke(Palm.line, lineWidth: 0.5))
    }
}
