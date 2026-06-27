import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// Palm OS 5 "silver" palette — aligned with the PWA (`src/android.css`) and
/// the Android app (`ui/theme/Color.kt`). Title bars are dark grey with light
/// text; content cards are off-white "vellum" on a silver desk.
enum Palm {
    static let desk = Color(hex: 0xCFD1CC)       // silver desk background
    static let vellum = Color(hex: 0xE6E6E1)     // off-white card surface
    static let field = Color(hex: 0xF4F4EE)      // input field surface
    static let titlebar = Color(hex: 0x4A4A48)   // dark grey title bar / selection
    static let line = Color(hex: 0x6B6C68)       // hairlines
    static let ink = Color.black                 // body text
    static let accentInk = Color(hex: 0x2B2B2B)  // action ink (not yellow here)
    static let green = Color(hex: 0x1E7A3A)
    static let link = Color(hex: 0x00467A)       // classic Mac/Palm blue
    static let darkRed = Color(hex: 0x8B1A1A)    // clock times / agenda meta
    static let online = Color(hex: 0x2ECC71)     // title-bar net dot (online)
    static let offline = Color(hex: 0xC0392B)    // title-bar net dot (offline)
    static let onDark = Color(hex: 0xF4F4EE)     // text on the dark title bar

    /// 15-colour category palette (left-border accents / chips) — distinguishes
    /// record kinds WITHOUT glyphs, per the no-emoji hard constraint.
    enum Category {
        static let ai = Color(hex: 0x6A4FB0)
        static let personal = Color(hex: 0x2E7D32)
        static let business = Color(hex: 0x1565C0)
        static let family = Color(hex: 0xAD1457)
        static let medical = Color(hex: 0xC62828)
        static let travel = Color(hex: 0x00838F)
        static let food = Color(hex: 0xEF6C00)
        static let finance = Color(hex: 0x4E342E)
        static let research = Color(hex: 0x37474F)
        static let mail = Color(hex: 0x5D4037)
        static let note = Color(hex: 0x827717)
        static let todo = Color(hex: 0x283593)
        static let event = Color(hex: 0x00695C)
        static let sketch = Color(hex: 0x6A1B9A)
        static let contact = Color(hex: 0x455A64)
    }
}
