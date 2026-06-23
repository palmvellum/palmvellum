package dev.tatliving.palmvellum.organizers.data.hotsync

import dev.tatliving.palmvellum.organizers.data.card.AddressDb
import dev.tatliving.palmvellum.organizers.data.card.AddressPhone
import dev.tatliving.palmvellum.organizers.data.card.Appointment
import dev.tatliving.palmvellum.organizers.data.card.Contact
import dev.tatliving.palmvellum.organizers.data.card.DatebookDb
import dev.tatliving.palmvellum.organizers.data.card.Mail
import dev.tatliving.palmvellum.organizers.data.card.MailDb
import dev.tatliving.palmvellum.organizers.data.card.Memo
import dev.tatliving.palmvellum.organizers.data.card.MemoAppInfo
import dev.tatliving.palmvellum.organizers.data.card.MemoDb
import dev.tatliving.palmvellum.organizers.data.card.PalmDate
import dev.tatliving.palmvellum.organizers.data.card.PalmDb
import dev.tatliving.palmvellum.organizers.data.card.ToDoDb
import dev.tatliving.palmvellum.organizers.data.card.Todo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for the HotSync record codecs and SLP framing — the layers that
 * can be verified without a physical Palm. The live USB / DLP path still needs
 * real-hardware validation (see docs/cross-platform-desktop-sync-feasibility.md).
 */
class HotSyncCodecTest {

    @Test fun memo_roundTrip() {
        val memos = listOf(
            Memo(uniqueId = 0x123456, category = 2, text = "Buy milk\nand eggs"),
            Memo(uniqueId = 0x000001, category = 0, text = "Plain ASCII note"),
        )
        val db = MemoDb.newDb(MemoAppInfo.default()).apply { records = MemoDb.encode(memos) }
        val decoded = MemoDb.decode(db)
        assertEquals(2, decoded.size)
        assertEquals(0x123456, decoded[0].uniqueId)
        assertEquals(2, decoded[0].category)
        assertEquals("Buy milk\nand eggs", decoded[0].text)
        assertEquals("Plain ASCII note", decoded[1].text)
    }

    @Test fun todo_roundTrip() {
        val todos = listOf(
            Todo(
                uniqueId = 0xABCDEF, category = 1,
                dueDate = PalmDate(2026, 7, 15), priority = 3, completed = true,
                description = "Ship release", notes = "remember signing",
            ),
            Todo(uniqueId = 0x10, category = 0, dueDate = null, priority = 1, description = "No due date"),
        )
        val db = PalmDb().apply { records = ToDoDb.encode(todos) }
        val decoded = ToDoDb.decode(db)
        assertEquals(2, decoded.size)
        val a = decoded[0]
        assertEquals(0xABCDEF, a.uniqueId)
        assertEquals(1, a.category)
        assertEquals(PalmDate(2026, 7, 15), a.dueDate)
        assertEquals(3, a.priority)
        assertTrue(a.completed)
        assertEquals("Ship release", a.description)
        assertEquals("remember signing", a.notes)
        assertNull(decoded[1].dueDate)
        assertEquals("No due date", decoded[1].description)
    }

    @Test fun memoAppInfo_roundTrip_andEnsureCategory() {
        val ai = MemoAppInfo.default()
        val aiIdx = ai.ensureCategory("AI")
        assertTrue(aiIdx in 1..14)
        assertEquals("AI", ai.categoryName(aiIdx))
        val parsed = MemoAppInfo.parse(ai.encode())!!
        assertEquals("AI", parsed.categoryName(aiIdx))
        assertEquals("Personal", parsed.categoryName(1))
    }

    @Test fun datebook_roundTrip_timedAndUntimed() {
        val appts = listOf(
            Appointment(
                uniqueId = 0x001234, year = 2026, month = 6, day = 23,
                startHour = 14, startMin = 30, endHour = 15, endMin = 0,
                description = "Dentist", note = "bring card",
                hasAlarm = true, alarmAdvance = 10, alarmUnit = 0,
            ),
            Appointment(uniqueId = 0x05, untimed = true, year = 2026, month = 12, day = 25, description = "Holiday"),
        )
        val db = DatebookDb.newDb(ByteArray(0)).apply { records = DatebookDb.encode(appts) }
        val decoded = DatebookDb.decode(db)
        assertEquals(2, decoded.size)
        val a = decoded[0]
        assertEquals(0x001234, a.uniqueId)
        assertEquals(2026, a.year); assertEquals(6, a.month); assertEquals(23, a.day)
        assertEquals(14, a.startHour); assertEquals(30, a.startMin)
        assertEquals(15, a.endHour); assertEquals(0, a.endMin)
        assertEquals("Dentist", a.description); assertEquals("bring card", a.note)
        assertTrue(a.hasAlarm); assertEquals(10, a.alarmAdvance)
        assertTrue(decoded[1].untimed)
        assertEquals("Holiday", decoded[1].description)
    }

    @Test fun address_roundTrip_withPhones() {
        val c = Contact(
            uniqueId = 0x00ABCD, last = "Wong", first = "Tai", company = "TAT",
            title = "Boss", address = "1 Main St", city = "HK", note = "vip",
            phones = mutableListOf(AddressPhone("Work", "12345678"), AddressPhone("E-mail", "a@b.com")),
        )
        val db = AddressDb.newDb(AddressDb.defaultAppInfo()).apply { records = AddressDb.encode(listOf(c)) }
        val decoded = AddressDb.decode(db)
        assertEquals(1, decoded.size)
        val d = decoded[0]
        assertEquals(0x00ABCD, d.uniqueId)
        assertEquals("Wong", d.last); assertEquals("Tai", d.first)
        assertEquals("TAT", d.company); assertEquals("Boss", d.title)
        assertEquals("Tai Wong", d.displayName())
        assertEquals(2, d.phones.size)
        assertEquals("Work", d.phones[0].label); assertEquals("12345678", d.phones[0].value)
        assertEquals("E-mail", d.phones[1].label); assertEquals("a@b.com", d.phones[1].value)
        assertEquals(AddressDb.defaultAppInfo().size, 638)
    }

    @Test fun mail_roundTrip() {
        val mails = listOf(
            Mail(uniqueId = 0x07, category = 0, year = 2026, month = 6, day = 23, hour = 8, min = 15,
                subject = "Morning digest", from = "AI", body = "3 things today"),
        )
        val db = MailDb.newDb(ByteArray(0)).apply { records = MailDb.encode(mails) }
        val decoded = MailDb.decode(db)
        assertEquals(1, decoded.size)
        val m = decoded[0]
        assertEquals(0x07, m.uniqueId)
        assertEquals("Morning digest", m.subject)
        assertEquals("AI", m.from)
        assertEquals("3 things today", m.body)
        assertEquals(2026, m.year); assertEquals(8, m.hour); assertEquals(15, m.min)
    }

    /** A loopback transport: bytes written are read straight back. */
    private class LoopbackTransport : PalmTransport {
        private val buf = ArrayDeque<Byte>()
        override fun read(b: ByteArray, timeoutMs: Int): Int {
            if (buf.isEmpty()) return 0
            var n = 0
            while (n < b.size && buf.isNotEmpty()) { b[n++] = buf.removeFirst() }
            return n
        }
        override fun write(data: ByteArray) { data.forEach { buf.addLast(it) } }
        override fun close() {}
    }

    @Test fun slp_frameRoundTrip() {
        val framer = SlpFramer(LoopbackTransport())
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x7F, 0xEF.toByte(), 0x00)
        framer.send(SLP_SOCKET_DLP, SLP_SOCKET_DLP, SLP_TYPE_PADP, 0x11, payload)
        val pkt = framer.receive(System.currentTimeMillis() + 5000)!!
        assertEquals(SLP_SOCKET_DLP, pkt.dest)
        assertEquals(SLP_SOCKET_DLP, pkt.src)
        assertEquals(SLP_TYPE_PADP, pkt.type)
        assertEquals(0x11, pkt.txid)
        assertArrayEquals(payload, pkt.data)
    }

    @Test fun slp_crc16_knownVector() {
        // CRC-16/XMODEM of the ASCII string "123456789" is 0x31C3.
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x31C3, SlpFramer.crc16(data, 0, data.size))
    }
}
