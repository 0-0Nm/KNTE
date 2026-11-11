package com.knte.preview.ui.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM JUnit4 contract tests for the product navigation model. No device / Compose. */
class ProductRouteTest {

    private fun expectInvalid(block: () -> ProductRoute) {
        var threw = false
        try {
            block()
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(
                "require message should identify the invalid combination",
                e.message?.contains("非法产品路由组合") == true,
            )
        }
        assertTrue("expected IllegalArgumentException from init require", threw)
    }

    @Test
    fun enumLabelsMatchDeclaredChineseApi() {
        assertEquals("静室", Module.QUIET.label)
        assertEquals("记念", Module.MEMORY.label)
        assertEquals("共读", Module.READING.label)
        assertEquals("知温", Module.ZHIWEN.label)

        assertEquals("日记", MemoryCategory.DIARY.label)
        assertEquals("时记", MemoryCategory.TIME.label)
        assertEquals("记账", MemoryCategory.ACCOUNTING.label)

        assertEquals(19, Section.values().size)
        assertEquals("日记", Section.DIARY.label)
        assertEquals("吃一堑", Section.LESSONS.label)
        assertEquals("心田", Section.HEART.label)
        assertEquals("师说", Section.TEACHINGS.label)
        assertEquals("珠玑", Section.MEMORY_GEMS.label)
        assertEquals("时记", Section.TIME.label)
        assertEquals("天行", Section.SKY.label)
        assertEquals("日程表", Section.SCHEDULE.label)
        assertEquals("备忘", Section.REMINDERS.label)
        assertEquals("习惯", Section.HABITS.label)
        assertEquals("四维图", Section.DIMENSIONS.label)
        assertEquals("记账", Section.ACCOUNTING.label)
        assertEquals("报表", Section.REPORTS.label)
        assertEquals("收支统计", Section.STATISTICS.label)
        assertEquals("可问", Section.ASK.label)
        assertEquals("观物", Section.OBSERVE.label)
        assertEquals("师长", Section.TEACHERS.label)
        assertEquals("札记", Section.READING_NOTES.label)
        assertEquals("珠玑", Section.READING_GEMS.label)
    }

    @Test
    fun leavesAreExactly21WithDeclaredScopeCounts() {
        val leaves = ProductRoute.leaves
        assertEquals(21, leaves.size)
        assertEquals(21, leaves.toSet().size)
        assertEquals(
            5,
            leaves.count { it.module == Module.MEMORY && it.category == MemoryCategory.DIARY },
        )
        assertEquals(
            6,
            leaves.count { it.module == Module.MEMORY && it.category == MemoryCategory.TIME },
        )
        assertEquals(
            3,
            leaves.count { it.module == Module.MEMORY && it.category == MemoryCategory.ACCOUNTING },
        )
        assertEquals(5, leaves.count { it.module == Module.READING })
        assertEquals(1, leaves.count { it.module == Module.QUIET })
        assertEquals(1, leaves.count { it.module == Module.ZHIWEN })
        // Every leaf survived init validation and is reachable from its own scope list.
        leaves.forEach { leaf ->
            val leafSection = leaf.section
            if (leafSection != null) {
                assertTrue(
                    "missing section for $leaf",
                    ProductRoute.sections(leaf.module, leaf.category).contains(leafSection),
                )
            }
        }
    }

    @Test
    fun sectionsExposeDeclaredOrderedScopes() {
        assertEquals(
            listOf(
                Section.DIARY, Section.LESSONS, Section.HEART,
                Section.TEACHINGS, Section.MEMORY_GEMS,
            ),
            ProductRoute.sections(Module.MEMORY, MemoryCategory.DIARY),
        )
        assertEquals(
            listOf(
                Section.TIME, Section.SKY, Section.SCHEDULE,
                Section.REMINDERS, Section.HABITS, Section.DIMENSIONS,
            ),
            ProductRoute.sections(Module.MEMORY, MemoryCategory.TIME),
        )
        assertEquals(
            listOf(Section.ACCOUNTING, Section.REPORTS, Section.STATISTICS),
            ProductRoute.sections(Module.MEMORY, MemoryCategory.ACCOUNTING),
        )
        assertEquals(
            listOf(
                Section.ASK, Section.OBSERVE, Section.TEACHERS,
                Section.READING_NOTES, Section.READING_GEMS,
            ),
            ProductRoute.sections(Module.READING, null),
        )
        assertTrue(ProductRoute.sections(Module.MEMORY, null).isEmpty())
        assertTrue(ProductRoute.sections(Module.READING, MemoryCategory.DIARY).isEmpty())
        assertTrue(ProductRoute.sections(Module.QUIET, null).isEmpty())
        assertTrue(ProductRoute.sections(Module.ZHIWEN, null).isEmpty())
    }

    @Test
    fun initialAndDefaultRouteAgree() {
        assertEquals(ProductRoute(), ProductRoute.initial(Module.MEMORY))
        assertEquals(
            ProductRoute(Module.MEMORY, MemoryCategory.DIARY, Section.DIARY),
            ProductRoute.initial(Module.MEMORY),
        )
        assertEquals(
            ProductRoute(Module.READING, null, Section.ASK),
            ProductRoute.initial(Module.READING),
        )
        assertEquals(
            ProductRoute(Module.QUIET, null, null),
            ProductRoute.initial(Module.QUIET),
        )
        assertEquals(
            ProductRoute(Module.ZHIWEN, null, null),
            ProductRoute.initial(Module.ZHIWEN),
        )
    }

    @Test
    fun validCombinationsAreExactlyThe21Leaves() {
        val categories: List<MemoryCategory?> = buildList {
            add(null)
            MemoryCategory.values().forEach { add(it) }
        }
        val sections: List<Section?> = buildList {
            add(null)
            Section.values().forEach { add(it) }
        }
        val valid = mutableSetOf<ProductRoute>()
        Module.values().forEach { module ->
            categories.forEach { category ->
                sections.forEach { section ->
                    val candidate = try {
                        ProductRoute(module, category, section)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (candidate != null) valid += candidate
                }
            }
        }
        assertEquals(21, valid.size)
        assertEquals(ProductRoute.leaves.toSet(), valid)
    }

    @Test
    fun invalidCombinationsAreRejectedByInit() {
        val invalid = listOf<() -> ProductRoute>(
            { ProductRoute(Module.MEMORY, null, Section.DIARY) },
            { ProductRoute(Module.MEMORY, MemoryCategory.DIARY, null) },
            { ProductRoute(Module.MEMORY, null, null) },
            { ProductRoute(Module.MEMORY, MemoryCategory.DIARY, Section.TIME) },
            { ProductRoute(Module.MEMORY, MemoryCategory.TIME, Section.DIARY) },
            { ProductRoute(Module.MEMORY, MemoryCategory.ACCOUNTING, Section.MEMORY_GEMS) },
            { ProductRoute(Module.MEMORY, MemoryCategory.DIARY, Section.ASK) },
            { ProductRoute(Module.READING, MemoryCategory.DIARY, Section.ASK) },
            { ProductRoute(Module.READING, null, null) },
            { ProductRoute(Module.READING, null, Section.DIARY) },
            { ProductRoute(Module.READING, null, Section.MEMORY_GEMS) },
            { ProductRoute(Module.QUIET, null, Section.DIARY) },
            { ProductRoute(Module.QUIET, MemoryCategory.DIARY, null) },
            { ProductRoute(Module.QUIET, MemoryCategory.TIME, Section.TIME) },
            { ProductRoute(Module.ZHIWEN, null, Section.ASK) },
            { ProductRoute(Module.ZHIWEN, MemoryCategory.TIME, null) },
        )
        invalid.forEach { expectInvalid(it) }
        // JUnit4.13.2 assertThrows agrees on a representative case.
        assertThrows(IllegalArgumentException::class.java) {
            ProductRoute(Module.MEMORY, MemoryCategory.DIARY, Section.REPORTS)
        }
    }

    @Test
    fun worldAndChapterScopeFlags() {
        val teacher = ProductRoute(Module.READING, null, Section.TEACHERS)
        assertFalse("师长是全局入口，不显示世界行", teacher.showWorld)
        assertFalse(teacher.showChapter)

        val ask = ProductRoute(Module.READING, null, Section.ASK)
        assertTrue("可问显示章节行", ask.showChapter)
        assertTrue(ask.showWorld)

        ProductRoute.sections(Module.READING, null)
            .filter { it != Section.TEACHERS && it != Section.ASK }
            .forEach { section ->
                val route = ProductRoute(Module.READING, null, section)
                assertTrue(route.showWorld)
                assertFalse(route.showChapter)
            }

        ProductRoute.leaves.filter { it.module != Module.READING }.forEach { route ->
            assertFalse(route.showWorld)
            assertFalse(route.showChapter)
        }
    }

    @Test
    fun isDiaryMatchesOnlyExactDiaryLeaf() {
        assertTrue(ProductRoute().isDiary)
        assertTrue(ProductRoute.initial(Module.MEMORY).isDiary)
        assertFalse(ProductRoute(Module.MEMORY, MemoryCategory.DIARY, Section.LESSONS).isDiary)
        assertFalse(ProductRoute(Module.MEMORY, MemoryCategory.DIARY, Section.MEMORY_GEMS).isDiary)
        assertFalse(ProductRoute(Module.MEMORY, MemoryCategory.TIME, Section.TIME).isDiary)
        assertFalse(ProductRoute(Module.READING, null, Section.ASK).isDiary)
        assertFalse(ProductRoute.initial(Module.QUIET).isDiary)
        assertFalse(ProductRoute.initial(Module.ZHIWEN).isDiary)
        assertEquals(1, ProductRoute.leaves.count { it.isDiary })
    }

    @Test
    fun breadcrumbFollowsScopeLabels() {
        assertEquals("记念 / 日记 / 日记", ProductRoute().breadcrumb)
        assertEquals(
            "记念 / 时记 / 天行",
            ProductRoute(Module.MEMORY, MemoryCategory.TIME, Section.SKY).breadcrumb,
        )
        assertEquals("共读 / 可问", ProductRoute.initial(Module.READING).breadcrumb)
        assertEquals("静室", ProductRoute.initial(Module.QUIET).breadcrumb)
        assertEquals("知温", ProductRoute.initial(Module.ZHIWEN).breadcrumb)
        ProductRoute.leaves.forEach { route ->
            assertTrue(route.breadcrumb.contains(route.module.label))
            route.category?.let { assertTrue(route.breadcrumb.contains(it.label)) }
            route.section?.let { assertTrue(route.breadcrumb.contains(it.label)) }
        }
    }

    @Test
    fun dependencyIsHonestAndDocumentsSharedGemsSourceByScope() {
        ProductRoute.leaves.forEach { route ->
            assertTrue(route.dependency.contains("尚未连接"))
            assertTrue(route.dependency.contains("不是空数据页"))
            assertTrue(route.dependency.contains("没有读取真实 Vault"))
        }
        val memoryGems = ProductRoute(Module.MEMORY, MemoryCategory.DIARY, Section.MEMORY_GEMS)
        val readingGems = ProductRoute(Module.READING, null, Section.READING_GEMS)
        assertNotEquals(memoryGems, readingGems)
        assertEquals(Section.MEMORY_GEMS.label, Section.READING_GEMS.label)
        listOf(memoryGems, readingGems).forEach { route ->
            assertTrue(route.dependency.contains("同一真实珠玑源"))
            assertTrue(route.dependency.contains("作用域"))
            assertTrue(route.dependency.contains("不是两个重复仓库"))
            assertTrue(route.dependency.contains("不新建全局业务数据源"))
        }
        assertTrue(ProductRoute.initial(Module.QUIET).dependency.contains("静室"))
        assertTrue(
            ProductRoute.initial(Module.READING)
                .dependency.contains("不预填世界、书籍或人物"),
        )
    }

    @Test
    fun availabilitySeparatesNotConnectedFromEmpty() {
        val diary = ProductRoute()
        assertEquals(ProductAvailability.Available, availability(diary, true))

        val notConnected = availability(diary, false)
        assertTrue(notConnected is ProductAvailability.NotConnected)
        val reason = (notConnected as ProductAvailability.NotConnected).reason
        assertTrue(reason.isNotBlank())
        assertNotEquals("NotConnected 不能当成 Empty", ProductAvailability.Empty, notConnected)
        assertNotEquals(ProductAvailability.Available, ProductAvailability.Empty)
        assertNotEquals(ProductAvailability.Empty, ProductAvailability.Available)

        // No leaf, connected or not, ever yields Empty from this function.
        ProductRoute.leaves.forEach { route ->
            listOf(false, true).forEach { connected ->
                val state = availability(route, connected)
                assertNotEquals(ProductAvailability.Empty, state)
                if (route.isDiary && connected) {
                    assertEquals(ProductAvailability.Available, state)
                } else {
                    assertTrue(state is ProductAvailability.NotConnected)
                    assertTrue(
                        (state as ProductAvailability.NotConnected).reason.isNotBlank(),
                    )
                }
            }
        }
        val quiet = availability(ProductRoute.initial(Module.QUIET), true)
        assertTrue(quiet is ProductAvailability.NotConnected)
        assertTrue((quiet as ProductAvailability.NotConnected).reason.contains("静室"))
    }

    @Test
    fun overlayIsMutuallyExclusiveAndNavigateClosesIt() {
        val initial = ShellState()
        assertEquals(ProductRoute(), initial.route)
        assertEquals(Overlay.NONE, initial.overlay)

        Overlay.values().forEach { value ->
            assertEquals(value, ShellState().open(value).overlay)
        }

        val stacked = initial.open(Overlay.SECTIONS).open(Overlay.MODULES)
        assertEquals(Overlay.MODULES, stacked.overlay)
        assertNotEquals(Overlay.SECTIONS, stacked.overlay)
        assertEquals(Overlay.NONE, stacked.open(Overlay.NONE).overlay)

        val target = ProductRoute.initial(Module.READING)
        val navigated = stacked.navigate(target)
        assertEquals(target, navigated.route)
        assertEquals(Overlay.NONE, navigated.overlay)
        // Original state is unchanged (data class copy semantics).
        assertEquals(Overlay.MODULES, stacked.overlay)
        assertEquals(ProductRoute(), stacked.route)
    }

    @Test
    fun noShellRouteHasPrimarySendAction() {
        ProductRoute.leaves.forEach { route ->
            assertFalse(hasPrimarySendAction(route, false))
            assertFalse(hasPrimarySendAction(route, true))
        }
        assertFalse(hasPrimarySendAction(ProductRoute(), true))
        assertFalse(hasPrimarySendAction(ShellState().route, false))
    }
}
