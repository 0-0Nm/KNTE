package com.knte.core.reference.formal

import com.knte.core.reference.ReferenceEntry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** In-process integrity/ownership tests. Not human-consent or persistent-write evidence. */
class FormalReferenceWitnessTest {
    private val context=FormalReferenceContext.seeded(LocalDate.of(2026,11,13),42)
    private val prefixes=FormalReferencePrefixes.empty()
    private val projector=FormalReferenceProjection
    private fun events(n:Int,tags:List<String> = emptyList())=listOf<FormalReferenceEvent>(
        FormalReferenceEvent.MemoUpsert(ReferenceEntry.Memo("m-%08x".format(n),"synthetic-$n",
            "2026-11-13T03:00:00","2026-11-13T03:00:00",tags=tags)))
    private fun plan(n:Int)=FormalReferenceWitnessGate.prepare(events(n),context,projector)
    private fun confirm(p:FormalReferencePlan,n:Int,c:String=p.confirmation,prev:String?=null)=
        FormalReferenceWitnessGate.confirm(p,c,events(n),context,projector,prefixes,prev)
    private fun reject(code:String,block:()->Unit) {
        try{block();fail("expected $code")}catch(e:FormalReferenceException){assertEquals(code,e.code)}
    }
    @Test fun samePlanAndEquivalentPlanCannotConfirmTwice() {
        val a=plan(101);val b=plan(101);assertNotEquals(a.token,b.token)
        confirm(a,101)
        reject(FormalReferenceCodes.REPLAY){confirm(a,101)}
        reject(FormalReferenceCodes.REPLAY){confirm(b,101)}
    }
    @Test fun wrongTokenDoesNotConsumeValidPlan() {
        val p=plan(102)
        reject(FormalReferenceCodes.CONFIRMATION_INVALID){confirm(p,102,"wrong")}
        assertTrue(confirm(p,102).applicableInMemory)
    }
    @Test fun callerCannotSelfAttestAnUnconfirmedPredecessor() {
        val a=plan(103)
        val b=FormalReferenceWitnessGate.prepare(events(104),context,projector,prefixes,a.token)
        reject(FormalReferenceCodes.MEMBER_ORDER){confirm(b,104,prev=a.token)}
        confirm(a,103);assertTrue(confirm(b,104,prev=a.token).applicableInMemory)
    }
    @Test fun changedInputOrObservedMemberIsRejected() {
        val p=plan(105)
        reject(FormalReferenceCodes.BASELINE_DRIFT){confirm(p,106)}
        reject(FormalReferenceCodes.MEMBER_REPLACED){
            FormalReferenceWitnessGate.confirm(p,p.confirmation,events(105),context,projector,prefixes,
                observedBuffers=FormalReferenceBuffers.empty())
        }
        confirm(p,105)
    }
    @Test fun newPlanObjectCannotImpersonateIssuedPlanEvenWithCopiedPublicMetadata() {
        val p=plan(107)
        val forged=FormalReferencePlan(p.token,p.confirmation,p.sequence,p.predecessorToken,p.baseline,
            p.contextFingerprint,p.memberHashes,p.lineCounts,p.witness,p.preview,p.buffers,p.prefixes)
        reject(FormalReferenceCodes.CONFIRMATION_INVALID){confirm(forged,107)}
        confirm(p,107)
    }
    @Test fun projectorCannotMutateDeepFrozenTagsAndOriginalMutationCausesDrift() {
        val original=mutableListOf("tag")
        var mutationBlocked=false
        val custom=object:FormalPureProjector {
            override fun project(events:List<FormalReferenceEvent>,context:FormalReferenceContext):FormalPureResult {
                val tagList=(events.single() as FormalReferenceEvent.MemoUpsert).entry.tags
                try{(tagList as MutableList<String>).add("injected")}catch(_:UnsupportedOperationException){mutationBlocked=true}
                return projector.project(events,context)
            }
        }
        val es=events(108,original)
        val p=FormalReferenceWitnessGate.prepare(es,context,custom)
        assertTrue(mutationBlocked);assertEquals(listOf("tag"),original)
        original.add("changed")
        reject(FormalReferenceCodes.BASELINE_DRIFT){
            FormalReferenceWitnessGate.confirm(p,p.confirmation,es,context,custom,prefixes)
        }
    }
    @Test fun inconsistentProjectorCountsFailBeforePlanIssuance() {
        val bad=object:FormalPureProjector {
            override fun project(events:List<FormalReferenceEvent>,context:FormalReferenceContext):FormalPureResult {
                val r=projector.project(events,context)
                return FormalPureResult(r.buffers,r.lineCounts+ ("memo" to 0),r.descriptions)
            }
        }
        reject(FormalReferenceCodes.MEMBER_REPLACED){FormalReferenceWitnessGate.prepare(events(109),context,bad)}
    }
    @Test fun metadataOwnsCopiesRatherThanExposingCallerMaps() {
        val p=plan(110);val map=p.memberHashes.toMutableMap();val counts=p.lineCounts.toMutableMap()
        val w=FormalReferenceWitness(p.baseline,p.contextFingerprint,p.witness.prefixHashes,map,counts,
            p.witness.combinedHash,1,p.token,emptySet())
        map["memo"]="tampered";counts["memo"]=999
        assertEquals(p.memberHashes,w.memberHashes);assertEquals(w.memberHashes,w.memberHashesView())
        assertEquals(p.lineCounts,w.lineCounts)
    }
    @Test fun simultaneousConfirmationReturnsExactlyOneResult() {
        val p=plan(111);val ok=AtomicInteger();val denied=AtomicInteger()
        val threads=List(2){Thread{try{confirm(p,111);ok.incrementAndGet()}catch(_:FormalReferenceException){denied.incrementAndGet()}}}
        threads.forEach{it.start()};threads.forEach{it.join()}
        assertEquals(1,ok.get());assertEquals(1,denied.get())
    }
}
