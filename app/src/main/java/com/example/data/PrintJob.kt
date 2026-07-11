package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "print_jobs")
data class PrintJob(
    @PrimaryKey val id: Int = 1,
    val localFilePath: String,
    val mimeType: String,
    val displayName: String,
    val timestamp: Long = System.currentTimeMillis()
)
