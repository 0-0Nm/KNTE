package com.knte.preview.modelconfig

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Production [AndroidModelConfigStore] unit tests.
 *
 * These use a TEST-PRIVATE in-memory implementation of the real
 * android.content.SharedPreferences INTERFACE (compiled against android.jar, never
 * instantiated as an Android framework object at runtime). No Context, no device, no
 * Android Keystore, no framework stub-from-nowhere, no user credential. The fake only
 * models the single commit()/apply() transaction contract the store relies on.
 *
 * Scope: presence/partial metadata fail-closed, alias/purpose/version/storage binding,
 * and that commit()==false is REPORTED AS FAILURE and never claimed durable. This does
 * NOT prove device SharedPreferences durability or process-death behavior. */
class ModelConfigStoreTest {

    private companion object {
        const val ENDPOINT = "https://api.example.com/v1/chat/completions"
        const val HOST = "api.example.com"
        const val PORT = 443
        const val PATH = "/v1/chat/completions"
        const val IV = "aaaaaaaaaaaaaaaa"
        const val BODY = "synthetic-ciphertext"
    }

    private fun profile(presence: CredentialPresence) =
        ModelProfile(ENDPOINT, "gpt-test", presence)

    private fun record(
        version: Int = ModelConfigBinding.CREDENTIAL_VERSION,
        alias: String = ModelConfigBinding.DEFAULT_ALIAS,
        purpose: String = ModelConfigBinding.DEFAULT_PURPOSE,
        storage: String = ModelConfigBinding.STORAGE_LABEL,
        provider: ProviderKind = ProviderKind.Generic
    ) = CredentialRecord(
        endpoint = ENDPOINT, host = HOST, port = PORT, path = PATH,
        provider = provider, purpose = purpose, version = version,
        sealed = SealedCredential(IV, BODY), storage = storage, alias = alias
    )

    private fun expectConfigError(expectedCode: String? = null, block: () -> Unit): ModelConfigException {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("expected ModelConfigException, got $e", e is ModelConfigException)
        val mce = e as ModelConfigException
        if (expectedCode != null) assertEquals(expectedCode, mce.code)
        return mce
    }

    // ---------- in-memory interface fake (test-private) ----------

    private class MemoryPrefs : SharedPreferences {
        val strings = HashMap<String, String>()
        val ints = HashMap<String, Int>()
        var commitResult = true
        val commitCalls = AtomicInteger(0)
        val applyCalls = AtomicInteger(0)
        var lastCommittedStrings: Map<String, String> = emptyMap()
        var lastCommittedInts: Map<String, Int> = emptyMap()
        private val listeners = HashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = HashMap<String, Any>(strings).apply { putAll(ints) }
        override fun getString(key: String, defValue: String?): String? = strings[key] ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = ints[key] ?: defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String): Boolean = strings.containsKey(key) || ints.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener
        ) { listeners.add(l) }
        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener
        ) { listeners.remove(l) }

        private class Editor(private val p: MemoryPrefs) : SharedPreferences.Editor {
            private val s = HashMap<String, String>()
            private val i = HashMap<String, Int>()
            private val removals = HashSet<String>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (value == null) removals.add(key) else { s[key] = value; removals.remove(key) }
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                i[key] = value; removals.remove(key); return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String): SharedPreferences.Editor {
                removals.add(key); s.remove(key); i.remove(key); return this
            }
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean {
                p.commitCalls.incrementAndGet()
                if (!p.commitResult) return false   // NOTHING is applied on a failed commit
                p.strings.keys.removeAll(removals)
                p.ints.keys.removeAll(removals)
                p.strings.putAll(s)
                p.ints.putAll(i)
                p.lastCommittedStrings = HashMap(p.strings)
                p.lastCommittedInts = HashMap(p.ints)
                return true
            }
            override fun apply() { p.applyCalls.incrementAndGet(); commit() }
        }
    }

    private fun committedRecord(prefs: MemoryPrefs): AndroidModelConfigStore =
        AndroidModelConfigStore(prefs).also {
            it.commitAtomic(profile(CredentialPresence.Present), record())
        }

    // ---------- readProfile / partial record fail-closed ----------

    @Test
    fun readProfileReturnsNullWhenProfileIsAbsentOrPartial() {
        val prefs = MemoryPrefs()
        val store = AndroidModelConfigStore(prefs)
        assertNull(store.readProfile())
        prefs.strings["model.endpoint"] = ENDPOINT
        assertNull(store.readProfile()) // model missing -> not a profile
        prefs.strings["model.model"] = "gpt-test"
        prefs.strings["model.credentialPresence"] = CredentialPresence.Present.name
        assertEquals(CredentialPresence.Present, store.readProfile()?.credentialPresence)
    }

    @Test
    fun readCredentialRecordReturnsNullOnlyWhenCompletelyAbsent() {
        val prefs = MemoryPrefs()
        val store = AndroidModelConfigStore(prefs)
        assertNull(store.readCredentialRecord())
        prefs.strings["model.cred.endpoint"] = ENDPOINT
        // One single stray key is a PARTIAL / interrupted record -> fail closed, never null.
        expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) { store.readCredentialRecord() }
    }

    @Test
    fun everyPartialCredentialRecordFailsClosed() {
        // A complete committed record with exactly ONE key removed must throw.
        val full = MemoryPrefs()
        committedRecord(full)
        val keys = listOf(
            "model.cred.endpoint", "model.cred.host", "model.cred.port",
            "model.cred.path", "model.cred.provider", "model.cred.purpose",
            "model.cred.version", "model.cred.iv", "model.cred.body",
            "model.cred.alias", "model.cred.storage"
        )
        keys.forEach { key ->
            val p = MemoryPrefs()
            p.strings.putAll(full.strings)
            p.ints.putAll(full.ints)
            p.strings.remove(key)
            p.ints.remove(key)
            expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
                AndroidModelConfigStore(p).readCredentialRecord()
            }
        }
    }

    @Test
    fun completeRecordRoundTripsAllV3Metadata() {
        val prefs = MemoryPrefs()
        val store = committedRecord(prefs)
        val rec = store.readCredentialRecord()!!
        assertEquals(ENDPOINT, rec.endpoint)
        assertEquals(HOST, rec.host)
        assertEquals(PORT, rec.port)
        assertEquals(PATH, rec.path)
        assertEquals(ProviderKind.Generic, rec.provider)
        assertEquals(ModelConfigBinding.DEFAULT_PURPOSE, rec.purpose)
        assertEquals(ModelConfigBinding.CREDENTIAL_VERSION, rec.version)
        assertEquals(ModelConfigBinding.DEFAULT_ALIAS, rec.alias)
        assertEquals(ModelConfigBinding.STORAGE_LABEL, rec.storage)
        assertEquals(IV, rec.sealed.iv)
        assertEquals(BODY, rec.sealed.ciphertext)
        assertEquals(CredentialPresence.Present, store.readProfile()?.credentialPresence)
    }

    // ---------- no silent migration / bad metadata ----------

    @Test
    fun legacyOrInconsistentMetadataIsRejectedNotMigrated() {
        listOf(
            Pair("version", { p: MemoryPrefs -> p.ints["model.cred.version"] = 2 }),
            Pair("alias", { p: MemoryPrefs -> p.strings["model.cred.alias"] = "old.alias" }),
            Pair("purpose", { p: MemoryPrefs -> p.strings["model.cred.purpose"] = "old-purpose" }),
            Pair("storage", { p: MemoryPrefs -> p.strings["model.cred.storage"] = "app-private" }),
            Pair("provider", { p: MemoryPrefs -> p.strings["model.cred.provider"] = "NoSuchProvider" }),
            Pair("port", { p: MemoryPrefs -> p.ints["model.cred.port"] = 0 }),
            Pair("emptyIv", { p: MemoryPrefs -> p.strings["model.cred.iv"] = "" })
        ).forEach { (label, mutate) ->
            val base = MemoryPrefs()
            committedRecord(base)
            val p = MemoryPrefs()
            p.strings.putAll(base.strings)
            p.ints.putAll(base.ints)
            mutate(p)
            val beforeRead = HashMap(p.strings)
            val beforeReadInts = HashMap(p.ints)
            expectConfigError(ModelConfigCodes.VAULT_READ_FAILED) {
                AndroidModelConfigStore(p).readCredentialRecord()
            }
            // A failing read must NOT migrate, repair or overwrite the damaged record:
            // compare the post-mutation snapshot, not the pre-mutation baseline.
            assertEquals("case $label strings", beforeRead, p.strings)
            assertEquals("case $label ints", beforeReadInts, p.ints)
        }
    }

    @Test
    fun commitAtomicRejectsInconsistentPresenceAndRecord() {
        val prefs = MemoryPrefs()
        val store = AndroidModelConfigStore(prefs)
        expectConfigError(ModelConfigCodes.COMMIT_FAILED) {
            store.commitAtomic(profile(CredentialPresence.Present), null)
        }
        expectConfigError(ModelConfigCodes.COMMIT_FAILED) {
            store.commitAtomic(profile(CredentialPresence.NotConfigured), record())
        }
        assertEquals(0, prefs.commitCalls.get()) // nothing was even submitted
        assertNull(store.readProfile())
    }

    @Test
    fun commitAtomicRejectsDamagedRecordWithoutOverwriting() {
        val prefs = MemoryPrefs()
        committedRecord(prefs)
        val before = HashMap(prefs.strings)
        val store = AndroidModelConfigStore(prefs)
        listOf(
            record(version = 2),
            record(alias = "other"),
            record(purpose = "other"),
            record(storage = "other"),
            record().copy(port = 0),
            record().copy(sealed = SealedCredential("", BODY))
        ).forEach { bad ->
            expectConfigError(ModelConfigCodes.COMMIT_FAILED) {
                store.commitAtomic(profile(CredentialPresence.Present), bad)
            }
        }
        assertEquals(before, prefs.strings) // no silent overwrite of a damaged record
    }

    @Test
    fun commitAtomicNeverSilentlyErasesAnExistingRecord() {
        val prefs = MemoryPrefs()
        committedRecord(prefs)
        val store = AndroidModelConfigStore(prefs)
        expectConfigError(ModelConfigCodes.COMMIT_FAILED) {
            store.commitAtomic(profile(CredentialPresence.NotConfigured), null)
        }
        assertNotNull(store.readCredentialRecord())
        assertEquals(CredentialPresence.Present, store.readProfile()?.credentialPresence)
    }

    // ---------- clearCredentialMetadata: presence sync ----------

    @Test
    fun clearCredentialMetadataSetsPresenceNotConfiguredInOneTransaction() {
        val prefs = MemoryPrefs()
        val store = committedRecord(prefs)
        val before = prefs.commitCalls.get()
        store.clearCredentialMetadata()
        assertEquals(before + 1, prefs.commitCalls.get())
        assertNull(store.readCredentialRecord())
        assertEquals(CredentialPresence.NotConfigured, store.readProfile()?.credentialPresence)
        // endpoint + model survive; only the credential claim is dropped.
        assertEquals(ENDPOINT, store.readProfile()?.endpoint)
        assertEquals("gpt-test", store.readProfile()?.model)
    }

    @Test
    fun clearCredentialMetadataIsIdempotent() {
        val prefs = MemoryPrefs()
        val store = AndroidModelConfigStore(prefs)
        store.clearCredentialMetadata()
        store.clearCredentialMetadata()
        assertNull(store.readCredentialRecord())
        assertEquals(2, prefs.commitCalls.get())
    }

    // ---------- commit() == false is a FAILURE, never "durable" ----------

    @Test
    fun commitFalseThrowsAndLeavesNoDurableState() {
        val prefs = MemoryPrefs()
        prefs.commitResult = false
        val store = AndroidModelConfigStore(prefs)
        expectConfigError(ModelConfigCodes.COMMIT_FAILED) {
            store.commitAtomic(profile(CredentialPresence.Present), record())
        }
        assertEquals(1, prefs.commitCalls.get())
        // The fake models a transaction that was NOT persisted: nothing is readable back.
        assertNull(store.readProfile())
        assertNull(store.readCredentialRecord())
        assertTrue(prefs.lastCommittedStrings.isEmpty())
    }

    @Test
    fun commitFalseOnProfileOnlySaveReportsFailure() {
        val prefs = MemoryPrefs()
        prefs.commitResult = false
        val store = AndroidModelConfigStore(prefs)
        expectConfigError(ModelConfigCodes.COMMIT_FAILED) {
            store.commitAtomic(profile(CredentialPresence.NotConfigured), null)
        }
        assertNull(store.readProfile())
    }

    @Test
    fun commitFalseOnClearReportsFailureAndKeepsPreviousClaim() {
        val prefs = MemoryPrefs()
        committedRecord(prefs)
        prefs.commitResult = false
        val store = AndroidModelConfigStore(prefs)
        expectConfigError(ModelConfigCodes.COMMIT_FAILED) { store.clearCredentialMetadata() }
        assertNotNull(store.readCredentialRecord())
        assertEquals(CredentialPresence.Present, store.readProfile()?.credentialPresence)
    }

    @Test
    fun successfulCommitUsesExactlyOneCommitNotApply() {
        val prefs = MemoryPrefs()
        val store = AndroidModelConfigStore(prefs)
        store.commitAtomic(profile(CredentialPresence.Present), record())
        assertEquals(1, prefs.commitCalls.get())
        assertEquals(0, prefs.applyCalls.get())
        assertNotNull(store.readCredentialRecord())
    }
}
