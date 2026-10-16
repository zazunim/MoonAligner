import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

public enum ColorTheme {
    public static let nightSky = Color(hex: 0x0B0E14)
    public static let surfaceDark = Color(hex: 0x151922)
    public static let cardBackground = Color(hex: 0x1C2230)

    public static let moonGlow = Color(hex: 0xFFF3D1)
    public static let moonSurface = Color(hex: 0xE8D5A3)
    public static let sunGold = Color(hex: 0xFFB300)
    public static let accentOrange = Color(hex: 0xFF7043)
    public static let accentBlue = Color(hex: 0x38BDF8)
    public static let roseAccent = Color(hex: 0xF43F5E)

    public static let textPrimary = Color(hex: 0xF0F4F8)
    public static let textSecondary = Color(hex: 0x94A3B8)
    public static let gridLine = Color(hex: 0x64748B, alpha: 0.25)
}

extension Color {
    public init(hex: UInt, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255.0,
            green: Double((hex >> 08) & 0xff) / 255.0,
            blue: Double((hex >> 00) & 0xff) / 255.0,
            opacity: alpha
        )
    }

    public init(hex: String, alpha: Double = 1.0) {
        let scanner = Scanner(string: hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted))
        var int: UInt64 = 0
        scanner.scanHexInt64(&int)
        self.init(hex: UInt(int), alpha: alpha)
    }

    public static let nightSky = ColorTheme.nightSky
    public static let surfaceDark = ColorTheme.surfaceDark
    public static let cardBackground = ColorTheme.cardBackground

    public static let moonGlow = ColorTheme.moonGlow
    public static let moonSurface = ColorTheme.moonSurface
    public static let sunGold = ColorTheme.sunGold
    public static let accentOrange = ColorTheme.accentOrange
    public static let accentBlue = ColorTheme.accentBlue
    public static let roseAccent = ColorTheme.roseAccent

    public static let textPrimary = ColorTheme.textPrimary
    public static let textSecondary = ColorTheme.textSecondary
    public static let gridLine = ColorTheme.gridLine
}

#if canImport(UIKit)
extension UIColor {
    public convenience init(hex: UInt, alpha: CGFloat = 1.0) {
        self.init(
            red: CGFloat((hex >> 16) & 0xff) / 255.0,
            green: CGFloat((hex >> 8) & 0xff) / 255.0,
            blue: CGFloat(hex & 0xff) / 255.0,
            alpha: alpha
        )
    }

    public static let nightSkyUI = UIColor(hex: 0x0B0E14)
    public static let surfaceDarkUI = UIColor(hex: 0x151922)
    public static let cardBackgroundUI = UIColor(hex: 0x1C2230)
    public static let sunGoldUI = UIColor(hex: 0xFFB300)
    public static let textPrimaryUI = UIColor(hex: 0xF0F4F8)
    public static let textSecondaryUI = UIColor(hex: 0x94A3B8)
}
#endif
