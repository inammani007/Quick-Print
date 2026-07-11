package com.example.data

import kotlinx.coroutines.flow.Flow

class PrintJobRepository(private val printJobDao: PrintJobDao) {
    val lastPrintJob: Flow<PrintJob?> = printJobDao.getLastPrintJobFlow()

    suspend fun getLastJob(): PrintJob? = printJobDao.getLastPrintJob()

    suspend fun savePrintJob(printJob: PrintJob) = printJobDao.insertPrintJob(printJob)

    suspend fun clearAll() = printJobDao.clearPrintJobs()
}
