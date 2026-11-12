package com.knte.preview.modelconfig

import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel behaviour tests, fully self-contained: the required VM-behaviour tests run
 * against a PRIVATE [FakeRepository] (interface-only, no dependency on the parent-owned
 * ModelConfigRepository.kt implementation or on the parallel-owned
 * tests/FakeModelConfigStorage.kt). A few integration tests additionally exercise the
 * REAL repository through private store/vault stubs.
 *
 * Helper contract (rewritten: the old helper cancelled viewModelScope right after
 * construction, which killed the init reload before it could ever run):
 *   construct -> advanceUntilIdle() so the initial reload completes -> body ->
 *   cancel the scope in finally. The scope is NEVER cancelled between construction and
 *   the first advance.
 *
 * The draft secret is asserted ABSENT from every published value, from toString() and
 * from any error/notice string; its retention or loss is proven BEHAVIOURALLY (a retry
 * save commits the same key digest; a cancelled scope commits nothing) — there is no
 * peek getter anywhere, and the fakes store SHA-256 DIGESTS only, never plaintext.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: RecordingStore
    private lateinit var vault: StubVault

    private val openAiEndpoint = "https://api.openai.com/v1/chat/completions"
    private val otherHostEndpoint = "https://api.deepseek.com/v1/chat/completions"
    private val anthropicEndpoint = "https://api.anthropic.com/v1/chat/completions"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = RecordingStore()
        vault = StubVault()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ helpers

    /** Construct + initialize the init reload + body + scope cleanup in finally. */
    private fun withFake(
        snapshot: ModelConfigSnapshot = ModelConfigSnapshot(null, null),
        configure: FakeRepository.() -> Unit = {},
        block: (ModelConfigViewModel, FakeRepository) -> Unit
    ) {
        val repo = FakeRepository(snapshot).apply(configure)
        val vm = ModelConfigViewModel(repo, dispatcher)
        try {
            dispatcher.scheduler.advanceUntilIdle()
            block(vm, repo)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /** Same lifecycle, but through the REAL repository + private store/vault stubs
     * (integration slice; depends on the parent-owned repository implementation). */
    private fun withRealRepository(block: (ModelConfigViewModel) -> Unit) {
        val vm = ModelConfigViewModel(AndroidModelConfigRepository(store, vault), dispatcher)
        try {
            dispatcher.scheduler.advanceUntilIdle()
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun advance() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun codeOf(message: String): String =
        Regex("\\[([A-Z_]+)]").find(message)?.groupValues?.get(1) ?: "UNKNOWN"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun savedSnapshot() = ModelConfigSnapshot(
        ModelProfile(openAiEndpoint, "m-old", CredentialPresence.Present),
        openAiEndpoint
    )

    /** A stored OpenAI-compatible profile WITH a sealed credential, exactly like the
     * repository expects to find it (v3 binding, fixed labels). */
    private fun storedOpenAiCredential() {
        store.profile = ModelProfile(openAiEndpoint, "m-old", CredentialPresence.Present)
        store.credential = CredentialRecord(
            endpoint = openAiEndpoint,
            host = "api.openai.com",
            port = 443,
            path = "/v1/chat/completions",
            provider = ProviderKind.OpenAi,
            purpose = ModelConfigBinding.DEFAULT_PURPOSE,
            version = ModelConfigBinding.CREDENTIAL_VERSION,
            sealed = SealedCredential("iv-old", "synthetic-old"),
            storage = ModelConfigBinding.STORAGE_LABEL
        )
    }

    // ---------------------------------------------------------------- loading / editability

    @Test
    fun loadingBlocksEditsAndActionsUntilReloadCompletes() {
        val repo = FakeRepository()
        val vm = ModelConfigViewModel(repo, dispatcher)
        try {
            assertTrue(vm.state.value.loading)
            vm.onEndpointChanged(otherHostEndpoint)
            vm.onModelChanged("m-x")
            vm.onCredentialChanged("k")
            vm.replaceCredential("k2")
            vm.cancel()
            vm.discardDraftSecret()
            // Nothing was silently applied while loading.
            assertTrue(vm.state.value.loading)
            assertEquals("", vm.state.value.endpoint)
            assertEquals("", vm.state.value.model)
            assertFalse(vm.state.value.draftCredentialPending)

            advance()
            assertFalse(vm.state.value.loading)
            // After loading completes the fields ARE editable.
            vm.onModelChanged("m-1")
            assertEquals("m-1", vm.state.value.model)
            vm.onCredentialChanged("k")
            assertTrue(vm.state.value.draftCredentialPending)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // --------------------------------------------------- save failure keeps key + pending

    @Test
    fun saveFailureKeepsSecretDraftAndPendingThenRetrySavesSameKey() {
        withFake(savedSnapshot(), {
            commitFailure = java.io.IOException("simulated commit failure")
        }) { vm, repo ->
            vm.onModelChanged("m-new")
            vm.onCredentialChanged("synthetic-key")
            val epochBefore = vm.state.value.secretResetEpoch
            vm.save(); advance()
            assertFalse(vm.state.value.saved)
            assertFalse(vm.state.value.busy)
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.error!!.contains(ModelConfigCodes.COMMIT_FAILED))
            assertEquals("m-new", vm.state.value.model)                  // non-secret kept
            assertTrue(vm.state.value.draftCredentialPending)           // pending kept
            assertEquals(epochBefore, vm.state.value.secretResetEpoch)  // no reset bump
            assertTrue(repo.commitDigests.isEmpty())                     // nothing committed

            // Retry WITHOUT retyping: the VM still holds the draft, so the second save
            // must commit the very same key (behavioural proof; no peek getter).
            repo.commitFailure = null
            vm.save(); advance()
            assertTrue(vm.state.value.saved)
            assertFalse(vm.state.value.draftCredentialPending)
            assertEquals(listOf(sha256("synthetic-key")), repo.commitDigests)
            assertEquals(epochBefore + 1L, vm.state.value.secretResetEpoch)
        }
    }

    @Test
    fun policyFailureKeepsSecretDraftAndPending() {
        withFake(savedSnapshot(), {
            commitFailure = ModelConfigException(
                ModelConfigCodes.PROVIDER_CHANGED, "接口提供方已变化（OpenAi → Generic）"
            )
        }) { vm, repo ->
            vm.onCredentialChanged("synthetic-key")
            val epochBefore = vm.state.value.secretResetEpoch
            vm.save(); advance()
            assertEquals(ModelConfigCodes.PROVIDER_CHANGED, codeOf(vm.state.value.error!!))
            assertFalse(vm.state.value.saved)
            assertTrue(vm.state.value.draftCredentialPending)
            assertEquals(epochBefore, vm.state.value.secretResetEpoch)
            assertTrue(repo.commitDigests.isEmpty())
        }
    }

    // ------------------------------------------------- explicit reset paths bump the epoch

    @Test
    fun explicitDiscardAndCancelBumpEpochAndDropPending() {
        withFake(savedSnapshot()) { vm, repo ->
            val epoch0 = vm.state.value.secretResetEpoch
            vm.onCredentialChanged("k-a")
            assertEquals(epoch0, vm.state.value.secretResetEpoch) // typing alone: no bump
            vm.discardDraftSecret()
            assertEquals(epoch0 + 1L, vm.state.value.secretResetEpoch)
            assertFalse(vm.state.value.draftCredentialPending)

            vm.onCredentialChanged("k-b")
            vm.onModelChanged("m-typed")
            vm.cancel()
            assertEquals(epoch0 + 2L, vm.state.value.secretResetEpoch)
            assertFalse(vm.state.value.draftCredentialPending)
            assertEquals("m-old", vm.state.value.model) // restored, nothing written
            assertTrue(repo.commitDigests.isEmpty())
        }
    }

    @Test
    fun reloadDropsDraftAndBumpsEpochWithoutWriting() {
        withFake(savedSnapshot()) { vm, repo ->
            val epoch0 = vm.state.value.secretResetEpoch
            vm.onModelChanged("m-typed")
            vm.onCredentialChanged("k-a")
            vm.reload(); advance()
            assertEquals("m-old", vm.state.value.model)
            assertFalse(vm.state.value.draftCredentialPending)
            assertEquals(epoch0 + 1L, vm.state.value.secretResetEpoch)
            assertTrue(repo.commitDigests.isEmpty())
        }
    }

    // -------------------------------- busy is synchronous: pre-dispatch rejections

    @Test
    fun busyIsSyncAndBlocksDuplicateSaveAndDiscardBeforeDispatch() {
        withFake(savedSnapshot()) { vm, repo ->
            vm.onModelChanged("m1")
            vm.onCredentialChanged("synthetic-key")
            vm.save()
            // busy was published BEFORE launch: assertable without advancing.
            assertTrue(vm.state.value.busy)

            vm.save() // duplicate save in the same frame
            assertTrue(vm.state.value.error?.contains("正在处理") == true)
            vm.discardDraftSecret() // must not steal the secret mid-flight
            assertTrue(vm.state.value.draftCredentialPending)
            vm.cancel() // must not "restore" while busy
            assertEquals("m1", vm.state.value.model)
            vm.onModelChanged("m2") // edits rejected while busy
            assertEquals("m1", vm.state.value.model)

            advance()
            assertEquals(1, repo.commitDigests.size)
            assertTrue(vm.state.value.saved)
            assertFalse(vm.state.value.draftCredentialPending)
            assertNull(vm.state.value.error)
            // The draft captured at save entry survived the rejected discard above.
            assertEquals(listOf(sha256("synthetic-key")), repo.commitDigests)
        }
    }

    @Test
    fun tripleSaveCommitsOnce() {
        withFake { vm, repo ->
            vm.onEndpointChanged(openAiEndpoint)
            vm.onModelChanged("m1")
            vm.save(); vm.save(); vm.save()
            advance()
            assertEquals(1, repo.commitDigests.size)
            assertTrue(vm.state.value.saved)
        }
    }

    // ------------------------------------------------------------- cancel writes nothing

    @Test
    fun cancelWritesNothing() {
        withFake(savedSnapshot()) { vm, repo ->
            vm.onEndpointChanged(otherHostEndpoint)
            vm.onModelChanged("changed")
            vm.onCredentialChanged("synthetic-key")
            vm.cancel(); advance()
            assertTrue(repo.commitDigests.isEmpty())
            assertEquals(openAiEndpoint, vm.state.value.endpoint)   // restored
            assertEquals("m-old", vm.state.value.model)
            assertFalse(vm.state.value.draftCredentialPending)
            assertFalse(vm.state.value.saved)
        }
    }

    // -------------------------------------- page re-entry clears only stale notice/saved

    @Test
    fun pageReentryAfterCancelDropsStaleNoticeButKeepsSavedFields() {
        withFake(savedSnapshot()) { vm, repo ->
            vm.onModelChanged("m-typed")
            vm.onCredentialChanged("synthetic-key")
            vm.cancel(); advance()
            assertNotNull(vm.state.value.notice)              // banner right after cancel
            vm.onPageEntered()                                // the user re-enters the page
            assertNull(vm.state.value.notice)                 // stale banner is gone
            assertFalse(vm.state.value.saved)
            assertEquals(openAiEndpoint, vm.state.value.endpoint) // stored fields kept
            assertEquals("m-old", vm.state.value.model)
            assertEquals(CredentialPresence.Present, vm.state.value.credentialPresence)
            assertFalse(vm.state.value.draftCredentialPending)
            assertTrue(repo.commitDigests.isEmpty())           // still no write, no reload
        }
    }

    @Test
    fun pageReentryAfterSuccessfulSaveClearsSavedAndNoticeButKeepsInputs() {
        withFake(savedSnapshot()) { vm, repo ->
            vm.onModelChanged("m-new")
            vm.save(); advance()
            assertTrue(vm.state.value.saved)
            assertNotNull(vm.state.value.notice)
            vm.onPageEntered()
            assertNull(vm.state.value.notice)
            assertFalse(vm.state.value.saved)
            assertEquals("m-new", vm.state.value.model)            // committed input kept
            assertEquals(openAiEndpoint, vm.state.value.endpoint)
            assertEquals("m-new", vm.state.value.savedModel)      // no restore, no reload
            assertFalse(vm.state.value.draftCredentialPending)
            assertEquals(1, repo.commitDigests.size)              // exactly one commit
        }
    }

    @Test
    fun pageReentryKeepsErrorAndPendingSecretDraftAndRetryCommitsSameKey() {
        withFake(savedSnapshot(), {
            commitFailure = java.io.IOException("simulated commit failure")
        }) { vm, repo ->
            vm.onModelChanged("m-new")
            vm.onCredentialChanged("synthetic-key")
            vm.save(); advance()
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.draftCredentialPending)
            val epochBefore = vm.state.value.secretResetEpoch
            vm.onPageEntered()                                    // re-enter with error shown
            assertNotNull(vm.state.value.error)                    // error survives re-entry
            assertTrue(vm.state.value.draftCredentialPending)     // pending draft survives
            assertEquals(epochBefore, vm.state.value.secretResetEpoch) // no secret reset
            assertEquals("m-new", vm.state.value.model)

            // Retry WITHOUT retyping still commits the very same key after re-entry.
            repo.commitFailure = null
            vm.save(); advance()
            assertTrue(vm.state.value.saved)
            assertEquals(listOf(sha256("synthetic-key")), repo.commitDigests)
        }
    }

    @Test
    fun pageReentryWhileBusyKeepsBusyAndDraftAndLaterClearsNotice() {
        withFake(savedSnapshot()) { vm, repo ->
            vm.onModelChanged("m-busy")
            vm.onCredentialChanged("synthetic-key")
            vm.save()
            assertTrue(vm.state.value.busy)                       // busy before dispatch
            vm.onPageEntered()                                   // re-enter mid-flight
            assertTrue(vm.state.value.busy)                       // busy NOT resolved
            assertTrue(vm.state.value.draftCredentialPending)     // draft NOT cleared
            assertEquals("m-busy", vm.state.value.model)          // inputs untouched
            advance()
            assertTrue(vm.state.value.saved)
            assertEquals(listOf(sha256("synthetic-key")), repo.commitDigests)
            vm.onPageEntered()                                   // re-entry after completion
            assertFalse(vm.state.value.saved)
            assertNull(vm.state.value.notice)
        }
    }

    // ---------------------------------------- read failure is an error, not "empty"

    @Test
    fun readFailureIsReportedNotTreatedAsEmptyConfig() {
        val vm = ModelConfigViewModel(AndroidModelConfigRepository(store, vault), dispatcher)
        try {
            store.failRead = true
            advance()
            assertFalse(vm.state.value.loading)
            assertNull(vm.state.value.notice)
            assertFalse(vm.state.value.saved)
            val error = vm.state.value.error
            assertNotNull(error)
            assertTrue(error!!.contains(ModelConfigCodes.READ_FAILED))
            assertEquals(0L, vm.state.value.secretResetEpoch) // failed reload: no bump

            // Recovery: once the read heals, an explicit reload loads real data.
            store.failRead = false
            storedOpenAiCredential()
            vm.reload(); advance()
            assertNull(vm.state.value.error)
            assertEquals(openAiEndpoint, vm.state.value.endpoint)
            assertEquals(CredentialPresence.Present, vm.state.value.credentialPresence)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // --------------------------------------- scope cancellation never claims success

    @Test
    fun cancellationExceptionIsRethrownNotDowngradedToFailure() {
        withFake(configure = {
            commitFailure = CancellationException("simulated scope cancel")
        }) { vm, _ ->
            vm.onModelChanged("m1")
            vm.save(); advance()
            // Neither success nor a downgraded failure: the job was cancelled.
            assertFalse(vm.state.value.saved)
            assertNull(vm.state.value.error)
        }
    }

    @Test
    fun scopeCancelledBeforeDispatchCommitsNothing() {
        val repo = FakeRepository(savedSnapshot())
        val vm = ModelConfigViewModel(repo, dispatcher)
        try {
            advance()
            vm.onModelChanged("m1")
            vm.save()
            vm.viewModelScope.cancel() // cancel before the scheduler runs the commit
            advance()
            assertFalse(vm.state.value.saved)
            assertNull(vm.state.value.error)
            assertTrue(repo.commitDigests.isEmpty())
            assertTrue(vm.state.value.busy) // honest: the operation never completed
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ---------------------------------------------------------- canLeave / dirty tracking

    @Test
    fun canLeaveTracksDirtyAndPendingKey() {
        withFake(savedSnapshot()) { vm, _ ->
            assertFalse(vm.hasUnsavedChanges())
            assertTrue(vm.canLeave())
            vm.onModelChanged("m-other")
            assertTrue(vm.hasUnsavedChanges())
            assertFalse(vm.canLeave())
            vm.cancel(); advance()
            assertTrue(vm.canLeave())
            vm.onCredentialChanged("k")
            assertTrue(vm.hasUnsavedChanges())
            assertFalse(vm.canLeave())
            vm.discardDraftSecret()
            assertFalse(vm.hasUnsavedChanges())
            assertTrue(vm.canLeave())
        }
    }

    // ------------------------------------------------------------ secret hygiene

    @Test
    fun secretNeverAppearsInStateErrorNoticeOrFakeEchoes() {
        withFake(savedSnapshot(), {
            commitFailure = ModelConfigException(
                ModelConfigCodes.KEY_REPLACEMENT_REQUIRED, "接口地址指向的服务已变化"
            )
        }) { vm, _ ->
            vm.onCredentialChanged("synthetic-secret-value")
            val text = vm.state.value.toString()
            assertFalse(text.contains("synthetic-secret-value"))
            assertFalse(text.contains("secret", true))

            vm.onEndpointChanged(otherHostEndpoint)
            vm.save(); advance()
            val messages = listOfNotNull(vm.state.value.error, vm.state.value.notice)
                .joinToString(" ")
            assertFalse(messages.contains("synthetic-secret-value"))

            vm.cancel(); advance()
            assertFalse(
                listOfNotNull(vm.state.value.notice, vm.state.value.error)
                    .joinToString(" ").contains("synthetic-secret-value")
            )
        }
    }

    // --------------------------- provider / policy behaviour through the REAL repository
    // (integration slice: depends on the parent-owned AndroidModelConfigRepository)

    @Test
    fun emptyKeySaveKeepsExistingCredentialByteForByte() {
        storedOpenAiCredential()
        withRealRepository { vm ->
            vm.onModelChanged("m-new")
            vm.save(); advance()
            assertEquals(CredentialPresence.Present, vm.state.value.credentialPresence)
            assertEquals("m-new", vm.state.value.model)
            assertEquals("synthetic-old", store.credential!!.sealed.ciphertext)
            assertTrue(vm.state.value.saved)
            assertNull(vm.state.value.error)
            assertTrue(vault.sealedDigests.isEmpty())
        }
    }

    @Test
    fun hostChangeWithoutNewKeyIsRejectedAndKeepsInputs() {
        storedOpenAiCredential()
        withRealRepository { vm ->
            vm.onEndpointChanged(otherHostEndpoint)
            vm.save(); advance()
            assertEquals(
                ModelConfigCodes.KEY_REPLACEMENT_REQUIRED,
                codeOf(vm.state.value.error!!)
            )
            assertEquals(otherHostEndpoint, vm.state.value.endpoint) // input kept
            assertEquals("m-old", vm.state.value.model)
            assertFalse(vm.state.value.saved)
            assertEquals(0, store.commitCount.get())
            assertEquals("synthetic-old", store.credential!!.sealed.ciphertext)
        }
    }

    @Test
    fun newKeySameBindingReplacesCredential() {
        storedOpenAiCredential()
        withRealRepository { vm ->
            vm.onCredentialChanged("synthetic-key")
            vm.save(); advance()
            assertEquals(CredentialPresence.Present, vm.state.value.credentialPresence)
            assertEquals(listOf(sha256("synthetic-key")), vault.sealedDigests)
            assertFalse(vm.state.value.draftCredentialPending)
            assertTrue(vm.state.value.saved)
        }
    }

    @Test
    fun firstSaveWithoutKeyIsNotConfigured() {
        withRealRepository { vm ->
            vm.onEndpointChanged(openAiEndpoint)
            vm.onModelChanged("m1")
            vm.save(); advance()
            assertEquals(CredentialPresence.NotConfigured, vm.state.value.credentialPresence)
            assertNull(store.credential)
            assertTrue(vm.state.value.saved)
        }
    }

    @Test
    fun anthropicEndpointIsLoudlyUnsupported() {
        withRealRepository { vm ->
            vm.onEndpointChanged(anthropicEndpoint)
            vm.onModelChanged("m1")
            vm.save(); advance()
            assertEquals(ModelConfigCodes.PROVIDER_UNSUPPORTED, codeOf(vm.state.value.error!!))
            assertFalse(vm.state.value.saved)
            assertEquals(0, store.commitCount.get())
        }
    }

    @Test
    fun nonHttpsEndpointRejectedWithFixedCode() {
        withRealRepository { vm ->
            vm.onEndpointChanged("http://api.openai.com/v1/chat/completions")
            vm.onModelChanged("m1")
            vm.save(); advance()
            assertEquals(ModelConfigCodes.ENDPOINT_NOT_HTTPS, codeOf(vm.state.value.error!!))
            assertFalse(vm.state.value.saved)
        }
    }
    @Test
    fun reloadPublishesBusyBeforeDispatchAndRejectsSaveAndDraftDiscard() {
        withFake(savedSnapshot()) { vm, repo ->
            vm.onCredentialChanged("synthetic-pending")
            val epoch = vm.state.value.secretResetEpoch
            vm.reload()
            assertTrue(vm.state.value.busy)
            vm.discardDraftSecret()
            vm.cancel()
            vm.onCredentialChanged("must-not-replace")
            vm.save()
            assertTrue(vm.state.value.draftCredentialPending)
            assertEquals(epoch, vm.state.value.secretResetEpoch)
            advance()
            assertTrue(repo.commitDigests.isEmpty())
            assertFalse(vm.state.value.draftCredentialPending)
            assertEquals(epoch + 1L, vm.state.value.secretResetEpoch)
        }
    }
}

/** Private fake repository for the required VM-behaviour tests: records ONLY SHA-256
 * digests of committed keys (never the plaintext), and injects load/commit failures.
 * Depends on the ModelConfigRepository interface only — not on any parallel-owned fake
 * or on the parent-owned implementation details. */
private class FakeRepository(
    var snapshot: ModelConfigSnapshot = ModelConfigSnapshot(null, null)
) : ModelConfigRepository {
    val commitDigests = mutableListOf<String>()
    var commitFailure: Exception? = null

    override fun load(): ModelConfigSnapshot = snapshot

    override fun commit(draft: ModelConfigDraft): ModelProfile {
        commitFailure?.let { throw it }
        commitDigests.add(
            MessageDigest.getInstance("SHA-256")
                .digest(draft.newKey.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        )
        val presence = if (draft.newKey.isEmpty()) {
            snapshot.profile?.credentialPresence ?: CredentialPresence.NotConfigured
        } else {
            CredentialPresence.Present
        }
        return ModelProfile(draft.endpoint, draft.model, presence)
    }
}

/** Private minimal store for the real-repository integration tests: counts commits,
 * injects read/commit failures. No dependency on tests/FakeModelConfigStorage.kt. */
private class RecordingStore : ModelConfigStore {
    var profile: ModelProfile? = null
    var credential: CredentialRecord? = null
    var failCommit = false
    var failRead = false
    val commitCount = AtomicInteger(0)

    override fun readProfile(): ModelProfile? {
        if (failRead) throw java.io.IOException("simulated read failure")
        return profile
    }

    override fun readCredentialRecord(): CredentialRecord? = credential

    override fun commitAtomic(profile: ModelProfile, credential: CredentialRecord?) {
        if (failCommit) throw java.io.IOException("simulated commit failure")
        commitCount.incrementAndGet()
        this.profile = profile
        this.credential = credential
    }

    override fun clearCredentialMetadata() {
        credential = null
        profile = profile?.copy(credentialPresence = CredentialPresence.NotConfigured)
    }
}

/** Private minimal vault. Sealed payloads are recorded as SHA-256 DIGESTS only: the
 * plaintext never lands in any list, log or assertion output. */
private class StubVault : CredentialVault {
    override val purpose: String = ModelConfigBinding.DEFAULT_PURPOSE
    override val alias: String = ModelConfigBinding.DEFAULT_ALIAS
    override val version: Int = ModelConfigBinding.CREDENTIAL_VERSION
    val sealedDigests = mutableListOf<String>()

    override fun hasCredential(): Boolean = true

    override fun <T> withSecret(identity: EndpointIdentity, block: (String) -> T): T =
        throw ModelConfigException(ModelConfigCodes.VAULT_UNAVAILABLE, "not used by the VM")

    override fun seal(identity: EndpointIdentity, secret: String): SealedCredential {
        sealedDigests.add(
            MessageDigest.getInstance("SHA-256")
                .digest(secret.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        )
        return SealedCredential("iv-synthetic", "synthetic-sealed")
    }
}
