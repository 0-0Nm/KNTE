package com.knte.core.habit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/** Phase-injection and drift tests: pending durability, cancelled plans, fault injection. */
class HabitLedgerFaultTest {

    private fun fixture(test: (File) -> Unit) {
        val root = Files.createTempDirectory("knte-habit-fault-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun repo(root: File) = HabitLedgerStore.open(File(root, "habit"))

    private fun api(root: File) = HabitLedgerApi(repo(root))

    private fun commit(api: HabitLedgerApi, plan: HabitAppendPlan) = api.commit(plan, plan.confirmation)

    private fun err(block: () -> Unit): String =
        try {
            block()
            "NO_ERROR"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }

    @Test fun failureBeforeTargetWriteRetainsPendingAndFailsClosed() = fixture { root ->
        // A crash right after the pending marker is durable: the marker stays and blocks every read.
        val failing = HabitLedgerStore.openForFault(File(root, "habit")) { phase ->
            phase == "pending-durable"
        }
        val failingApi = HabitLedgerApi(failing)
        val plan = failingApi.planCreate("h-e0000002", "x", 7, "r-e0000002", "2026-10-01T08:00:00")
        val message = err { failingApi.commit(plan, plan.confirmation) }
        assertTrue(message.contains("HABIT_INJECTED_FAULT:pending-durable"))
        assertTrue(File(File(root, "habit/db"), "habits.jsonl.pending").exists())
        assertFalse(File(File(root, "habit/db"), "habits.jsonl").exists())
        assertTrue(err { failingApi.commit(plan, plan.confirmation) }
            .contains("HABIT_PENDING_REVIEW_REQUIRED"))
        assertTrue(err { failingApi.snapshot() }.contains("HABIT_PENDING_REVIEW_REQUIRED"))
        // A pending marker also blocks building any replacement plan: no silent recovery, no retry.
        assertTrue(err { failingApi.planCreate("h-e0000003", "y", 7, "r-e0000003", "2026-10-01T09:00:00") }
            .contains("HABIT_PENDING_REVIEW_REQUIRED"))
    }

    @Test fun failureAfterPublishKeepsBeforeImageAndRefusesReplay() = fixture { root ->
        val healthy = api(root)
        commit(healthy, healthy.planCreate("h-e0000011", "x", 7, "r-e0000011", "2026-10-01T08:00:00"))
        val bytesA = File(File(root, "habit/db"), "habits.jsonl").readBytes()
        val failing = HabitLedgerStore.openForFault(File(root, "habit")) { phase ->
            phase == "target-published"
        }
        val failingApi = HabitLedgerApi(failing)
        val plan = failingApi.planCheck("h-e0000011", "2026-10-05", true, null,
            "r-e0000012", "2026-10-05T07:30:00")
        assertTrue(err { failingApi.commit(plan, plan.confirmation) }
            .contains("HABIT_INJECTED_FAULT:target-published"))
        val onDisk = File(File(root, "habit/db"), "habits.jsonl").readBytes()
        assertTrue(onDisk.size > bytesA.size) // the append reached the file
        assertArrayEquals(bytesA, onDisk.copyOfRange(0, bytesA.size))
        assertTrue(File(File(root, "habit/db"), "habits.jsonl.pending").exists())
        val beforeImages = mutableListOf<ByteArray>()
        Files.walk(File(File(root, "habit/db"), ".journal").toPath()).use { stream ->
            stream.filter { it.fileName.toString() == "before.bin" }
                .forEach { beforeImages.add(Files.readAllBytes(it)) }
        }
        assertTrue(beforeImages.any { it.contentEquals(bytesA) })
        assertTrue(err { failingApi.commit(plan, plan.confirmation) }
            .contains("HABIT_PENDING_REVIEW_REQUIRED"))
    }

    @Test fun tamperedReadbackIsReportedUnknownAndKeepsEvidence() = fixture { root ->
        val store = File(root, "habit/db")
        val tampered = "tampered\n".toByteArray()
        // The phase probe rewrites the ledger after publication: the readback no longer matches.
        val faulty = HabitLedgerStore.openForFault(File(root, "habit")) { phase ->
            if (phase == "target-published") Files.write(File(store, "habits.jsonl").toPath(), tampered)
            false
        }
        val api = HabitLedgerApi(faulty)
        val plan = api.planCreate("h-f0000001", "x", 7, "r-f0000001", "2026-10-01T08:00:00")
        assertTrue(err { api.commit(plan, plan.confirmation) }.contains("HABIT_WRITE_RESULT_UNKNOWN"))
        assertArrayEquals(tampered, File(store, "habits.jsonl").readBytes())
        assertTrue(File(store, "habits.jsonl.pending").exists())
        var receipts = 0
        Files.walk(File(store, ".journal").toPath()).use { stream ->
            receipts = stream.filter { it.fileName.toString() == "receipt.txt" }.count().toInt()
        }
        assertEquals(0, receipts)
        // No retry, no repair: reads stay blocked until a human clears the pending marker.
        assertTrue(err { api.commit(plan, plan.confirmation) }.contains("HABIT_PENDING_REVIEW_REQUIRED"))
    }

    @Test fun healthyInstanceRefusesToReadWhilePendingExists() = fixture { root ->
        val store = File(root, "habit/db")
        store.mkdirs()
        File(store, "habits.jsonl.pending").writeText("marker")
        assertTrue(err { repo(root).load() }.contains("HABIT_PENDING_REVIEW_REQUIRED"))
        // A fresh healthy instance on the same root agrees: no silent recovery.
        assertTrue(err { api(root).snapshot() }.contains("HABIT_PENDING_REVIEW_REQUIRED"))
        File(store, "habits.jsonl.pending").delete()
        assertTrue(repo(root).load().events().isEmpty())
    }

    @Test fun planAndCommitOrderIsPendingFirstThenReceipt() = fixture { root ->
        val phases = mutableListOf<String>()
        val store = HabitLedgerStore.openForPhases(File(root, "habit"), phases)
        val api = HabitLedgerApi(store)
        val plan = api.planCreate("h-11112222", "x", 7, "r-11112222", "2026-10-01T08:00:00")
        commit(api, plan)
        assertEquals(listOf("pending-durable", "target-published", "receipt-durable"), phases)
        // The pending marker is gone only after the receipt was durable.
        assertFalse(File(File(root, "habit/db"), "habits.jsonl.pending").exists())
    }

    @Test fun appendIsStrictlyTailOnlyEvenUnderRepeatedCommits() = fixture { root ->
        val api = api(root)
        commit(api, api.planCreate("h-22223333", "x", 7, "r-22220001", "2026-10-01T08:00:00"))
        val first = File(File(root, "habit/db"), "habits.jsonl").readBytes()
        commit(api, api.planCheck("h-22223333", "2026-10-05", true, null, "r-22220002", "2026-10-05T07:30:00"))
        commit(api, api.planCheck("h-22223333", "2026-10-06", true, null, "r-22220003", "2026-10-06T07:30:00"))
        val last = File(File(root, "habit/db"), "habits.jsonl").readBytes()
        assertArrayEquals(first, last.copyOfRange(0, first.size))
        assertEquals(3, last.count { it == '\n'.code.toByte() })
        assertEquals(3, api.snapshot().eventCount)
    }

    @Test fun defaultClockIsCallerSuppliedAndNeverInferredInsideCodec() {
        // The codec has no clock access: it only formats what the caller passes in.
        assertEquals("2026-10-05T07:30:00", HabitCodec.stamp(LocalDateTime.of(2026, 10, 5, 7, 30, 0)))
        assertEquals("2026-12-31T23:59:59", HabitCodec.stamp(LocalDateTime.of(2026, 12, 31, 23, 59, 59)))
        assertFalse(
            HabitCodec.stamp(LocalDateTime.of(2026, 10, 5, 7, 30, 0)) ==
                HabitCodec.stamp(LocalDateTime.of(2026, 10, 5, 7, 30, 1))
        )
    }
}
