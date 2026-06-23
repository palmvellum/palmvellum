package dev.tatliving.palmvellum.organizers.data.card

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmInstallParserTest {

    @Test
    fun parsesPdbRecordDatabase() {
        val db = PalmDb(
            name = "TestMemoDB",
            attributes = 0x0008, // backup bit; resDB (0x0001) NOT set
            version = 1,
            type = "DATA".toByteArray(Charsets.US_ASCII),
            creator = "memo".toByteArray(Charsets.US_ASCII),
            records = mutableListOf(
                PalmRecord(uniqueId = 0x010203, attributes = 0x01, data = "first".toByteArray()),
                PalmRecord(uniqueId = 0x040506, attributes = 0x00, data = "second!".toByteArray()),
            ),
        )
        val bytes = PalmPdb.write(db)

        val f = PalmInstallParser.parse(bytes)
        assertFalse(f.isResource)
        assertEquals("TestMemoDB", f.name)
        assertEquals("DATA", f.typeString())
        assertEquals("memo", f.creatorString())
        assertEquals(2, f.entryCount)
        assertEquals(0x010203, f.pdb!!.records[0].uniqueId)
        assertArrayEquals("first".toByteArray(), f.pdb.records[0].data)
        assertArrayEquals("second!".toByteArray(), f.pdb.records[1].data)
    }

    @Test
    fun parsesPrcResourceDatabase() {
        // Hand-build a minimal .prc: header + one resource entry + its data.
        val name = "TestApp"
        val resData = "hello-resource".toByteArray()
        val headerLen = 78
        val entryLen = 10
        val dataOffset = headerLen + entryLen // 88
        val total = dataOffset + resData.size

        val b = ByteArray(total)
        name.toByteArray(Charsets.US_ASCII).copyInto(b, 0)
        // attributes (offset 32): resDB bit set.
        b[32] = 0x00; b[33] = 0x01
        // version (34) = 1
        b[34] = 0x00; b[35] = 0x01
        // type (60) "appl", creator (64) "test"
        "appl".toByteArray(Charsets.US_ASCII).copyInto(b, 60)
        "test".toByteArray(Charsets.US_ASCII).copyInto(b, 64)
        // numRecords (76) = 1
        b[76] = 0x00; b[77] = 0x01
        // resource entry @78: type[4]="code", id[2]=0x03E8(1000), offset[4]=88
        "code".toByteArray(Charsets.US_ASCII).copyInto(b, 78)
        b[82] = 0x03; b[83] = 0xE8.toByte()
        b[84] = 0x00; b[85] = 0x00; b[86] = 0x00; b[87] = dataOffset.toByte()
        resData.copyInto(b, dataOffset)

        val f = PalmInstallParser.parse(b)
        assertTrue(f.isResource)
        assertEquals("TestApp", f.name)
        assertEquals("appl", f.typeString())
        assertEquals(1, f.entryCount)
        val r = f.resources[0]
        assertEquals("code", String(r.type, Charsets.US_ASCII))
        assertEquals(1000, r.id)
        assertArrayEquals(resData, r.data)
    }
}
