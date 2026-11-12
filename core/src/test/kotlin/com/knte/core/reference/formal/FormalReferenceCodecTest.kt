package com.knte.core.reference.formal

import com.knte.core.time.format.TimeJson
import org.junit.Assert.*
import org.junit.Test

/** Synthetic candidate-codec tests; no production schema or persistence acceptance. */
class FormalReferenceCodecTest {
    private val ts = "2026-11-13T03:00:00"
    private fun memo(forward: List<Pair<String, TimeJson.Value>> = emptyList()) =
        FormalMemoLine("m-00000001", "sample", null, false, false, emptyList(), null, ts, ts, forward)
    private fun reject(block: () -> Unit) {
        try { block();fail("expected candidate validation failure") }
        catch (_: FormalReferenceException) { }
    }
    @Test fun canonicalMemoMatchesGoldenShapeAndOmitsAbsentOptionals() {
        val bytes=FormalReferenceCodec.encodeLine(memo())
        val expected="""{"op":"upsert","id":"m-00000001","title":"sample","done":false,"archived":false,"tags":[],"created_ts":"2026-11-13T03:00:00","ts":"2026-11-13T03:00:00","at_trust":"unverified"}"""+"\n"
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8),bytes)
        assertArrayEquals(bytes,FormalReferenceCodec.canonical("memo",bytes))
    }
    @Test fun malformedNumericLiteralCannotInjectJson() {
        for(n in listOf("0}","NaN","Infinity","1e999","01","1,2"))
            reject { FormalReferenceCodec.encodeLine(memo(listOf("future" to TimeJson.Value.Num(n)))) }
    }
    @Test fun nestedDuplicateKeysAreRejectedBeforeEncoding() {
        val duplicate=TimeJson.Value.Obj(listOf("x" to TimeJson.Value.Num("1"),"x" to TimeJson.Value.Num("2")))
        reject { memo(listOf("future" to duplicate)) }
    }
    @Test fun excessiveNestedDepthIsRejected() {
        var value:TimeJson.Value=TimeJson.Value.Str("x")
        repeat(18) { value=TimeJson.Value.Arr(listOf(value)) }
        reject { memo(listOf("future" to value)) }
    }
    @Test fun unknownSeedIsNeverSilentlyLostDuringRoundtrip() {
        for(value in listOf(TimeJson.Value.Null,TimeJson.Value.Num("123"))) {
            val bytes=FormalReferenceCodec.encodeLine(memo(listOf("seed" to value)))
            val parsed=FormalReferenceCodec.decodeLine("memo",bytes).toJson() as TimeJson.Value.Obj
            assertEquals(value,parsed.entries.first{it.first=="seed"}.second)
            assertArrayEquals(bytes,FormalReferenceCodec.canonical("memo",bytes))
        }
    }
    @Test fun malformedBytesAndMultipleLinesAreRejected() {
        val good=FormalReferenceCodec.encodeLine(memo())
        reject { FormalReferenceCodec.decodeLine("memo",byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+good) }
        reject { FormalReferenceCodec.decodeLine("memo",good+good) }
        reject { FormalReferenceCodec.decodeLine("memo",byteArrayOf(0xc3.toByte(),0x28,0x0a)) }
    }
    @Test fun callerMutationAfterConstructionStillCannotInjectInvalidForward() {
        val future=mutableListOf<Pair<String,TimeJson.Value>>()
        val line=memo(future)
        future.add("future" to TimeJson.Value.Num("0}"))
        reject { FormalReferenceCodec.encodeLine(line) }
    }
    @Test fun escapedNewlineAndValidUnknownTreeRoundtripWithoutRawNewlines() {
        val value=TimeJson.Value.Obj(listOf("v" to TimeJson.Value.Str("a\nb"),"n" to TimeJson.Value.Num("1.25")))
        val bytes=FormalReferenceCodec.encodeLine(memo(listOf("future" to value)))
        assertEquals(1,bytes.count{it==10.toByte()})
        assertArrayEquals(bytes,FormalReferenceCodec.canonical("memo",bytes))
    }
    @Test fun contextTicketScriptCannotBeMutatedThroughPublicProperty() {
        val input=mutableListOf(0L,1L)
        val c=FormalReferenceContext.exact(java.time.LocalDate.of(2026,11,13),input)
        input[0]=999L
        assertEquals(listOf(0L,1L),c.tickets)
        try { (c.tickets as MutableList<Long>)[0]=999L;fail("immutable context") }
        catch(_:UnsupportedOperationException) { }
    }

}
