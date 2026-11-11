package com.knte.core.reference

import com.knte.core.time.format.TimeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceScopeTest {

    @Test
    fun allScopeIncludesEverything() {
        val scope = ReferenceScope.all()
        assertTrue(scope.includes(memoEntry("m-00000001", world = "素问")))
        assertTrue(scope.includes(memoEntry("m-00000002", world = null)))
        assertTrue(scope.includes(pitfallEntry("p-00000001", world = "归墟")))
    }

    @Test
    fun worldScopeMatchesAliasesOnly() {
        val scope = ReferenceScope.world("素问", listOf("素问", "素问别名"))
        assertTrue(scope.includes(memoEntry("m-00000001", world = "素问")))
        assertTrue(scope.includes(pitfallEntry("p-00000001", world = "素问别名")))
        assertFalse(scope.includes(memoEntry("m-00000002", world = "归墟")))
        assertFalse(scope.includes(memoEntry("m-00000003", world = null)))
    }

    @Test
    fun unclassifiedScopeIncludesUnknownWorlds() {
        val scope = ReferenceScope.unclassified("未分类", listOf("素问"))
        assertTrue(scope.includes(memoEntry("m-00000001", world = null)))
        assertTrue(scope.includes(memoEntry("m-00000002", world = "归墟")))
        assertFalse(scope.includes(memoEntry("m-00000003", world = "素问")))
    }

    @Test
    fun drawFiltersByWorldSource() {
        val entries = listOf(
            memoEntry("m-00000001", due = TEST_TODAY.toString(), world = "素问"),
            memoEntry("m-00000002", due = TEST_TODAY.toString(), world = "归墟"),
            memoEntry("m-00000003", due = TEST_TODAY.toString(), world = null),
        )
        val worldScope = ReferenceScope.world("素问", listOf("素问"))
        assertEquals(
            listOf("m-00000001"),
            ReferenceSelect.filtered(entries, ReferencePool.MEMO, worldScope).map { it.id },
        )
        val choice = ReferenceSelect.draw(entries, ReferencePool.MEMO, worldScope, TEST_TODAY, emptyList(), ScriptedRandom(longArrayOf(0)))!!
        assertEquals("m-00000001", choice.entry.id)
        assertEquals(1, choice.snapshot.poolSize)
        val unclassified = ReferenceScope.unclassified("未分类", listOf("素问"))
        assertEquals(
            listOf("m-00000002", "m-00000003"),
            ReferenceSelect.filtered(entries, ReferencePool.MEMO, unclassified).map { it.id },
        )
    }

    @Test
    fun scopeRoundTripsAndToleratesUnknownKeys() {
        val scope = ReferenceScope.world("素问", listOf("素问", "别名"))
        assertEquals(scope, ReferenceScope.parse(scope.toJson()))
        val withExtra = TimeJson.parse("""{"kind":"world","label":"素问","names":["素问"],"future_key":[]}""")
        assertEquals(scope.kind, ReferenceScope.parse(withExtra).kind)
    }

    @Test
    fun invalidScopesRefused() {
        fails { ReferenceScope("galaxy", "未知类型", emptyList()) }
        fails { ReferenceScope("world", "", listOf("素问")) }
        fails { ReferenceScope("world", "素问", listOf("")) }
        fails { ReferenceScope("world", "素问", (1..10101).map { "w$it" }) }
        ReferenceScope("world", "素问", (1..10100).map { "w$it" }) // 上限可容纳
    }
}
