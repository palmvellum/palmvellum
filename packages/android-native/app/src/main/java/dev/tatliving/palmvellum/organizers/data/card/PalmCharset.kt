package dev.tatliving.palmvellum.organizers.data.card

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Bridges the cloud's UTF-8 text and the encoding a PalmOS device expects in
 * its record bytes — a faithful Kotlin port of the project's Go engine
 * (`packages/palm-engine/charset/charset.go`), so the USB HotSync path on the
 * Cosmo and the desktop card/cable paths share one on-the-wire contract.
 *
 * The cloud (PWA, Supabase) is the UTF-8 source of truth. PalmOS native apps
 * store record text as raw bytes; with a CJKOS-equipped device those bytes are
 * interpreted as Big5 (Traditional Chinese, incl. HKSCS for Cantonese). Plain
 * ASCII passes through Big5 unchanged, so an English-only Palm is unaffected.
 *
 * Big5 cannot represent emoji / simplified-only glyphs / most non-CJK symbols;
 * [toPalm] degrades any un-mappable character to '?' (lossy, one-way). The
 * cloud copy keeps the full UTF-8 text; the device is a lossy view of it.
 */
object PalmCharset {

    // HKSCS superset first (covers the Cantonese 喺哋嘅 the user tests with);
    // fall back to plain Big5, then — on the rare JVM without Big5 — Latin-1,
    // which at least keeps ASCII intact.
    private val charset: Charset = runCatching { Charset.forName("Big5-HKSCS") }
        .recoverCatching { Charset.forName("Big5") }
        .getOrElse { Charsets.ISO_8859_1 }

    /**
     * UTF-8 string -> device bytes. Un-mappable characters become '?'. Line
     * endings are normalised to a bare LF (0x0A), the PalmOS line break — a
     * stray CR renders as a garbage glyph on the device.
     */
    fun toPalm(s: String): ByteArray {
        val normalized = s.replace("\r\n", "\n").replace('\r', '\n')
        val enc = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(byteArrayOf('?'.code.toByte()))
        return runCatching {
            val bb = enc.encode(java.nio.CharBuffer.wrap(normalized))
            ByteArray(bb.remaining()).also { bb.get(it) }
        }.getOrElse { normalized.toByteArray(Charsets.ISO_8859_1) }
    }

    /** Device bytes -> UTF-8 string. Invalid sequences degrade leniently. */
    fun fromPalm(b: ByteArray): String {
        val dec = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() }
            .getOrElse { String(b, Charsets.ISO_8859_1) }
    }
}
