package com.knte.preview.ui.product

/**
 * Pure-Kotlin product navigation model for the preview shell.
 *
 * This file declares routes, view scopes and availability states only. It does not open a
 * repository, read the real Vault, create a business data source, hold world/book/person
 * values, or perform save/send/record operations. The parent supplies any real content.
 *
 * Two distinct 珠玑 leaves — [Section.MEMORY_GEMS] (记念) and [Section.READING_GEMS] (共读) —
 * are VIEW ROUTES over one real 珠玑 source, queried by route scope. They are not two
 * repositories: this shell creates no global or duplicate business source.
 */
enum class Module(val label: String) {
    QUIET("静室"),
    MEMORY("记念"),
    READING("共读"),
    ZHIWEN("知温"),
}

enum class MemoryCategory(val label: String) {
    DIARY("日记"),
    TIME("时记"),
    ACCOUNTING("记账"),
}

enum class Section(val label: String) {
    DIARY("日记"),
    LESSONS("吃一堑"),
    HEART("心田"),
    TEACHINGS("师说"),
    /** 记念作用域的同一真实珠玑源视图，不是独立仓库。 */
    MEMORY_GEMS("珠玑"),
    TIME("时记"),
    SKY("天行"),
    SCHEDULE("日程表"),
    REMINDERS("备忘"),
    HABITS("习惯"),
    DIMENSIONS("四维图"),
    ACCOUNTING("记账"),
    REPORTS("报表"),
    STATISTICS("收支统计"),
    ASK("可问"),
    OBSERVE("观物"),
    TEACHERS("师长"),
    READING_NOTES("札记"),
    /** 共读作用域的同一真实珠玑源视图，不是独立仓库。 */
    READING_GEMS("珠玑"),
}

/**
 * One navigation leaf. [category] and [section] are nullable only for the two module-only
 * leaves (静室 / 知温); every 记念 leaf has both, every 共读 leaf has a section and no category.
 */
data class ProductRoute(
    val module: Module = Module.MEMORY,
    val category: MemoryCategory? = MemoryCategory.DIARY,
    val section: Section? = Section.DIARY,
) {
    init {
        // Inlined so validation never depends on companion-object private visibility rules.
        val valid = when (module) {
            Module.MEMORY -> {
                val c = category
                val s = section
                c != null && s != null && s in sections(module, c)
            }
            Module.READING -> {
                val c = category
                val s = section
                c == null && s != null && s in sections(module, null)
            }
            Module.QUIET, Module.ZHIWEN -> category == null && section == null
        }
        require(valid) {
            "非法产品路由组合: module=$module, category=$category, section=$section"
        }
    }

    /** 共读显示世界行；师长是全局入口，不显示世界行。 */
    val showWorld: Boolean
        get() = module == Module.READING && section != null && section != Section.TEACHERS

    /** 共读只有「可问」显示章节行。 */
    val showChapter: Boolean
        get() = module == Module.READING && section == Section.ASK

    /** 精确的「记念 / 日记 / 日记」叶子；同分类下其他分区不是正式日记入口。 */
    val isDiary: Boolean
        get() = module == Module.MEMORY &&
            category == MemoryCategory.DIARY &&
            section == Section.DIARY

    val breadcrumb: String
        get() = buildList {
            add(module.label)
            category?.let { add(it.label) }
            section?.let { add(it.label) }
        }.joinToString(" / ")

    /** Honest, scope-aware dependency text; never claims a connected or empty data set. */
    val dependency: String
        get() = buildString {
            append("尚未连接：")
            when (module) {
                Module.QUIET -> append(
                    "静室只展示暗域；保存与录音能力尚未连接，不接收输入，也不会声称内容已被保存。"
                )
                Module.ZHIWEN -> append(
                    "知温尚未连接模型、天气或外部服务；当前只有产品导航入口，没有生成或发送操作。"
                )
                Module.MEMORY -> when (category) {
                    MemoryCategory.DIARY -> append(
                        "正式日记尚未接入真实 Vault；不读取、迁移或合并现有日记，" +
                            "也不使用 App 私有 .note 预览工具代替。"
                    )
                    MemoryCategory.TIME -> append(
                        "时记、日程、提醒与习惯尚未连接真实时间或日程数据源；未连接不表示没有记录。"
                    )
                    MemoryCategory.ACCOUNTING -> append(
                        "记账尚未连接真实账本；不展示金额、报表数值或收支结论，也没有支付或发送能力。"
                    )
                    null -> append("记念路由缺少有效分类。")
                }
                Module.READING -> append(
                    "共读尚未连接真实世界目录、章节索引与问答服务；" +
                        "不预填世界、书籍或人物，没有可选项不等于目录为空。"
                )
            }
            append(" 此处是产品导航入口，不是空数据页；连接失败不能显示为零条记录，")
            append("也不能显示保存成功、AI、天气、录音或金额。")
            if (section == Section.MEMORY_GEMS || section == Section.READING_GEMS) {
                append(
                    " 记念/共读的珠玑是同一真实珠玑源按路由作用域查询的两个视图入口，" +
                        "不是两个重复仓库；本壳不新建全局业务数据源。"
                )
            }
            append(" 没有读取真实 Vault，没有生成、发送、录音或保存操作。")
        }

    companion object {
        /** First leaf for each module; module-only leaves carry null category/section. */
        fun initial(module: Module): ProductRoute = when (module) {
            Module.MEMORY -> ProductRoute(Module.MEMORY, MemoryCategory.DIARY, Section.DIARY)
            Module.READING -> ProductRoute(Module.READING, null, Section.ASK)
            Module.QUIET -> ProductRoute(Module.QUIET, null, null)
            Module.ZHIWEN -> ProductRoute(Module.ZHIWEN, null, null)
        }

        /**
         * All 21 navigation leaves: 记念/日记 5, 记念/时记 6, 记念/记账 3,
         * 共读 5, 静室 1, 知温 1. Derived from [sections] so counts cannot drift.
         */
        val leaves: List<ProductRoute> by lazy {
            buildList {
                MemoryCategory.values().forEach { category ->
                    sections(Module.MEMORY, category).forEach { section ->
                        add(ProductRoute(Module.MEMORY, category, section))
                    }
                }
                sections(Module.READING, null).forEach { section ->
                    add(ProductRoute(Module.READING, null, section))
                }
                add(initial(Module.QUIET))
                add(initial(Module.ZHIWEN))
            }
        }

        /** Sections available for a module/category scope; empty for module-only leaves. */
        fun sections(module: Module, category: MemoryCategory?): List<Section> = when (module) {
            Module.MEMORY -> when (category) {
                MemoryCategory.DIARY -> listOf(
                    Section.DIARY, Section.LESSONS, Section.HEART,
                    Section.TEACHINGS, Section.MEMORY_GEMS,
                )
                MemoryCategory.TIME -> listOf(
                    Section.TIME, Section.SKY, Section.SCHEDULE,
                    Section.REMINDERS, Section.HABITS, Section.DIMENSIONS,
                )
                MemoryCategory.ACCOUNTING -> listOf(
                    Section.ACCOUNTING, Section.REPORTS, Section.STATISTICS,
                )
                null -> emptyList()
            }
            Module.READING -> if (category == null) listOf(
                Section.ASK, Section.OBSERVE, Section.TEACHERS,
                Section.READING_NOTES, Section.READING_GEMS,
            ) else emptyList()
            Module.QUIET, Module.ZHIWEN -> emptyList()
        }
    }
}

/** Exactly one overlay at a time; [Overlay.NONE] means closed. */
enum class Overlay { NONE, MODULES, SECTIONS, WORLD, SETTINGS }

data class ShellState(
    val route: ProductRoute = ProductRoute(),
    val overlay: Overlay = Overlay.NONE,
) {
    /** Replace (never stack) the overlay, so values are mutually exclusive. */
    fun open(overlay: Overlay): ShellState = copy(overlay = overlay)

    /** Navigate to [route] and always close the overlay. */
    fun navigate(route: ProductRoute): ShellState =
        copy(route = route, overlay = Overlay.NONE)
}

/**
 * Connection state, deliberately distinct from an empty data set. [ProductAvailability.Empty]
 * is reserved for a real repository read-back supplied by the parent; [availability] never
 * invents it.
 */
sealed interface ProductAvailability {
    data class NotConnected(val reason: String) : ProductAvailability
    data object Empty : ProductAvailability
    data object Available : ProductAvailability
}

/**
 * Only the exact diary leaf with a parent-supplied isolated Markdown diary is Available.
 * Everything else in this shell is NotConnected with an explanatory reason; 静室 is an
 * intentional dark-room entry, not a failed repository connection.
 */
fun availability(route: ProductRoute, diaryConnected: Boolean): ProductAvailability =
    if (route.isDiary && diaryConnected) {
        ProductAvailability.Available
    } else {
        ProductAvailability.NotConnected(route.notConnectedReason(diaryConnected))
    }

private fun ProductRoute.notConnectedReason(diaryConnected: Boolean): String = buildString {
    when (module) {
        Module.QUIET -> append(
            "静室是独立的暗域入口，不连接日记或任何仓库；这里没有可读取的内容，也不是空数据页。"
        )
        Module.ZHIWEN -> append("知温尚未连接模型与天气服务，当前只有导航入口。")
        Module.MEMORY -> when (category) {
            MemoryCategory.DIARY ->
                if (isDiary && !diaryConnected) {
                    append(
                        "正式日记入口已定义，但父级未提供隔离 Markdown 日记内容；" +
                            "未接真实 Vault，不回退到 App 私有 .note 预览工具。"
                    )
                } else {
                    append(
                        "记念·日记的「${section?.label}」分区尚未连接对应真实数据源，" +
                            "当前仅展示导航与依赖边界。"
                    )
                }
            MemoryCategory.TIME -> append("记念·时记尚未连接真实时间或日程数据源。")
            MemoryCategory.ACCOUNTING -> append("记念·记账尚未连接真实账本。")
            null -> append("记念路由缺少分类。")
        }
        Module.READING -> append("共读尚未连接真实世界、章节与问答服务。")
    }
    append(" 未连接不等同于空数据；不展示零条记录或保存成功。")
}

/**
 * No shell route owns a primary send action in this preview slice, connected or not.
 * Kept as an explicit API so the parent does not infer one from availability.
 */
@Suppress("UNUSED_PARAMETER")
fun hasPrimarySendAction(route: ProductRoute, diaryConnected: Boolean): Boolean = false
