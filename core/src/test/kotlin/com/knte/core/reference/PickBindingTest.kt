package com.knte.core.reference

import com.knte.core.time.format.TimeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickBindingTest {
    private val all = ReferenceScope.all()
    private val ts = "2026-11-11T21:03:11"

    @Test
    fun eventIdDerivedFromTurnIdentityLikeLegacy() {
        val session = "s-" + "1".repeat(32)
        val turn = "t-" + "2".repeat(32)
        val binding = TurnBinding(session, turn, "f".repeat(64))
        val expected = "r-" + sha256Hex((session + "\u0000" + turn).toByteArray(Charsets.UTF_8)).substring(0, 8)
        assertEquals(expected, binding.eventId)
        assertEquals(binding.eventId, binding.copy().eventId)
        assertEquals(binding, binding.copy())
    }

    @Test
    fun bindingRecordedInsidePickEvent() {
        val binding = validBinding()
        val pick = ReferencePick.pickTurn(fourMemos(), all, TEST_TODAY, ts, ScriptedRandom(longArrayOf(0)), binding = binding)!!
        assertEquals(binding.eventId, pick.eventId)
        val line = PickEventRecord.of(pick).encodeLine()
        assertTrue(line.contains("\"chat_session\":\"s-" + "1".repeat(32) + "\""))
        assertTrue(line.contains("\"chat_turn\":\"t-" + "2".repeat(32) + "\""))
        assertTrue(line.contains("\"response_sha256\":\"" + "a".repeat(64) + "\""))
        val parsed = PickEventRecord.parse(TimeJson.parse(line))
        assertEquals(binding, parsed.binding)
    }

    @Test
    fun changedInputInvalidatesBinding() {
        val binding = validBinding()
        val pick = ReferencePick.pickTurn(fourMemos(), all, TEST_TODAY, ts, ScriptedRandom(longArrayOf(0)), binding = binding)!!
        val record = PickEventRecord.of(pick)
        assertEquals(BindingStatus.OK, ReferencePick.verifyBinding(record, binding))
        // 输入 hash 变化 → 旧引用卡失效（回答版本变化，不能沿用旧引用卡）
        val editedInput = validBinding("b".repeat(64))
        assertEquals(BindingStatus.INPUT_CHANGED, ReferencePick.verifyBinding(record, editedInput))
        val otherTurn = TurnBinding("s-" + "1".repeat(32), "t-" + "9".repeat(32), "a".repeat(64))
        assertEquals(BindingStatus.TURN_CHANGED, ReferencePick.verifyBinding(record, otherTurn))
        val unbound = PickEventRecord.of(
            ReferencePick.pickTurn(fourMemos(), all, TEST_TODAY, ts, ScriptedRandom(longArrayOf(0)), eventId = "r-1a2b3c4d")!!,
        )
        assertEquals(BindingStatus.NONE_RECORDED, ReferencePick.verifyBinding(unbound, binding))
        // 整轮验证同样拒绝失效输入
        val outcome = ReferencePick.verifyTurn(fourMemos(), record, editedInput)
        assertFalse(outcome.ok)
        assertEquals(BindingStatus.INPUT_CHANGED, outcome.binding)
        // 原绑定仍然通过
        assertTrue(ReferencePick.verifyTurn(fourMemos(), record, binding).ok)
    }

    @Test
    fun malformedBindingsRefused() {
        fails { TurnBinding("s-xyz", "t-" + "2".repeat(32), "a".repeat(64)) }
        fails { TurnBinding("s-" + "1".repeat(32), "t-short", "a".repeat(64)) }
        fails { TurnBinding("s-" + "1".repeat(32), "t-" + "2".repeat(32), "A".repeat(64)) }
        fails { TurnBinding("s-" + "1".repeat(31), "t-" + "2".repeat(32), "a".repeat(64)) }
        fails { TurnBinding("s-" + "1".repeat(32), "t-" + "2".repeat(32), "a".repeat(63)) }
    }
}
