import SwiftUI

/// Temporary stand-in for an organizer screen until its phase lands.
struct PlaceholderScreen: View {
    let title: String
    var note: String = "P1 起逐步實裝" // 起逐步實裝

    var body: some View {
        PalmScaffold(title: title) {
            VStack(spacing: 6) {
                Spacer()
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(Palm.ink)
                Text(note)
                    .font(.system(size: 12))
                    .foregroundColor(Palm.accentInk)
                Spacer()
            }
            .frame(maxWidth: .infinity)
        }
    }
}
