package com.knte.core.notes

/** App-private preview record; not a Vault diary or a formal data-source migration. */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val revision: String,
)
