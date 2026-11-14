package com.knte.preview

import android.os.Bundle
import com.knte.preview.modelconfig.*
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import com.knte.preview.source.*
import com.knte.preview.reading.*
import com.knte.preview.habit.HabitViewModel
import com.knte.preview.reference.ReferenceViewModel
import com.knte.core.reference.store.ReferenceStoreApi
import com.knte.core.habit.HabitLedgerApi
import com.knte.core.habit.HabitLedgerStore
import com.knte.core.reading.position.FileReadingPositionStore
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
import com.knte.preview.time.host.AppTimeAuthority
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
        val timeAuthority = AppTimeAuthority.get(applicationContext)
        val timeFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(TimeViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return TimeViewModel(timeAuthority, extras.createSavedStateHandle()) as T
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
        val sourceEngine = ReadOnlyDiarySourceImpl(AndroidSourceDocumentProvider(contentResolver))
        val sourcePrefs = AndroidSourcePreferences(applicationContext)
        val sourceFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(SourceViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return SourceViewModel(sourceEngine, sourcePrefs) as T
            }
        }
        // Registered before STARTED; callback runs only after an explicit system picker result.
        val sourceHolder = ViewModelProvider(this, sourceFactory)[SourceViewModel::class.java]
        val chooseSource = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uri = data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                try {
                    check((data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    sourceHolder.select(SourceRoot.bind(uri.toString(),
                        DocumentsContract.getTreeDocumentId(uri), SourcePermission.Granted))
                } catch (_: Exception) { sourceHolder.selectionFailed() }
            }
        }
        val readingPositions by lazy {
            FileReadingPositionStore(File(filesDir.canonicalFile, "reading-positions-v1"))
        }
        val readingFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(ReadingViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return ReadingViewModel(AndroidBookLoader(contentResolver), { readingPositions }) as T
            }
        }
        val readingHolder = ViewModelProvider(this, readingFactory)[ReadingViewModel::class.java]
        val chooseBook = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uri = data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                if ((data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    readingHolder.select(uri.toString()) else readingHolder.selectionFailed()
            } else readingHolder.selectionFailed()
        }
        val habitStorage by lazy {
            HabitLedgerApi(HabitLedgerStore.open(File(filesDir.canonicalFile, "isolated-habit-contract-v1")))
        }
        val habitFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(HabitViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return HabitViewModel({ habitStorage }, extras.createSavedStateHandle()) as T
            }
        }
        val referenceFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(ReferenceViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return ReferenceViewModel({ ReferenceStoreApi.open(
                    File(filesDir.canonicalFile, "isolated-reference-contract-v1").toPath()) },
                    extras.createSavedStateHandle()) as T
            }
        }
        // Dedicated new-app preferences only; lazy construction occurs in VM IO calls.
        // No legacy credential migration, transport, connection test or Internet permission.
        val modelAppContext = applicationContext
        val modelRepository by lazy {
            val store = AndroidModelConfigStore(modelAppContext.getSharedPreferences(
                "model-config-v3", android.content.Context.MODE_PRIVATE))
            AndroidModelConfigRepository(store, AndroidKeystoreVault(store))
        }
        val modelFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(ModelConfigViewModel::class.java))
                val deferred = object : ModelConfigRepository {
                    override fun load(): ModelConfigSnapshot = modelRepository.load()
                    override fun commit(draft: ModelConfigDraft): ModelProfile = modelRepository.commit(draft)
                }
                @Suppress("UNCHECKED_CAST")
                return ModelConfigViewModel(deferred) as T
            }
        }
        setContent {
            val stateHolder: NoteViewModel = viewModel(factory = factory)
            val diaryHolder: DiaryViewModel = viewModel(factory = diaryFactory)
            val timeHolder: TimeViewModel = viewModel(factory = timeFactory)
            val accountingHolder: AccountingViewModel = viewModel(factory = accountingFactory)
            val habitHolder: HabitViewModel = viewModel(factory = habitFactory)
            val referenceHolder: ReferenceViewModel = viewModel(factory = referenceFactory)
            val modelHolder: ModelConfigViewModel = viewModel(factory = modelFactory)
            ProductRoot(stateHolder, diaryHolder, timeHolder, accountingHolder, sourceHolder,
                chooseSource = {
                    chooseSource.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                }, reading = readingHolder, chooseBook = {
                    chooseBook.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("text/plain","text/markdown","text/x-markdown")))
                }, habit = habitHolder, reference = referenceHolder, modelConfig = modelHolder)
        }
    }
}
