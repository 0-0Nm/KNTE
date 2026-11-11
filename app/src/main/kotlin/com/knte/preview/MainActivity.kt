package com.knte.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knte.core.notes.Note
import com.knte.core.notes.NoteRepository
import com.knte.core.notes.FileNoteRepository
import com.knte.preview.ui.NotesScreen
import java.io.File

/** Separate app ID and private folder. Never opens the old app or any external Vault. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Construct the storage on its first IO-dispatched operation, not in Activity.onCreate.
        val repository = object : NoteRepository {
            private val storage by lazy { FileNoteRepository(File(filesDir.canonicalFile, "preview-notes-v1")) }
            override fun list(): List<Note> = storage.list()
            override fun get(id: String): Note? = storage.get(id)
            override fun create(title: String, body: String, nowMillis: Long): Note =
                storage.create(title, body, nowMillis)
            override fun update(id: String, expectedRevision: String, title: String, body: String, nowMillis: Long): Note =
                storage.update(id, expectedRevision, title, body, nowMillis)
        }
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(NoteViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return NoteViewModel(repository, extras.createSavedStateHandle()) as T
            }
        }
        setContent {
            MaterialTheme {
                val stateHolder: NoteViewModel = viewModel(factory = factory)
                NotesScreen(stateHolder)
            }
        }
    }
}
