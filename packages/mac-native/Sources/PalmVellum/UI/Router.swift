import SwiftUI

/// Classic Palm "apps". Mirrors the Android `Routes` object.
enum Route: Hashable {
    case launcher, datebook, todo, address, memo, notepad, expense, mail, hotsync, settings, conflicts
}

@MainActor
final class Router: ObservableObject {
    @Published var route: Route = .launcher

    func go(_ r: Route) { route = r }
    func home() { route = .launcher }
}
