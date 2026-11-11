package com.knte.core.notes

/** Synchronous IO contract; Android callers must dispatch it off the main thread.
 * No delete, live-Vault access, credentials, network, merge or migration capability. */
interface NoteRepository {
    fun list(): List<Note>
    fun get(id: String): Note?
    fun create(title: String, body: String, nowMillis: Long): Note
    fun update(id: String, expectedRevision: String, title: String, body: String, nowMillis: Long): Note
}
