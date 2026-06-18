// Package charset bridges the cloud's UTF-8 text and the Traditional
// Chinese Big5 encoding that a CJKOS-equipped PalmOS device expects.
//
// The cloud (PWA, Supabase) is the UTF-8 source of truth. PalmOS native
// apps store record text as raw bytes; with CJKOS set to Traditional
// Chinese those bytes are interpreted as Big5. Writing UTF-8 straight
// into a .pdb therefore renders as 亂碼 on the device — every string that
// crosses into or out of a .pdb must pass through here.
//
// Big5 is a strict subset of Unicode: emoji, simplified-only glyphs and
// most non-CJK symbols have no Big5 code point. ToPalm replaces any
// un-mappable rune with '?' (a lossy, one-way degrade). The cloud copy
// keeps the full UTF-8 text; the card is a lossy view of it.
package charset

import (
	"bytes"
	"io"
	"strings"

	"golang.org/x/text/encoding/traditionalchinese"
	"golang.org/x/text/transform"
)

// ToPalm converts a UTF-8 string to Big5 bytes for storage in a .pdb.
// Runes with no Big5 representation become '?'. Line endings are
// normalised to a bare LF (0x0A), the PalmOS line break — a stray CR
// renders as a garbage glyph on the device.
func ToPalm(s string) []byte {
	return transformReplace(normalizeNewlines(s))
}

// FromPalm converts Big5 bytes read from a .pdb back to a UTF-8 string.
// Invalid Big5 sequences are replaced with U+FFFD by the decoder.
func FromPalm(b []byte) string {
	dec := traditionalchinese.Big5.NewDecoder()
	r := transform.NewReader(bytes.NewReader(b), dec)
	out, err := io.ReadAll(r)
	if err != nil {
		// Decoder is lenient; on a hard error fall back to raw.
		return string(b)
	}
	return string(out)
}

// transformReplace encodes s to Big5, substituting '?' for any rune the
// Big5 encoder cannot represent. We drive the transform rune-by-rune so
// a single bad rune degrades to '?' instead of truncating the output.
func transformReplace(s string) []byte {
	enc := traditionalchinese.Big5.NewEncoder()
	var buf bytes.Buffer
	for _, r := range s {
		b, err := enc.Bytes([]byte(string(r)))
		if err != nil {
			buf.WriteByte('?')
			enc.Reset()
			continue
		}
		buf.Write(b)
	}
	return buf.Bytes()
}

// normalizeNewlines collapses CRLF and lone CR to LF.
func normalizeNewlines(s string) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	return s
}
