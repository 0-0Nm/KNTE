package com.knte.preview.ui.product

/** Dependency-free executable checks; complements, does not impersonate, JUnit4 tests. */
object ProductRouteSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        var assertions = 0
        fun verify(condition: Boolean, label: String) {
            check(condition) { label }
            assertions++
        }
        verify(Module.values().map { it.label } == listOf("静室", "记念", "共读", "知温"), "module order")
        val leaves = ProductRoute.leaves
        verify(leaves.size == 21 && leaves.toSet().size == 21, "21 unique leaves")
        val counts = MemoryCategory.values().map { c -> leaves.count { it.category == c } }
        verify(counts == listOf(5, 6, 3), "memory 5/6/3")
        verify(leaves.count { it.module == Module.READING } == 5, "reading five")
        val valid = mutableSetOf<ProductRoute>()
        for (m in Module.values()) for (c in listOf(null) + MemoryCategory.values().toList()) {
            for (s in listOf(null) + Section.values().toList()) {
                runCatching { ProductRoute(m, c, s) }.getOrNull()?.let { valid.add(it) }
            }
        }
        verify(valid == leaves.toSet(), "all 320 combinations yield exactly declared leaves")
        for (m in Module.values()) verify(ProductRoute.initial(m) in leaves, "initial $m")
        val ask = ProductRoute.initial(Module.READING)
        val teacher = ask.copy(section = Section.TEACHERS)
        verify(ask.showWorld && ask.showChapter, "ask world/chapter")
        verify(!teacher.showWorld && !teacher.showChapter, "teacher no world/chapter")
        verify(!ShellState().navigate(teacher).route.showWorld, "context recomputes after navigation")
        verify(ShellState().navigate(ask).route.showChapter, "ask context restored after navigation")
        for (route in leaves) {
            verify(route.isDiary == (route == ProductRoute()), "precise diary $route")
            verify(route.dependency.isNotBlank(), "dependency $route")
            if (route.section != null) {
                verify(route.section in ProductRoute.sections(route.module, route.category), "valid leaf $route")
            }
            for (connected in listOf(false, true)) {
                val state = availability(route, connected)
                verify(state != ProductAvailability.Empty, "not connected is not empty")
                verify((state == ProductAvailability.Available) == (route.isDiary && connected), "availability")
                verify(!hasPrimarySendAction(route, connected), "no primary send")
            }
        }
        for (first in Overlay.values()) for (second in Overlay.values()) {
            val state = ShellState().open(first).open(second)
            verify(state.overlay == second, "overlay replaces, never stacks")
            verify(state.navigate(ask).overlay == Overlay.NONE, "navigation closes overlay")
        }
        val memoryGems = ProductRoute().copy(section = Section.MEMORY_GEMS)
        val readingGems = ask.copy(section = Section.READING_GEMS)
        verify(memoryGems != readingGems, "distinct view routes")
        verify(memoryGems.dependency.contains("同一真实珠玑源") &&
            readingGems.dependency.contains("同一真实珠玑源"), "shared real gems source contract")
        val observe = ProductRoute(Module.READING, null, Section.OBSERVE)
        val practice = ShellState(observe).selectObserve(ObserveMode.PRACTICE)
        verify(practice.route == observe && practice.route.showObserve, "observe stays local")
        verify(practice.observeMode == ObserveMode.PRACTICE, "practice context")
        verify(practice.navigate(teacher).navigate(observe).observeMode == ObserveMode.OBJECTS,
            "context reset after leaving observe")
        verify(!teacher.showObserve && !ask.showObserve, "no observe context on other reading pages")
        verify(availability(practice.route, true) is ProductAvailability.NotConnected, "no fake practice data")
        verify(overlayBackTarget(Overlay.SETTINGS, "data") == Overlay.SETTINGS, "detail back")
        verify(overlayBackTarget(Overlay.SETTINGS, null) == Overlay.NONE, "settings root back")
        for (origin in leaves.filter { it.module != Module.QUIET }) {
            val quiet = ShellState(origin).navigate(ProductRoute.initial(Module.QUIET))
            verify(quiet.navigate(origin).route == origin, "quiet restores exact origin")
        }
        println("PASS ProductRouteSmoke: $assertions assertions; 21 leaves; 320 combinations; no Compose/runtime claims")
    }
}
