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

    @Test fun currentContinuationRunsOnlyOnce() {
        val gate=NavigationContinuationGate();val origin=ProductRoute();var count=0
        gate.begin();val next=gate.once(origin,false,{origin to false}){count++}
        next();next();assertEquals(1,count)
    }
    @Test fun newerExitInvalidatesOldCallbackEvenAfterReturningToSameRoute() {
        val gate=NavigationContinuationGate();val origin=ProductRoute()
        var current=origin;var count=0
        gate.begin();val old=gate.once(origin,false,{current to false}){count++}
        gate.begin();current=ProductRoute(Module.QUIET,null,null)
        gate.begin();current=origin
        old();assertEquals(0,count)
        val latest=gate.once(origin,false,{current to false}){count++}
        latest();assertEquals(1,count)
    }
    @Test fun rejectedOriginCallbackCannotReviveWhenRouteLaterMatches() {
        val gate=NavigationContinuationGate();val origin=ProductRoute();var current=origin;var count=0
        gate.begin();val next=gate.once(origin,false,{current to false}){count++}
        current=ProductRoute(Module.QUIET,null,null);next()
        current=origin;next();assertEquals(0,count)
    }
    @Test fun changedToolRejectsAndConsumesOldCallback() {
        val gate=NavigationContinuationGate();val origin=ProductRoute();var tool=true;var count=0
        gate.begin();val next=gate.once(origin,false,{origin to tool}){count++}
        next();tool=false;next();assertEquals(0,count)
    }
    @Test fun throwingContinuationIsConsumedBeforeAction() {
        val gate=NavigationContinuationGate();val origin=ProductRoute();var count=0
        gate.begin();val next=gate.once(origin,false,{origin to false}){count++;throw IllegalStateException("synthetic navigation failure")}
        try { next();fail("expected synthetic failure") } catch(_:IllegalStateException) { }
        next();assertEquals(1,count)
    }

    @Test fun modelSettingsOwnTheirDraftAndNeverAliasBusinessRouteOwnership() {
        assertFalse(ProductRoute.leaves.any { contentOwner(it) == ContentOwner.MODEL_CONFIG })
        assertEquals(NavigationExitDecision.WAIT,navigationExitDecision(true,false,true))
        assertEquals(NavigationExitDecision.CONFIRM_DISCARD,navigationExitDecision(false,false,true))
        assertEquals(NavigationExitDecision.DISCARD_CLEAN,navigationExitDecision(false,false,false))
    }

}
