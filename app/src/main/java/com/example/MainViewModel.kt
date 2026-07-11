package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.PrintJob
import com.example.data.PrintJobRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: PrintJobRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = PrintJobRepository(database.printJobDao())
    }

    val lastPrintJob: StateFlow<PrintJob?> = repository.lastPrintJob
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun savePrintJob(localPath: String, mimeType: String, displayName: String) {
        viewModelScope.launch {
            repository.savePrintJob(
                PrintJob(
                    localFilePath = localPath,
                    mimeType = mimeType,
                    displayName = displayName,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun clearPrintJob() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }
}
