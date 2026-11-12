package com.knte.preview.ui.product

/** Navigation ownership is a UI scope, never a second business state source. */
enum class ContentOwner { PREVIEW_TOOL, DIARY, TIME, ACCOUNTING, HABIT, REFERENCE, NONE }
fun contentOwner(route: ProductRoute, tool: Boolean = false): ContentOwner = when {
    tool -> ContentOwner.PREVIEW_TOOL
    route.isDiary -> ContentOwner.DIARY
    route.module != Module.MEMORY -> ContentOwner.NONE
    route.section == Section.TIME -> ContentOwner.TIME
    route.section == Section.HABITS -> ContentOwner.HABIT
    route.category == MemoryCategory.ACCOUNTING -> ContentOwner.ACCOUNTING
    route.section == Section.LESSONS || route.section == Section.REMINDERS -> ContentOwner.REFERENCE
    else -> ContentOwner.NONE
}

/** Review-locked work can leave only by preserving it; it must never enter discard. */
enum class NavigationExitDecision { WAIT, PRESERVE_FOR_REVIEW, CONFIRM_DISCARD, DISCARD_CLEAN }
fun navigationExitDecision(busy: Boolean, reviewRequired: Boolean, hasPendingEdit: Boolean): NavigationExitDecision = when {
    busy -> NavigationExitDecision.WAIT
    reviewRequired -> NavigationExitDecision.PRESERVE_FOR_REVIEW
    hasPendingEdit -> NavigationExitDecision.CONFIRM_DISCARD
    else -> NavigationExitDecision.DISCARD_CLEAN
}

/** Recovery reveals a draft by default; an explicit preserve-and-view-source choice wins. */
fun diarySourceVisible(requested: Boolean, hasPendingEdit: Boolean, allowRetainedDraft: Boolean = false): Boolean =
    requested && (!hasPendingEdit || allowRetainedDraft)

/** UI-thread-confined gate. Every exit request invalidates older callbacks, including
 * requests from an unhosted route. A callback is consumed even when rejected or throwing. */
class NavigationContinuationGate {
    private var epoch = 0L
    fun begin() { epoch++ }
    fun once(origin: ProductRoute, originTool: Boolean,
        current: () -> Pair<ProductRoute, Boolean>, proceed: () -> Unit): () -> Unit {
        val issued = epoch
        var used = false
        return {
            if (!used) {
                used = true
                if (issued == epoch && current() == (origin to originTool)) proceed()
            }
        }
    }
}
