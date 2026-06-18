package charset

import "testing"

func TestRoundTripASCII(t *testing.T) {
	in := "Order FTDI 232RL cable\n-- AI --\nok"
	got := FromPalm(ToPalm(in))
	if got != in {
		t.Fatalf("ascii round-trip: got %q want %q", got, in)
	}
}

func TestRoundTripTraditionalChinese(t *testing.T) {
	in := "繁體中文測試：你好，世界"
	got := FromPalm(ToPalm(in))
	if got != in {
		t.Fatalf("big5 round-trip: got %q want %q", got, in)
	}
}

func TestEmDashIsBig5Mappable(t *testing.T) {
	// Sanity: the old separator's em-dash must NOT survive as raw UTF-8.
	// Either it maps to a Big5 byte pair or degrades to '?', but it must
	// never emit the 3-byte UTF-8 sequence e2 80 94 that broke CJKOS.
	b := ToPalm("—")
	if len(b) >= 3 && b[0] == 0xe2 && b[1] == 0x80 && b[2] == 0x94 {
		t.Fatalf("em-dash leaked as raw UTF-8: % x", b)
	}
}

func TestUnmappableRuneBecomesQuestionMark(t *testing.T) {
	// Emoji has no Big5 code point → '?'. Surrounding text survives.
	b := ToPalm("a🤖b")
	got := FromPalm(b)
	if got != "a?b" {
		t.Fatalf("unmappable: got %q want %q", got, "a?b")
	}
}

func TestNewlineNormalization(t *testing.T) {
	b := ToPalm("a\r\nb\rc")
	if got := FromPalm(b); got != "a\nb\nc" {
		t.Fatalf("newline normalize: got %q", got)
	}
}
