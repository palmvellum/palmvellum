// Package ui carries the desktop app's look. PalmTheme mirrors the web
// "palm organizer" aesthetic — a silver Palm-OS desk, cream paper
// surfaces, dark warm-gray chrome, near-black primary ink, Palm green/red
// for status, and IBM Plex Mono throughout. Retro, but professional.
//
// Palette tracks packages/pwa/src/android.css (the canonical Palm light
// theme) so the desktop and web organizers read as one product.
package ui

import (
	_ "embed"
	"image/color"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/theme"
)

//go:embed fonts/IBMPlexMono-Regular.ttf
var regularTTF []byte

//go:embed fonts/IBMPlexMono-Bold.ttf
var boldTTF []byte

var (
	fontRegular = &fyne.StaticResource{StaticName: "IBMPlexMono-Regular.ttf", StaticContent: regularTTF}
	fontBold    = &fyne.StaticResource{StaticName: "IBMPlexMono-SemiBold.ttf", StaticContent: boldTTF}
)

// PalmTheme is a light-only theme matching the web palm organizer.
type PalmTheme struct{}

var _ fyne.Theme = PalmTheme{}

func (PalmTheme) Color(n fyne.ThemeColorName, _ fyne.ThemeVariant) color.Color {
	switch n {
	case theme.ColorNameBackground:
		return rgb(0xCF, 0xD1, 0xCC) // desk silver  (--bg)
	case theme.ColorNameForeground:
		return rgb(0x1A, 0x1A, 0x1A) // ink          (--ink-dim)
	case theme.ColorNameForegroundOnPrimary, theme.ColorNameForegroundOnError, theme.ColorNameForegroundOnSuccess:
		return rgb(0xFF, 0xFF, 0xFF)
	case theme.ColorNamePrimary, theme.ColorNameFocus:
		return rgb(0x2B, 0x2B, 0x2B) // primary action ink (--accent, deliberately not yellow)
	case theme.ColorNameButton:
		return rgb(0xF4, 0xF4, 0xEE) // cream paper  (--surface-hi)
	case theme.ColorNameDisabledButton:
		return rgb(0xDD, 0xDD, 0xD7)
	case theme.ColorNameHeaderBackground, theme.ColorNameMenuBackground, theme.ColorNameOverlayBackground:
		return rgb(0xE6, 0xE6, 0xE1) // off-white     (--surface-lo)
	case theme.ColorNameInputBackground:
		return rgb(0xF4, 0xF4, 0xEE)
	case theme.ColorNameInputBorder, theme.ColorNameSeparator:
		return rgb(0x6B, 0x6C, 0x68) // hairline      (--line)
	case theme.ColorNamePlaceHolder, theme.ColorNameDisabled:
		return rgb(0x77, 0x77, 0x73)
	case theme.ColorNameHover, theme.ColorNamePressed:
		return rgba(0x00, 0x00, 0x00, 0x16)
	case theme.ColorNameSelection:
		return rgba(0x2B, 0x2B, 0x2B, 0x33)
	case theme.ColorNameScrollBar:
		return rgba(0x00, 0x00, 0x00, 0x55)
	case theme.ColorNameScrollBarBackground:
		return rgba(0x00, 0x00, 0x00, 0x10)
	case theme.ColorNameShadow:
		return rgba(0x00, 0x00, 0x00, 0x30)
	case theme.ColorNameSuccess:
		return rgb(0x1E, 0x7A, 0x3A) // palm green    (--green)
	case theme.ColorNameError, theme.ColorNameWarning:
		return rgb(0x8B, 0x1A, 0x1A) // palm red
	case theme.ColorNameHyperlink:
		return rgb(0x1E, 0x5A, 0x8A)
	}
	return theme.DefaultTheme().Color(n, theme.VariantLight)
}

// Font returns IBM Plex Mono for every style — the monospace face is core
// to the organizer look.
func (PalmTheme) Font(s fyne.TextStyle) fyne.Resource {
	if s.Bold {
		return fontBold
	}
	return fontRegular
}

func (PalmTheme) Icon(n fyne.ThemeIconName) fyne.Resource {
	return theme.DefaultTheme().Icon(n)
}

func (PalmTheme) Size(n fyne.ThemeSizeName) float32 {
	switch n {
	case theme.SizeNameInputRadius, theme.SizeNameSelectionRadius, theme.SizeNameScrollBarRadius:
		return 0 // boxy Palm corners
	case theme.SizeNameInputBorder, theme.SizeNameSeparatorThickness:
		return 1
	}
	return theme.DefaultTheme().Size(n)
}

func rgb(r, g, b uint8) color.Color     { return color.NRGBA{R: r, G: g, B: b, A: 0xFF} }
func rgba(r, g, b, a uint8) color.Color { return color.NRGBA{R: r, G: g, B: b, A: a} }
