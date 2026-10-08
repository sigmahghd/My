package com.example.ui.viewmodel

import android.app.Application
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.ScanEntity
import com.example.data.repository.ScanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface NfcTagConnection {
    fun connect()
    fun close()
    fun readPages(pageOffset: Int): ByteArray
    fun writePage(pageOffset: Int, data: ByteArray)
    fun getTypeName(): String
    fun authenticate(password: ByteArray): ByteArray?
    fun magicWakeup(): Boolean
    fun transceiveRaw(data: ByteArray): ByteArray?
    /**
     * FAST_READ (0x3A): reads pages [startPage..endPage] inclusive in a single transceive,
     * returning (endPage - startPage + 1) * 4 bytes. Returns null if unsupported/failed.
     */
    fun fastRead(startPage: Int, endPage: Int): ByteArray?
}

class MifareUltralightConnection(private val mifare: MifareUltralight) : NfcTagConnection {
    override fun connect() = mifare.connect()
    override fun close() = mifare.close()
    override fun readPages(pageOffset: Int): ByteArray = mifare.readPages(pageOffset)
    override fun writePage(pageOffset: Int, data: ByteArray) = mifare.writePage(pageOffset, data)
    override fun getTypeName(): String = when (mifare.type) {
        MifareUltralight.TYPE_ULTRALIGHT -> "Mifare Ultralight"
        MifareUltralight.TYPE_ULTRALIGHT_C -> "Mifare Ultralight C"
        else -> "Mifare Ultralight (EV1/NTAG)"
    }
    override fun authenticate(password: ByteArray): ByteArray? {
        val cmd = ByteArray(5)
        cmd[0] = 0x1B.toByte()
        System.arraycopy(password, 0, cmd, 1, 4)
        return try {
            mifare.transceive(cmd)
        } catch (e: Exception) {
            null
        }
    }
    override fun magicWakeup(): Boolean = false
    override fun transceiveRaw(data: ByteArray): ByteArray? {
        return try {
            mifare.transceive(data)
        } catch (e: Exception) {
            null
        }
    }
    override fun fastRead(startPage: Int, endPage: Int): ByteArray? {
        return try {
            mifare.transceive(byteArrayOf(0x3A, startPage.toByte(), endPage.toByte()))
        } catch (e: Exception) {
            null
        }
    }
}

class NfcAConnection(private val nfcA: NfcA) : NfcTagConnection {
    override fun connect() = nfcA.connect()
    override fun close() = nfcA.close()

    override fun readPages(pageOffset: Int): ByteArray {
        val command = byteArrayOf(0x30.toByte(), pageOffset.toByte())
        return nfcA.transceive(command)
    }

    override fun writePage(pageOffset: Int, data: ByteArray) {
        require(data.size == 4) { "Mifare Ultralight page write requires exactly 4 bytes" }
        val command = ByteArray(6)
        command[0] = 0xA2.toByte()
        command[1] = pageOffset.toByte()
        System.arraycopy(data, 0, command, 2, 4)
        nfcA.transceive(command)
    }

    override fun getTypeName(): String {
        val sak = nfcA.sak.toInt() and 0xFF
        val atqaBytes = nfcA.atqa
        val atqa = if (atqaBytes.size >= 2) {
            ((atqaBytes[0].toInt() and 0xFF) shl 8) or (atqaBytes[1].toInt() and 0xFF)
        } else 0
        return "NfcA / Mifare UL (SAK: 0x${Integer.toHexString(sak).uppercase(Locale.ROOT)}, ATQA: 0x${Integer.toHexString(atqa).uppercase(Locale.ROOT)})"
    }

    override fun authenticate(password: ByteArray): ByteArray? {
        val cmd = ByteArray(5)
        cmd[0] = 0x1B.toByte()
        System.arraycopy(password, 0, cmd, 1, 4)
        return try {
            nfcA.transceive(cmd)
        } catch (e: Exception) {
            null
        }
    }

    override fun magicWakeup(): Boolean {
        try {
            // 1. HLTA
            try { nfcA.transceive(byteArrayOf(0x50, 0x00)) } catch (_: Exception) {}
            // 2. WAKEUP (7-битная команда, на многих чипах работает передача как 8-бит)
            val resp1 = nfcA.transceive(byteArrayOf(0x40))
            if (resp1.isEmpty() || resp1[0] != 0x0A.toByte()) return false
            // 3. SELECT
            val resp2 = nfcA.transceive(byteArrayOf(0x43))
            if (resp2.isEmpty() || resp2[0] != 0x0A.toByte()) return false
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun transceiveRaw(data: ByteArray): ByteArray? {
        return try {
            nfcA.transceive(data)
        } catch (e: Exception) {
            null
        }
    }

    override fun fastRead(startPage: Int, endPage: Int): ByteArray? {
        return try {
            nfcA.transceive(byteArrayOf(0x3A, startPage.toByte(), endPage.toByte()))
        } catch (e: Exception) {
            null
        }
    }
}

enum class AppMode {
    READ, WRITE
}

/**
 * Transient state of a long-running read/write operation, kept in its own flow so progress
 * ticks only recompose the progress indicator, never the whole screen or the dump list.
 */
data class OperationState(
    val isOperating: Boolean = false,
    val progress: Float = 0f
)

data class ScanUiState(
    val pages: Map<Int, String> = emptyMap(),
    val uid: String = "",
    val mode: AppMode = AppMode.READ,
    val infoText: String = "Готов к сканированию",
    val tagType: String = "Неизвестно",
    val maxPages: Int = 16,
    val writeSystemPages: Boolean = false, // If true, write pages 0-3 (Requires Magic Tag)
    val readTotalPages: String = "",
    val isDarkTheme: Boolean = false,
    val passwordHex: String = "00000000",
    val enableLockBypass: Boolean = false,
    val writeCfgPages: Boolean = true
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ScanRepository
    val historyScans: StateFlow<List<ScanEntity>>

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    // Logs live in their own flow. Appending a line no longer copies the whole UI state,
    // and background NFC code can log without hopping to the main thread.
    private val _logs = MutableStateFlow(
        listOf(
            "Журнал",
            "Read mode: tap tag",
            "======================"
        )
    )
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // Operation progress/activity in its own flow (see OperationState).
    private val _operation = MutableStateFlow(OperationState())
    val operation: StateFlow<OperationState> = _operation.asStateFlow()

    private val prefs = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ScanRepository(database.scanDao())
        historyScans = repository.allScans.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Load settings from preferences
        val savedDark = prefs.getBoolean("dark_theme", false)
        val savedWriteSystem = prefs.getBoolean("write_system_pages", false)
        val savedLockBypass = prefs.getBoolean("enable_lock_bypass", false)
        val savedWriteCfgPages = prefs.getBoolean("write_cfg_pages", true)
        _uiState.update {
            it.copy(
                isDarkTheme = savedDark,
                writeSystemPages = savedWriteSystem,
                enableLockBypass = savedLockBypass,
                writeCfgPages = savedWriteCfgPages
            )
        }

        // Initialize empty dump database (e.g. 44 pages as standard)
        initializeEmptyDump(44)
    }

    fun toggleDarkTheme(enable: Boolean) {
        prefs.edit().putBoolean("dark_theme", enable).apply()
        _uiState.update { it.copy(isDarkTheme = enable) }
    }

    fun toggleLockBypass(enable: Boolean) {
        prefs.edit().putBoolean("enable_lock_bypass", enable).apply()
        _uiState.update { it.copy(enableLockBypass = enable) }
        log("Обход лок-байтов (Lock-Bait Bypass): ${if (enable) "ВКЛЮЧЕН" else "ВЫКЛЮЧЕН"}")
    }

    fun toggleWriteCfgPages(enable: Boolean) {
        prefs.edit().putBoolean("write_cfg_pages", enable).apply()
        _uiState.update { it.copy(writeCfgPages = enable) }
        log("Запись стр. 29-2B (CFG/PWD): ${if (enable) "ВКЛЮЧЕНА" else "ВЫКЛЮЧЕНА"}")
    }

    fun initializeEmptyDump(count: Int) {
        val emptyMap = HashMap<Int, String>(count)
        for (i in 0 until count) {
            emptyMap[i] = "00000000"
        }
        _uiState.update {
            it.copy(
                pages = emptyMap,
                maxPages = count,
                uid = "00000000000000",
                tagType = "MF0 UL11/UL21/C"
            )
        }
    }

    fun updatePassword(newPasswordHex: String) {
        val cleaned = newPasswordHex.uppercase(Locale.ROOT).filter { it in "0123456789ABCDEF" }
        val finalPwd = cleaned.take(8).padEnd(8, '0')
        _uiState.update { it.copy(passwordHex = finalPwd) }
        log("Криптоключ (пароль PWD) изменен на: $finalPwd")
    }

    fun setMode(mode: AppMode) {
        val cleanLogs = mutableListOf("Журнал")
        cleanLogs.add(if (mode == AppMode.READ) "Read mode: tap tag" else "Write mode: tap tag")
        cleanLogs.add("======================")
        _logs.value = cleanLogs
        _uiState.update { it.copy(mode = mode, readTotalPages = "") }
    }

    fun toggleWriteSystemPages(enable: Boolean) {
        prefs.edit().putBoolean("write_system_pages", enable).apply()
        _uiState.update { it.copy(writeSystemPages = enable) }
        log("Системные страницы 0-3 при записи: ${if (enable) "ВКЛЮЧЕНЫ" else "ВЫКЛЮЧЕНЫ"}")
    }

    fun updatePage(pageIndex: Int, hexData: String) {
        // Validation: Must be up to 8 hex chars
        val cleaned = hexData.uppercase(Locale.ROOT).filter { it in "0123456789ABCDEF" }
        val finalHex = cleaned.take(8).padEnd(8, '0')

        _uiState.update { state ->
            val updatedPages = state.pages.toMutableMap()
            updatedPages[pageIndex] = finalHex

            // Auto BCC Recalculation
            try {
                if (pageIndex == 0 || pageIndex == 1) {
                    val p0 = updatedPages[0]?.let { hexToBytes(it) } ?: byteArrayOf(0, 0, 0, 0)
                    val p1 = updatedPages[1]?.let { hexToBytes(it) } ?: byteArrayOf(0, 0, 0, 0)
                    val p2 = updatedPages[2]?.let { hexToBytes(it) } ?: byteArrayOf(0, 0, 0, 0)

                    // Recalculate BCC0 (page 0 byte 3)
                    val bcc0 = (0x88.toByte().toInt() xor p0[0].toInt() xor p0[1].toInt() xor p0[2].toInt()).toByte()
                    p0[3] = bcc0
                    updatedPages[0] = bytesToHex(p0)

                    // Recalculate BCC1 (page 2 byte 0)
                    val bcc1 = (p1[0].toInt() xor p1[1].toInt() xor p1[2].toInt() xor p1[3].toInt()).toByte()
                    p2[0] = bcc1
                    updatedPages[2] = bytesToHex(p2)
                }
            } catch (_: Exception) {}

            state.copy(pages = updatedPages)
        }
    }

    fun loadScan(scan: ScanEntity) {
        val pagesMap = mutableMapOf<Int, String>()
        val lines = scan.pagesData.split("\n")
        var maxIdx = 0
        for (line in lines) {
            val parts = line.split(":")
            if (parts.size == 2) {
                val idx = parts[0].trim().toIntOrNull()
                val hex = parts[1].trim().uppercase(Locale.ROOT)
                if (idx != null) {
                    pagesMap[idx] = hex
                    if (idx > maxIdx) {
                        maxIdx = idx
                    }
                }
            }
        }

        val actualCount = maxIdx + 1
        _uiState.update { state ->
            state.copy(
                pages = pagesMap,
                maxPages = actualCount,
                uid = scan.uid,
                tagType = scan.tagType,
                readTotalPages = "Загружено из истории: $actualCount стр."
            )
        }

        log("Загружен дамп из истории:")
        log("UID: ${scan.uid}")
        log("Спецификация: ${scan.tagType}")
        log("Режим: Редактирование")
    }

    fun clearDump() {
        initializeEmptyDump(_uiState.value.maxPages)
        log("Дамп очищен.")
    }

    fun deleteHistoryId(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.deleteScanById(id)
            } catch (e: Exception) {
                log("Ошибка удаления: ${e.localizedMessage}")
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.clearHistory()
                log("История очищена.")
            } catch (e: Exception) {
                log("Ошибка очистки истории: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Thread-safe log append. Safe to call from any dispatcher; the UI collects [logs] on main.
     * Caps history at 200 lines (trimmed to the latest 150 plus the header) to avoid bloat.
     */
    fun log(message: String) {
        _logs.update { current ->
            val list = current.toMutableList()
            list.add(message)
            if (list.size > 200) {
                val header = list.firstOrNull() ?: "Журнал"
                val truncated = list.takeLast(150).toMutableList()
                if (truncated.firstOrNull() != header) {
                    truncated.add(0, header)
                }
                truncated
            } else {
                list
            }
        }
    }

    private fun startOperation() {
        _operation.value = OperationState(isOperating = true, progress = 0f)
    }

    private fun finishOperation(finalProgress: Float = 1f) {
        _operation.value = OperationState(isOperating = false, progress = finalProgress)
    }

    /**
     * Throttled progress emit: only publishes when the whole-percent value changes (or at 100%),
     * cutting hundreds of emissions on large tags down to at most ~100.
     */
    private fun emitProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        val cur = _operation.value
        if (!cur.isOperating) return
        if ((clamped * 100).toInt() != (cur.progress * 100).toInt() || clamped >= 1f) {
            _operation.value = cur.copy(progress = clamped)
        }
    }

    fun processNfcTag(tag: Tag) {
        val currentMode = _uiState.value.mode
        val connection: NfcTagConnection? = try {
            when {
                MifareUltralight.get(tag) != null -> MifareUltralightConnection(MifareUltralight.get(tag))
                NfcA.get(tag) != null -> NfcAConnection(NfcA.get(tag))
                else -> null
            }
        } catch (e: Exception) {
            log("Ошибка инициализации тега: ${e.localizedMessage}")
            null
        }

        if (connection == null) {
            log("Ошибка: Тег не поддерживает технологию NFC-A / Mifare Ultralight")
            return
        }

        val uidBytes = tag.id
        val uidHex = if (uidBytes != null) bytesToHex(uidBytes) else "00000000000000"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (currentMode == AppMode.READ) {
                    performRead(connection, uidHex)
                } else {
                    performWrite(connection, uidHex)
                }
            } catch (e: Exception) {
                log("Критическая ошибка работы с тегом: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun performRead(connection: NfcTagConnection, uidHex: String) {
        startOperation()
        log("Режим: Читать")
        log("Технология: [NfcA / MifareUltralight]")
        log("UID (поверхностный): $uidHex")

        val pagesMap = mutableMapOf<Int, String>()
        var successPages = 0
        var totalReadPages = 44 // default guess, will expand dynamically

        try {
            connection.connect()
            val typeVal = connection.getTypeName()
            log("Тип чипа: $typeVal")

            // GetVersion to determine exact specification and page sizes
            var detectedMaxPages = 44
            var detectedType = "Mifare Ultralight"
            try {
                // Command: GetVersion (0x60)
                val versionBytes = connection.transceiveRaw(byteArrayOf(0x60))
                if (versionBytes != null && versionBytes.size >= 8) {
                    val storageSize = versionBytes[6].toInt() and 0xFF
                    val productType = versionBytes[1].toInt() and 0xFF
                    val productSubtype = versionBytes[2].toInt() and 0xFF

                    if (productType == 0x04) {
                        if (productSubtype == 0x01) {
                            detectedType = "Mifare Ultralight EV1"
                        } else if (productSubtype == 0x02) {
                            detectedType = "NTAG21x"
                        }
                    }

                    when (storageSize) {
                        0x0B -> {
                            detectedMaxPages = 44
                            detectedType += " (136B)"
                        }
                        0x0E -> {
                            detectedMaxPages = 135
                            detectedType += " (504B / NTAG215)"
                        }
                        0x11 -> {
                            detectedMaxPages = 231
                            detectedType += " (888B / NTAG216)"
                        }
                        else -> {
                            detectedMaxPages = if (storageSize > 0) storageSize * 8 else 44
                        }
                    }
                    log("GetVersion (0x60): УСПЕШНО. Обнаружен: $detectedType ($detectedMaxPages стр.)")
                    totalReadPages = detectedMaxPages
                } else {
                    log("GetVersion (0x60) не поддерживается. Будем использовать автоопределение.")
                }
            } catch (e: Exception) {
                // Keep default
            }

            // Authentication with password (if configured)
            val pwdHex = _uiState.value.passwordHex
            if (pwdHex.isNotEmpty() && pwdHex != "00000000") {
                log("Авторизация: попытка с PWD: $pwdHex")
                val pwdBytes = hexToBytes(pwdHex)
                if (pwdBytes.size == 4) {
                    val pack = connection.authenticate(pwdBytes)
                    if (pack != null) {
                        log("Авторизация УСПЕШНА. PACK: ${bytesToHex(pack)}")
                    } else {
                        log("Авторизация не пройдена или не требуется для чтения")
                    }
                }
            }

            // Fast path: FAST_READ (0x3A) pulls many pages per transceive. We request in
            // chunks to stay within the tag's transceive buffer, and fall back to the
            // classic 4-page READ loop if the chip does not support FAST_READ.
            val fastReadChunk = 60 // pages per FAST_READ request (<= 240 bytes response)
            var fastReadUsed = false
            run {
                var start = 0
                while (start < totalReadPages) {
                    val end = (start + fastReadChunk - 1).coerceAtMost(totalReadPages - 1)
                    val data = connection.fastRead(start, end)
                    val expectedBytes = (end - start + 1) * 4
                    if (data == null || data.size < expectedBytes) {
                        // Unsupported or short response: abandon fast path entirely.
                        if (start == 0) fastReadUsed = false
                        break
                    }
                    fastReadUsed = true
                    for (p in start..end) {
                        val off = (p - start) * 4
                        pagesMap[p] = bytesToHex(data.sliceArray(off until off + 4))
                    }
                    successPages = end + 1
                    start = end + 1
                    emitProgress(start.toFloat() / totalReadPages)
                }
            }

            // Fallback (or completion) via 4-page READ blocks (16 bytes each).
            if (!fastReadUsed) {
                successPages = 0
                pagesMap.clear()
                for (i in 0 until totalReadPages step 4) {
                    emitProgress(i.toFloat() / totalReadPages)
                    try {
                        val data = connection.readPages(i)
                        if (data != null && data.size >= 16) {
                            pagesMap[i] = bytesToHex(data.sliceArray(0..3))
                            pagesMap[i + 1] = bytesToHex(data.sliceArray(4..7))
                            pagesMap[i + 2] = bytesToHex(data.sliceArray(8..11))
                            pagesMap[i + 3] = bytesToHex(data.sliceArray(12..15))
                            successPages = i + 4
                        } else {
                            break
                        }
                    } catch (e: Exception) {
                        // Out of bounds or lock hit
                        break
                    }
                }
            }

            log(if (fastReadUsed) "Чтение: FAST_READ (0x3A) — ускоренный режим" else "Чтение: стандартный READ (0x30)")

            // Connection is closed in the finally block to avoid a double close.

            if (successPages > 0) {
                totalReadPages = successPages

                // Extract exact 7-byte UID from Page 0 and Page 1
                val p0 = pagesMap[0] ?: ""
                val p1 = pagesMap[1] ?: ""
                val exactUidHex = if (p0.length >= 6 && p1.length >= 8) {
                    p0.substring(0, 6) + p1.substring(0, 8)
                } else {
                    uidHex
                }

                // Save to history automatically
                val simpleDateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                val itemTitle = "Дамп $exactUidHex (${simpleDateFormat.format(Date())})"

                val entity = ScanEntity(
                    title = itemTitle,
                    uid = exactUidHex,
                    pagesData = buildString {
                        for (i in 0 until totalReadPages) {
                            val hexVal = pagesMap[i] ?: "00000000"
                            append("$i:$hexVal\n")
                        }
                    }.trim(),
                    tagType = if (totalReadPages <= 16) "MF0 UL11 (16 стр)" else "EV1/NTAG ($totalReadPages стр)"
                )
                repository.insertScan(entity)

                _uiState.update { state ->
                    state.copy(
                        pages = pagesMap,
                        uid = exactUidHex,
                        maxPages = totalReadPages,
                        tagType = entity.tagType,
                        readTotalPages = "Read completed: $totalReadPages/$totalReadPages (100%)"
                    )
                }
                log("Успешно считано $totalReadPages страниц")
                log("Реальный 7-байтный UID: $exactUidHex")
                log("Дамп автоматически записан в историю.")
            } else {
                log("Ошибка: Не удалось прочитать данные с чипа")
            }

        } catch (e: Exception) {
            log("Ошибка подключения к чипу: ${e.localizedMessage}")
        } finally {
            finishOperation()
            try {
                connection.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun performWrite(connection: NfcTagConnection, uidHex: String) {
        val state = _uiState.value
        val pagesToWrite = state.pages
        val writeSystem = state.writeSystemPages

        startOperation()

        log("Режим: Запись")
        log("Телеметрия чипа перед записью...")
        log("UID цели: $uidHex")

        try {
            connection.connect()

            // Authentication with password (if configured)
            val pwdHex = state.passwordHex
            if (pwdHex.isNotEmpty() && pwdHex != "00000000") {
                log("Авторизация: попытка с PWD: $pwdHex")
                val pwdBytes = hexToBytes(pwdHex)
                if (pwdBytes.size == 4) {
                    val pack = connection.authenticate(pwdBytes)
                    if (pack != null) {
                        log("Авторизация УСПЕШНА. PACK: ${bytesToHex(pack)}")
                    } else {
                        log("Вызов PWD авторизации перед записью...")
                    }
                }
            }

            // Start writing page by page
            val startPage = if (writeSystem) 0 else 4
            val endPage = state.maxPages

            log("Спектр записи: стр. $startPage - ${endPage - 1}")

            // Lock-Bait Bypass detection & implementation (for pages 4-6) if enabled
            var originalPage2Bytes: ByteArray? = null
            var hasLockBypass = false
            if (state.enableLockBypass) {
                try {
                    // Read original page 2 (bytes 8-11 of pages 0-3 block)
                    val page2Data = connection.readPages(0)
                    if (page2Data.size >= 12) {
                        originalPage2Bytes = page2Data.sliceArray(8..11)
                    }
                } catch (e: Exception) {
                    log("Внимание: оригинальная стр.02 не прочитана: ${e.localizedMessage}")
                }

                hasLockBypass = (originalPage2Bytes != null)
                if (hasLockBypass) {
                    try {
                        // 1. Wakeup
                        val wOk = connection.magicWakeup()
                        if (wOk) {
                            log("Magic Wakeup: УСПЕШНО")
                        }
                        // 2. Write page 2 with bypass: 00 00, bytes 2-3
                        val bypass02 = byteArrayOf(0x00, 0x00, originalPage2Bytes!![2], originalPage2Bytes!![3])
                        connection.writePage(2, bypass02)
                        log("Lock-Bait обход: блокировка временно снята")
                    } catch (e: Exception) {
                        log("Ошибка Lock-Bait обхода: ${e.localizedMessage}")
                    }
                }
            }

            var writtenCount = 0
            val totalToWrite = endPage - startPage
            // Track pages we actually wrote and the exact bytes intended, for post-write verification.
            val writtenPages = LinkedHashMap<Int, ByteArray>()

            for (pageIdx in startPage until endPage) {
                emitProgress((pageIdx - startPage).toFloat() / totalToWrite)

                // Check if we should skip configuration/password pages 0x29, 0x2A, 0x2B (41, 42, 43 decimal)
                if (!state.writeCfgPages && (pageIdx == 41 || pageIdx == 42 || pageIdx == 43)) {
                    continue
                }

                val hexString = pagesToWrite[pageIdx] ?: "00000000"
                val dataToWrite = hexToBytes(hexString)

                try {
                    connection.writePage(pageIdx, dataToWrite)
                    writtenCount++
                    writtenPages[pageIdx] = dataToWrite
                } catch (e: Exception) {
                    log("Ошибка записи стр. " + String.format(Locale.ROOT, "%02X", pageIdx) + ": ${e.localizedMessage}")
                }
            }

            // --- Verification pass: read back written pages and compare against intended bytes ---
            val verified = verifyWrittenPages(connection, writtenPages)

            // Restore original Lock Bytes (page 2) to lock backdoor/unlock status before final close!
            if (state.enableLockBypass && hasLockBypass && originalPage2Bytes != null) {
                try {
                    connection.magicWakeup()
                    connection.writePage(2, originalPage2Bytes)
                    log("Lock-Bait обход: блокировка восстановлена")
                } catch (e: Exception) {
                    log("Внимание: Lock-Bait не восстановлен: ${e.localizedMessage}")
                }
            }

            emitProgress(1.0f)
            // Connection is closed in the finally block to avoid a double close.

            log("Запись завершена!")
            log("Записано страниц: $writtenCount из $totalToWrite")
            when (verified) {
                null -> {
                    log("⚠️ Верификация не выполнена (не удалось прочитать страницы обратно)")
                    _uiState.update { it.copy(readTotalPages = "Write completed: $writtenCount/$totalToWrite (не проверено)") }
                }
                writtenCount -> {
                    log("✅ Верификация пройдена: все $verified стр. совпадают с дампом")
                    _uiState.update { it.copy(readTotalPages = "Write OK: $writtenCount/$totalToWrite (verified $verified)") }
                }
                else -> {
                    val mismatched = writtenCount - verified
                    log("❌ Верификация: несовпадений — $mismatched из $writtenCount (подробности выше)")
                    _uiState.update { it.copy(readTotalPages = "Write MISMATCH: $mismatched/$writtenCount стр.") }
                }
            }

        } catch (e: Exception) {
            log("Ошибка подключения: ${e.localizedMessage}")
        } finally {
            finishOperation()
            try {
                connection.close()
            } catch (ignored: Exception) {}
        }
    }

    fun exportDumpToUri(contentResolver: android.content.ContentResolver, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val max = state.maxPages
                val bytes = ByteArray(max * 4)
                for (i in 0 until max) {
                    val hex = state.pages[i] ?: "00000000"
                    val pageBytes = hexToBytes(hex)
                    System.arraycopy(pageBytes, 0, bytes, i * 4, 4)
                }
                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                }
                log("Успешный экспорт дампа: ${max * 4} байт")
            } catch (e: Exception) {
                log("Ошибка экспорта дампа: ${e.localizedMessage}")
            }
        }
    }

    fun importDumpFromUri(contentResolver: android.content.ContentResolver, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val totalBytes = bytes.size
                    if (totalBytes == 0 || totalBytes % 4 != 0) {
                        log("Ошибка импорта: размер файла должен быть кратен 4 байтам (получено $totalBytes байт)")
                        return@use
                    }
                    val pagesCount = totalBytes / 4
                    val pagesMap = mutableMapOf<Int, String>()
                    for (i in 0 until pagesCount) {
                        val pageBytes = bytes.sliceArray((i * 4) until (i * 4 + 4))
                        pagesMap[i] = bytesToHex(pageBytes)
                    }

                    // Also obtain a 7-byte UID representation from first 2 pages if available
                    val p0 = pagesMap[0] ?: "00000000"
                    val p1 = pagesMap[1] ?: "00000000"
                    val importedUid = if (p0.length >= 6 && p1.length >= 8) {
                        p0.substring(0, 6) + p1.substring(0, 8)
                    } else {
                        "00000000000000"
                    }

                    _uiState.update { state ->
                        state.copy(
                            pages = pagesMap,
                            maxPages = pagesCount,
                            uid = importedUid,
                            tagType = if (pagesCount <= 16) "MF0 UL11 (16 стр)" else "EV1/NTAG ($pagesCount стр)",
                            readTotalPages = "Импортировано из файла: $pagesCount стр."
                        )
                    }
                    log("Успешный импорт дампа: $pagesCount страниц ($totalBytes байт)")
                    log("Импортирован UID: $importedUid")
                }
            } catch (e: Exception) {
                log("Ошибка импорта дампа: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Reads back the pages we just wrote and compares them to the intended bytes.
     * Reads are grouped into 4-page blocks (the READ command returns 16 bytes) to
     * minimize transceives and tag dwell time.
     *
     * @return number of pages that matched, or null if no page could be read back at all.
     */
    private fun verifyWrittenPages(
        connection: NfcTagConnection,
        writtenPages: Map<Int, ByteArray>
    ): Int? {
        if (writtenPages.isEmpty()) return 0

        // Group target pages by their 4-page aligned block offset.
        val blockOffsets = writtenPages.keys.map { it and 0x03.inv() }.toSortedSet()
        var matched = 0
        var anyReadOk = false

        for (blockOffset in blockOffsets) {
            val block: ByteArray? = try {
                connection.readPages(blockOffset)
            } catch (e: Exception) {
                null
            }
            if (block == null || block.size < 16) {
                // Could not read this block; report each of its pages as unverified.
                for (offset in 0 until 4) {
                    val pageIdx = blockOffset + offset
                    if (writtenPages.containsKey(pageIdx)) {
                        log("Верификация стр. " + String.format(Locale.ROOT, "%02X", pageIdx) + ": не прочитана")
                    }
                }
                continue
            }
            anyReadOk = true
            for (offset in 0 until 4) {
                val pageIdx = blockOffset + offset
                val expected = writtenPages[pageIdx] ?: continue
                val actual = block.sliceArray((offset * 4) until (offset * 4 + 4))
                if (expected.contentEquals(actual)) {
                    matched++
                } else {
                    log(
                        "Верификация стр. " + String.format(Locale.ROOT, "%02X", pageIdx) +
                            ": ожидалось ${bytesToHex(expected)}, прочитано ${bytesToHex(actual)}"
                    )
                }
            }
        }

        return if (anyReadOk) matched else null
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexFormat = StringBuilder()
        for (b in bytes) {
            hexFormat.append(String.format("%02X", b))
        }
        return hexFormat.toString()
    }

    private fun hexToBytes(s: String): ByteArray {
        val sanitized = s.filter { it in "0123456789ABCDEFabcdef" }
        val len = sanitized.length
        if (len % 2 != 0) {
            return hexToBytes(sanitized + "0")
        }
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(sanitized[i], 16)
            val low = Character.digit(sanitized[i + 1], 16)
            if (high != -1 && low != -1) {
                data[i / 2] = ((high shl 4) + low).toByte()
            } else {
                data[i / 2] = 0.toByte()
            }
            i += 2
        }
        return data
    }
}
