package com.knte.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knte.core.notes.Note
import com.knte.core.notes.NoteRepository
import com.knte.core.notes.FileNoteRepository
import com.knte.core.diary.IsolatedDiaryRepository
import com.knte.preview.diary.DiaryViewModel
import com.knte.preview.time.TimeViewModel
import com.knte.preview.accounting.AccountingViewModel
import com.knte.core.accounting.IsolatedAccountingLedger
import com.knte.core.time.repository.IsolatedTimeRepository
import com.knte.core.time.repository.TimeLedgerApi
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
        val isolatedStorage by lazy {
            IsolatedDiaryRepository(File(filesDir.canonicalFile, "isolated-diary-contract-v1"))
        }
        val diaryFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(DiaryViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return DiaryViewModel({ isolatedStorage }, extras.createSavedStateHandle()) as T
            }
        }
        val timeStorage by lazy {
            TimeLedgerApi(IsolatedTimeRepository.open(File(filesDir.canonicalFile, "isolated-time-contract-v1")))
        }
        val timeFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(TimeViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return TimeViewModel({ timeStorage }, extras.createSavedStateHandle()) as T
            }
        }
        val accountingStorage by lazy {
            IsolatedAccountingLedger(File(filesDir.canonicalFile, "isolated-accounting-contract-v1"))
        }
        val accountingFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(AccountingViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return AccountingViewModel({ accountingStorage }, extras.createSavedStateHandle()) as T
            }
        }
        setContent {
            val stateHolder: NoteViewModel = viewModel(factory = factory)
            val diaryHolder: DiaryViewModel = viewModel(factory = diaryFactory)
            val timeHolder: TimeViewModel = viewModel(factory = timeFactory)
            val accountingHolder: AccountingViewModel = viewModel(factory = accountingFactory)
            ProductRoot(stateHolder, diaryHolder, timeHolder, accountingHolder)
        }
    }
}
