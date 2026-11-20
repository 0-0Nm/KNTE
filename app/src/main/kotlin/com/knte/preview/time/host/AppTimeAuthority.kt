package com.knte.preview.time.host

import android.content.Context
import com.knte.core.time.control.ConfirmResult
import com.knte.core.time.control.TimeControlView
import com.knte.core.time.projection.TimeRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Thin Android wrapper over [TimeAuthorityCore]. One process-wide instance; every surface
 *  uses its explicit owner token (page / monitor) so prepare/confirm/cancel are bound to
 *  the surface that issued them, and closing that surface closes its owner through the
 *  serialized reducer (see TimeAuthorityCore.closeOwner). No Activity/ViewModel is retained. */
class AppTimeAuthority private constructor(
    context: Context,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val core: TimeAuthorityCore = TimeAuthorityCore.open(
        ledgerRoot = File(context.filesDir.canonicalFile, "timetrack"),
        journalRoot = File(context.filesDir.canonicalFile, "isolated-time-intents-v1"),
        io = io,
    )

    val state: StateFlow<AuthorityState> get() = core.state

    suspend fun initialize() = core.initialize()
    suspend fun reload() = core.reload()
    suspend fun viewSnapshot() = core.viewSnapshot()
    suspend fun prepareStart(project: String, owner: String) = core.prepareStart(project, owner)
    suspend fun prepareStop(recordId: String, owner: String) = core.prepareStop(recordId, owner)
    suspend fun confirm(handle: OpaqueHostHandle, owner: String) = core.confirm(handle, owner)
    suspend fun cancel(handle: OpaqueHostHandle, owner: String) = core.cancel(handle, owner)
    suspend fun acknowledgeReview() = core.acknowledgeReview()
    suspend fun closeOwner(owner: String, reason: String) = core.closeOwner(owner, reason)
    fun closeOwnerDetached(owner: String, reason: String) = core.closeOwnerDetached(owner, reason)
    fun sessionOf(handle: OpaqueHostHandle): com.knte.core.time.control.SessionHandle? = core.sessionOf(handle)
    fun openOwner(owner: String) = core.openOwner(owner)
    fun ownedHandle(owner: String): OpaqueHostHandle? = core.ownedHandle(owner)
    fun setMonitor(value: Boolean) = core.setMonitor(value)
    fun currentView(): TimeControlView? = core.currentView()
    fun currentHandle(value: String): OpaqueHostHandle? = core.currentHandle(value)
    fun currentOpaqueHandle(): OpaqueHostHandle? = core.currentOpaqueHandle()
    fun records(): List<TimeRecord> = core.records()

    companion object {
        const val OWNER_PAGE = "page"
        const val OWNER_MONITOR = "monitor"

        @Volatile private var singleton: AppTimeAuthority? = null
        fun get(context: Context): AppTimeAuthority = singleton ?: synchronized(this) {
            singleton ?: AppTimeAuthority(context.applicationContext).also { singleton = it }
        }
    }
}

data class OpaqueHostHandle(val value: String)

fun OpaqueHostHandle.toPanelHandle() = com.knte.preview.time.overlay.OpaqueHandle(value)
