package com.example.data.repository

import com.example.data.database.ScanDao
import com.example.data.database.ScanEntity
import kotlinx.coroutines.flow.Flow

class ScanRepository(private val scanDao: ScanDao) {
    val allScans: Flow<List<ScanEntity>> = scanDao.getAllScans()

    suspend fun insertScan(scan: ScanEntity): Long {
        return scanDao.insertScan(scan)
    }

    suspend fun deleteScanById(id: Int) {
        scanDao.deleteScanById(id)
    }

    suspend fun clearHistory() {
        scanDao.clearHistory()
    }
}
