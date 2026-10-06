package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_history")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val uid: String,
    val pagesData: String, // format "00:34D7DFB4\n01:A91A6978"
    val timestamp: Long = System.currentTimeMillis(),
    val tagType: String
)
