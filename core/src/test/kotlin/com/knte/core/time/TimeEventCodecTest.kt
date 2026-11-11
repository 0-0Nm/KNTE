package com.knte.core.time

import com.knte.core.time.format.TimeEventCodec
import com.knte.core.time.format.TimeJson
import com.knte.core.time.format.TimeJsonValues
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Strict JSONL event format tests: unknown keys pass through, unsupported values fail closed. */
class TimeEventCodecTest {

    private fun line(json: String): String = TimeEventCodec.parse(json).encode()

    @Test fun canonicalEventRoundTrip() {
        val raw = """{"op":"start","event_id":"e-0001","id":"t-a1","date":"2026-10-23","start":"09:00","project":"晨读","tags":["语文"],"note":"n","repeat_id":"r-001","ts":"2026-10-23T09:00:01"}"""
        assertEquals(raw, line(raw))
    }

    @Test fun optionalKeysAreOmittedNotNulled() {
        val raw = """{"op":"stop","event_id":"e-0002","id":"t-a1","end":"10:30","ts":"2026-10-23T10:30:02"}"""
        val event = TimeEventCodec.parse(raw)
        assertNull(event.field("end_date"))
        assertFalse(event.encode().contains("null"))
    }

    @Test fun unknownKeysArePreservedVerbatim() {
        val raw = """{"op":"start","event_id":"e-0010","id":"t-a4","date":"2026-10-24","start":"10:00","project":"物理","future":{"a":[1,2,true,null],"b":"x"},"ts":"2026-10-24T10:00:00"}"""
        val event = TimeEventCodec.parse(raw)
        val nested = event.unknownKeys.first { it.first == "future" }.second as TimeJson.Value.Obj
        val inner = nested.entries.first { it.first == "a" }.second as TimeJson.Value.Arr
        assertEquals(4, inner.items.size)
        assertEquals("x", TimeJsonValues.str(nested.entries.first { it.first == "b" }.second))
        assertEquals(raw, event.encode())
    }

    @Test fun derivedStateCanNeverBePersisted() {
        for (key in listOf("status", "duration_min", "duration_sec", "duration")) {
            val raw = """{"op":"stop","event_id":"e-1","id":"t-a1","end":"10:30","$key":"done","ts":"2026-10-23T10:30:02"}"""
            rejects(raw)
        }
    }

    @Test fun unknownOpAndMissingRequiredKeysFailClosed() {
        rejects("""{"op":"delete","event_id":"e-1","id":"t-a1","ts":"2026-10-23T10:30:02"}""")
        rejects("""{"op":"start","event_id":"e-1","date":"2026-10-23","start":"09:00","project":"x","ts":"2026-10-23T09:00:00"}""")
        rejects("""{"op":"stop","event_id":"e-1","id":"t-a1","ts":"2026-10-23T10:30:02"}""")
        rejects("""{"op":"amend","event_id":"e-1","target":"t-a1","field":"start","ts":"2026-10-23T10:30:02"}""")
        rejects("""{"op":"amend","event_id":"e-1","target":"t-a1","field":"bogus","value":"09:00","ts":"2026-10-23T10:30:02"}""")
    }

    @Test fun malformedDatesTimesAndIdsFailClosed() {
        val bases = listOf(
            """{"op":"start","event_id":"e-1","id":"bad id","date":"2026-10-23","start":"09:00","project":"x","ts":"2026-10-23T09:00:00"}""",
            """{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-02-30","start":"09:00","project":"x","ts":"2026-10-23T09:00:00"}""",
            """{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"9:00","project":"x","ts":"2026-10-23T09:00:00"}""",
            """{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"25:00","project":"x","ts":"2026-10-23T09:00:00"}""",
            """{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"09:00","project":"x","ts":"2026-10-23T09:00"}""",
            """{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"09:00","project":"x","tags":"语文","ts":"2026-10-23T09:00:00"}""",
            """{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"09:00","project":"x","at_trust":"verified","ts":"2026-10-23T09:00:00"}""",
            """{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"09:00","project":" ","ts":"2026-10-23T09:00:00"}"""
        )
        bases.forEach { rejects(it) }
    }

    @Test fun duplicateKeysAndTruncatedJsonFailClosed() {
        rejects("""{"op":"start","op":"stop","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"09:00","project":"x","ts":"2026-10-23T09:00:00"}""")
        rejects("""{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"09:00","project":"x","ts":""")
        rejects("""{"op":"start","event_id":"e-1","id":"t-a1","date":"2026-10-23","start":"09:00","project":"x","ts":"2026-10-23T09:00:00"} trailing""")
        rejects("not json")
    }

    @Test fun amendNullClearsOptionalFieldsAndEncodesAsNull() {
        val event = TimeEventCodec.parse("""{"op":"amend","event_id":"e-1","target":"t-a1","field":"note","value":null,"reason":"清空","ts":"2026-10-23T10:31:00"}""")
        assertTrue(event.field("value") is TimeJson.Value.Null)
        assertTrue(event.encode().contains("\"value\":null"))
    }

    @Test fun goldenFileLinesAllParseAndStayAppendOnly() {
        val text = File(javaClass.classLoader!!.getResource("time/golden-events.jsonl")!!.file)
            .readText()
        val lines = text.trimEnd('\n').split('\n')
        assertEquals(10, lines.size)
        val events = lines.map { TimeEventCodec.parse(it) }
        assertEquals(10, events.size)
        assertEquals(listOf("start", "stop", "amend", "start", "stop", "start", "amend", "stop", "rename_project", "start"), events.map { it.op })
        // Re-encoding a historical line must be byte-identical: no rewriting, no normalising away.
        lines.forEachIndexed { i, l -> assertEquals(l, events[i].encode()) }
    }

    private fun rejects(raw: String) {
        try { TimeEventCodec.parse(raw); fail("expected rejection: $raw") } catch (e: Exception) { }
    }
}
