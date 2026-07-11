package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintJobDao {
    @Query("SELECT * FROM print_jobs WHERE id = 1")
    fun getLastPrintJobFlow(): Flow<PrintJob?>

    @Query("SELECT * FROM print_jobs WHERE id = 1")
    suspend fun getLastPrintJob(): PrintJob?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrintJob(printJob: PrintJob)

    @Query("DELETE FROM print_jobs")
    suspend fun clearPrintJobs()
}
