package com.knte.preview.ui.product
import org.junit.Assert.*
import org.junit.Test
class NavigationContentTest {
    @Test fun eachRouteOwnsOnlyItsHostedEditor() {
        assertEquals(ContentOwner.DIARY,contentOwner(ProductRoute()))
        assertEquals(ContentOwner.PREVIEW_TOOL,contentOwner(ProductRoute(),true))
        assertEquals(ContentOwner.TIME,contentOwner(ProductRoute(Module.MEMORY,MemoryCategory.TIME,Section.TIME)))
        assertEquals(ContentOwner.HABIT,contentOwner(ProductRoute(Module.MEMORY,MemoryCategory.TIME,Section.HABITS)))
        assertEquals(ContentOwner.REFERENCE,contentOwner(ProductRoute(Module.MEMORY,MemoryCategory.DIARY,Section.LESSONS)))
        assertEquals(ContentOwner.REFERENCE,contentOwner(ProductRoute(Module.MEMORY,MemoryCategory.TIME,Section.REMINDERS)))
        assertEquals(ContentOwner.ACCOUNTING,contentOwner(ProductRoute(Module.MEMORY,MemoryCategory.ACCOUNTING,Section.REPORTS)))
    }
    @Test fun unrelatedReadingAndQuietRoutesDoNotOwnHiddenBusinessDrafts() {
        ProductRoute.leaves.filter{it.module!=Module.MEMORY}.forEach{assertEquals(ContentOwner.NONE,contentOwner(it))}
    }
    @Test fun busyAlwaysWaitsEvenWhenReviewOrDraftAlsoExists() {
        for(review in listOf(false,true)) for(draft in listOf(false,true))
            assertEquals(NavigationExitDecision.WAIT,navigationExitDecision(true,review,draft))
    }
    @Test fun reviewLockAlwaysPreservesInsteadOfDiscardingOrSwallowingNavigation() {
        for(draft in listOf(false,true))
            assertEquals(NavigationExitDecision.PRESERVE_FOR_REVIEW,navigationExitDecision(false,true,draft))
    }
    @Test fun onlyOrdinaryPendingEditsOfferDiscardConfirmation() {
        assertEquals(NavigationExitDecision.CONFIRM_DISCARD,navigationExitDecision(false,false,true))
        assertEquals(NavigationExitDecision.DISCARD_CLEAN,navigationExitDecision(false,false,false))
    }

    @Test fun recoveredDiaryDraftIsVisibleUnlessSourceWasExplicitlyChosen() {
        assertFalse(diarySourceVisible(true,true))
        assertTrue(diarySourceVisible(true,true,true))
        assertTrue(diarySourceVisible(true,false))
        assertFalse(diarySourceVisible(false,true,true))
    }

}
