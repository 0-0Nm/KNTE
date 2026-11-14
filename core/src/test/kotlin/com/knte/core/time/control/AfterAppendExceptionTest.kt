package com.knte.core.time.control
import com.knte.core.time.repository.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDateTime

/** Same-module fault hook injects an exception at repository readback phase AFTER real append.
 * Does not pretend an actual filesystem/storage failure was induced. */
class AfterAppendExceptionTest {
    @Test fun exceptionAfterRealAppendMustRetainIntentAndReportUnknownNotDefiniteFailure() {
        val root=Files.createTempDirectory(File(System.getProperty("review.synthetic")).toPath(),"after-append-exception-").toFile()
        val dir=File(root,"store")
        val src=TimeSessionSource.openWithFaultInjection(dir) { throw IOException("INJECTED_READBACK_IO_AFTER_APPEND") }
        val c=src.controller(object:TimeClock { override fun now()=LocalDateTime.parse("2026-10-23T09:00") })
        val journal=InMemoryIntentJournal(); c.setIntentJournal(journal)
        val h=c.prepareStart("QA")
        var code:String?=null
        try { c.confirm(h); fail("injected readback must throw") } catch(e:TimeControlException) { code=e.code }
        val history=File(dir,"timetrack.jsonl").readText()
        assertTrue(history.contains(h.eventId)); assertTrue(File(dir,"timetrack.jsonl.pending").isFile)
        val intents=journal.loadAll().size
        // Read the private gate only for diagnostics BEFORE tolerant/strict load can alter it.
        val review=c.javaClass.getDeclaredField("reviewFlag").apply { isAccessible=true }.getBoolean(c)
        println("AFTER_REAL_APPEND_EXCEPTION: code=$code containsEvent=${history.contains(h.eventId)} pending=true journal=$intents reviewBeforeRead=$review")
        assertEquals("landed event was mislabeled definite failure", "TIME_WRITE_RESULT_UNKNOWN",code)
        assertEquals("unknown intent must not be cleared",1,intents)
        assertTrue(review)
    }
}
