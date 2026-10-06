package com.example

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.ScanEntity
import com.example.ui.theme.ConsoleGreen
import com.example.ui.theme.ConsoleGreenDark
import com.example.ui.theme.LightTextPrimary
import com.example.ui.theme.LightTextSecondary
import com.example.ui.theme.DarkTextPrimary
import com.example.ui.theme.DarkTextSecondary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AppMode
import com.example.ui.viewmodel.ScanViewModel
import com.example.ui.viewmodel.PwdGenerator
import kotlinx.coroutines.flow.map
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize NFC Adapter
        try {
            nfcAdapter = NfcAdapter.getDefaultAdapter(this)
            if (nfcAdapter == null) {
                viewModel.log("NFC не поддерживается на этом устройстве.")
            } else if (!nfcAdapter!!.isEnabled) {
                viewModel.log("Реквизит NFC отключен. Включите его в настройках.")
            }
        } catch (e: Exception) {
            nfcAdapter = null
            viewModel.log("Ошибка инициализации NFC: ${e.localizedMessage}")
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val isDarkTheme = uiState.isDarkTheme

            MyApplicationTheme(darkTheme = isDarkTheme) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    ClonerScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                        nfcEnabled = nfcAdapter?.isEnabled ?: false
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            nfcAdapter?.let { adapter ->
                if (adapter.isEnabled) {
                    val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    val pendingIntent = PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val filters = arrayOf(
                        IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
                        IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
                    )
                    val techList = arrayOf(
                        arrayOf(MifareUltralight::class.java.name),
                        arrayOf(NfcA::class.java.name)
                    )
                    adapter.enableForegroundDispatch(this, pendingIntent, filters, techList)
                }
            }
        } catch (e: Exception) {
            viewModel.log("Ошибка активации NFC диспетчера: ${e.localizedMessage}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            nfcAdapter?.let { adapter ->
                if (adapter.isEnabled) {
                    adapter.disableForegroundDispatch(this)
                }
            }
        } catch (e: Exception) {
            // Ignore failure to disable on pause to prevent crash
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.action
        if (NfcAdapter.ACTION_TECH_DISCOVERED == action || NfcAdapter.ACTION_TAG_DISCOVERED == action) {
            val tag: Tag? = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) as? Tag
                }
            } catch (e: Exception) {
                null
            }
            tag?.let {
                viewModel.processNfcTag(it)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClonerScreen(
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
    nfcEnabled: Boolean
) {
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.historyScans.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val contentResolver = context.contentResolver

    // Import Launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            viewModel.importDumpFromUri(contentResolver, uri)
        }
    }

    // Export Launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            viewModel.exportDumpToUri(contentResolver, uri)
        }
    }

    // Keep a stable reference to the current UID so the export filename callback
    // does not capture changing state and force ClonerHeader to recompose on every tick.
    val currentUid = androidx.compose.runtime.rememberUpdatedState(uiState.uid)
    val onImportDumpRemember = remember { { importLauncher.launch("*/*") } }
    val onExportDumpRemember = remember {
        { exportLauncher.launch("vizit_dump_${currentUid.value}.bin"); Unit }
    }

    val stablePages = remember(uiState.pages, uiState.maxPages) {
        val list = List(uiState.maxPages) { idx ->
            PageItem(idx, uiState.pages[idx] ?: "00000000")
        }
        StablePages(list)
    }
    val stableLogs = remember(uiState.logs) { StableLogs(uiState.logs) }

    var editingPage by remember { mutableStateOf<Int?>(null) }
    var showHistoryScreen by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showKeyConfigDialog by remember { mutableStateOf(false) }

    // Alert dialogs for operations
    var showClearConfirm by remember { mutableStateOf(false) }

    // Remembered callbacks for maximum skippability
    val onDismissSettingsRemember = remember { { showSettingsScreen = false } }
    val onToggleSystemPagesRemember = remember(viewModel) { { b: Boolean -> viewModel.toggleWriteSystemPages(b) } }
    val onToggleDarkThemeRemember = remember(viewModel) { { b: Boolean -> viewModel.toggleDarkTheme(b) } }
    val onToggleLockBypassRemember = remember(viewModel) { { b: Boolean -> viewModel.toggleLockBypass(b) } }
    val onToggleWriteCfgPagesRemember = remember(viewModel) { { b: Boolean -> viewModel.toggleWriteCfgPages(b) } }
    val onClearDumpRemember = remember { { showClearConfirm = true } }
    val onShowKeyConfigRemember = remember { { showKeyConfigDialog = true } }

    val onDismissHistoryRemember = remember { { showHistoryScreen = false } }
    val onLoadHistoryRemember = remember(viewModel) {
        { scan: ScanEntity ->
            viewModel.loadScan(scan)
            showHistoryScreen = false
        }
    }
    val onDeleteHistoryRemember = remember(viewModel) { { id: Int -> viewModel.deleteHistoryId(id) } }
    val onClearAllHistoryRemember = remember(viewModel) { { viewModel.clearHistory() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Main Screen Workspace
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            val onShowHistoryRemember = remember { { showHistoryScreen = true } }
            val onShowSettingsRemember = remember { { showSettingsScreen = true } }
            
            // Reusable top cloner header controls
            ClonerHeader(
                onShowHistory = onShowHistoryRemember,
                onShowSettings = onShowSettingsRemember,
                onImportDump = onImportDumpRemember,
                onExportDump = onExportDumpRemember
            )
            
            val onSetModeRemember = remember { { mode: AppMode -> viewModel.setMode(mode) } }
            
            // Compact targeted core controls (minimum wasted space, extremely layout efficient)
            TargetControlCard(
                tagType = uiState.tagType,
                uid = uiState.uid,
                mode = uiState.mode,
                onSetMode = onSetModeRemember,
                isOperating = uiState.isOperating,
                operationProgress = uiState.operationProgress
            )

            // Configure Cryptokey/Password selection button/card
            CryptoKeyControlCard(
                passwordHex = uiState.passwordHex,
                onConfigureKey = onShowKeyConfigRemember
            )

            // Dynamic columns occupying maximum vertical space
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val onPageClickRemember = remember { { idx: Int -> editingPage = idx } }
                HexDumpColumn(
                    maxPages = uiState.maxPages,
                    stablePages = stablePages,
                    onPageClick = onPageClickRemember,
                    modifier = Modifier.weight(0.46f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                LogConsole(
                    stableLogs = stableLogs,
                    uid = uiState.uid,
                    tagType = uiState.tagType,
                    isDarkTheme = uiState.isDarkTheme,
                    modifier = Modifier.weight(0.54f)
                )
            }
        }

        // --- SMOOTH FULL SCREEN OVERLAYS ---
        // 1. Settings Menu Full-Screen Overlay
        AnimatedVisibility(
            visible = showSettingsScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            FullScreenSettings(
                writeSystemPages = uiState.writeSystemPages,
                isDarkTheme = uiState.isDarkTheme,
                enableLockBypass = uiState.enableLockBypass,
                writeCfgPages = uiState.writeCfgPages,
                onDismiss = onDismissSettingsRemember,
                onToggleSystemPages = onToggleSystemPagesRemember,
                onToggleDarkTheme = onToggleDarkThemeRemember,
                onToggleLockBypass = onToggleLockBypassRemember,
                onToggleWriteCfgPages = onToggleWriteCfgPagesRemember,
                onClearDump = onClearDumpRemember
            )
        }

        // 2. Scan History Full-Screen Overlay
        AnimatedVisibility(
            visible = showHistoryScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            FullScreenHistory(
                history = history,
                onDismiss = onDismissHistoryRemember,
                onLoad = onLoadHistoryRemember,
                onDelete = onDeleteHistoryRemember,
                onClearAll = onClearAllHistoryRemember
            )
        }
    }

    // --- SUPPORTING COMPLEMENTARY DIALOGS ---
    // A. Edit Page Hex Payload Dialog
    if (editingPage != null) {
        val pageIdx = editingPage!!
        val originalHex = uiState.pages[pageIdx] ?: "00000000"
        var textValue by remember { mutableStateOf(originalHex) }
        var errorText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { editingPage = null },
            icon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = {
                Text(
                    text = String.format(Locale.ROOT, "Редактировать страницу %02X", pageIdx),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Введите 8 шестнадцатеричных символов (0-9, A-F):",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { input ->
                            val clean = input.uppercase(Locale.ROOT).filter { it in "0123456789ABCDEF" }
                            if (clean.length <= 8) {
                                textValue = clean
                                errorText = if (clean.length < 8) "Должно быть ровно 8 символов (${clean.length}/8)" else ""
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            errorBorderColor = Color.Red
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hex_payload_input"),
                        isError = errorText.isNotEmpty()
                    )

                    if (errorText.isNotEmpty()) {
                        Text(
                            text = errorText,
                            color = Color.Red,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updatePage(pageIdx, textValue)
                        editingPage = null
                    },
                    enabled = textValue.length == 8
                ) {
                    Text("Сохранить", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPage = null }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // B. Clear Dump Confirm Dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить текущий дамп?", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
            text = { Text("Это действие обнулит все страницы редактируемого дампа в 00000000.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearDump()
                    showClearConfirm = false
                    showSettingsScreen = false // Close settings
                }) {
                    Text("Очистить", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // C. Crypto Password Configuration Dialog
    if (showKeyConfigDialog) {
        var customKeyText by remember { mutableStateOf(uiState.passwordHex) }
        var errorText by remember { mutableStateOf("") }
        val uidVal = uiState.uid

        AlertDialog(
            onDismissRequest = { showKeyConfigDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = {
                Text(
                    text = "Настройка криптоключа (PWD)",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = "Введите 8 шестнадцатеричных символов (4 байта) или выберите готовый:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Key Input Row
                    item {
                        OutlinedTextField(
                            value = customKeyText,
                            onValueChange = { input ->
                                val clean = input.uppercase(Locale.ROOT).filter { it in "0123456789ABCDEF" }
                                if (clean.length <= 8) {
                                    customKeyText = clean
                                    errorText = if (clean.length < 8) "Должно быть ровно 8 символов (${clean.length}/8)" else ""
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            ),
                            label = { Text("Свой криптоключ PWD") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                errorBorderColor = Color.Red
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            isError = errorText.isNotEmpty()
                        )
                        if (errorText.isNotEmpty()) {
                            Text(
                                text = errorText,
                                color = Color.Red,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    // Build the full option list declaratively (presets + UID generators).
                    val keyOptions = remember(uidVal, hasRealUid) {
                        val list = mutableListOf<KeyOption>()
                        list += KeyOption("00000000", "КРИПТОКЛЮЧА НЕТ (отключить авторизацию)", "00000000")
                        list += KeyOption("FFFFFFFF", "Заводской по умолчанию", "FFFFFFFF")
                        list += KeyOption("4E457854", "NExT импланты / чипы", "4E457854")
                        list += KeyOption("B6AA558D", "Copykey заготовки", "B6AA558D")
                        list
                    }
                    val generatorOptions = remember(uidVal, hasRealUid) {
                        listOf(
                            Triple("EV1/NTAG XOR алгоритм", "Расчет: ", if (hasRealUid) PwdGenerator.generateDefaultXor(uidVal) else "—"),
                            Triple("SALTO Systems (A)", "Расчет: ", if (hasRealUid) PwdGenerator.generateSalto(uidVal) else "—"),
                            Triple("Amiibo (B)", "Расчет: ", if (hasRealUid) PwdGenerator.generateAmiibo(uidVal) else "—"),
                            Triple("Lego Dimensions (C)", "Расчет: ", if (hasRealUid) PwdGenerator.generateLego(uidVal) else "—"),
                            Triple("Xiaomi Air Purifier (E)", "Расчет: ", if (hasRealUid) PwdGenerator.generateXiaomi(uidVal) else "—"),
                            Triple("NDEF Tools MD5 (F)", "Расчет: ", if (hasRealUid) PwdGenerator.generateNdef(uidVal) else "—")
                        ).map { (title, prefix, calc) ->
                            KeyOption(
                                title = title,
                                subtitle = "$prefix$calc",
                                value = if (hasRealUid) calc else null,
                                isGenerator = true,
                                enabled = hasRealUid
                            )
                        }
                    }

                    // Predefined public presets section
                    item {
                        Text(
                            text = "ОБЩЕДОСТУПНЫЕ ПАРОЛИ (ULTRALIGHT/NTAG):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(keyOptions, key = { "preset_${it.title}" }) { option ->
                        KeyOptionCard(
                            option = option,
                            selected = customKeyText == option.value,
                            onSelect = { customKeyText = it; errorText = "" }
                        )
                    }

                    // Generator algorithms based on UID
                    item {
                        Text(
                            text = "ГЕНЕРАТОРЫ ПО UID (${if (uidVal.isNotEmpty() && uidVal != "00000000000000") uidVal else "0000.."}):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(generatorOptions, key = { "gen_${it.title}" }) { option ->
                        KeyOptionCard(
                            option = option,
                            selected = option.enabled && customKeyText == option.value,
                            onSelect = { customKeyText = it; errorText = "" }
                        )
                    }

                    if (!hasRealUid) {
                        item {
                            Text(
                                text = "⚠️ Для использования генераторов требуется предварительно считать тег или загрузить дамп с действительным UID.",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updatePassword(customKeyText)
                        showKeyConfigDialog = false
                    },
                    enabled = customKeyText.length == 8
                ) {
                    Text("Сохранить", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeyConfigDialog = false }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

// ==========================================
// HIGH PERFORMANCE SUB-COMPONENTS
// ==========================================

@Composable
fun ClonerHeader(
    onShowHistory: () -> Unit,
    onShowSettings: () -> Unit,
    onImportDump: () -> Unit,
    onExportDump: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp, top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onShowHistory() }
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = "История",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Vizit Cloner Tool",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            )
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Import Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onImportDump() }
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Импорт",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            
            // Export Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onExportDump() }
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Экспорт",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            // Settings Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onShowSettings() }
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Настройки",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun TargetControlCard(
    tagType: String,
    uid: String,
    mode: AppMode,
    onSetMode: (AppMode) -> Unit,
    isOperating: Boolean,
    operationProgress: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (isOperating) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = operationProgress,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Side: Tag status and UID stacked tight
                Column(
                    modifier = Modifier.weight(0.45f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (tagType.isNotEmpty()) tagType.uppercase(Locale.ROOT) else "ULTRALIGHT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (uid.isNotEmpty() && uid != "00000000000000") ConsoleGreen else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (uid.isNotEmpty() && uid != "00000000000000") "CONN" else "OK",
                                color = Color.White,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "UID: " + if (uid.isNotEmpty() && uid != "00000000000000") uid else "Ожидание тега",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                // Right Side: Slim side-by-side action buttons
                Row(
                    modifier = Modifier.weight(0.55f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { onSetMode(AppMode.READ) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mode == AppMode.READ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (mode == AppMode.READ) Color.White else MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .border(1.dp, if (mode == AppMode.READ) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .testTag("action_read"),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "▼ ЧИТАТЬ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }

                    Button(
                        onClick = { onSetMode(AppMode.WRITE) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mode == AppMode.WRITE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (mode == AppMode.WRITE) Color.White else MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .border(1.dp, if (mode == AppMode.WRITE) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .testTag("action_write"),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "✍ ЗАПИСАТЬ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CryptoKeyControlCard(
    passwordHex: String,
    onConfigureKey: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onConfigureKey() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "КРИПТОКЛЮЧ (PWD):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (passwordHex == "00000000") "00000000 (НЕТ)" else passwordHex,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 0.5.sp
                )
            }
            Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = "Изменить криптоключ",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun getLockedPages(page2Hex: String): Set<Int> {
    if (page2Hex.length < 8) return emptySet()
    val b2 = page2Hex.substring(4, 6).toIntOrNull(16) ?: 0
    val b3 = page2Hex.substring(6, 8).toIntOrNull(16) ?: 0
    
    val locked = mutableSetOf<Int>()
    
    // Check under Standard Lock Byte assignment:
    // b2 is L0 (locks pages 8-15)
    for (i in 0..7) {
        if ((b2 and (1 shl i)) != 0) {
            locked.add(8 + i)
        }
    }
    // b3 is L1 (locks pages 3-7)
    if ((b3 and 0x08) != 0) locked.add(3)
    if ((b3 and 0x10) != 0) locked.add(4)
    if ((b3 and 0x20) != 0) locked.add(5)
    if ((b3 and 0x40) != 0) locked.add(6)
    if ((b3 and 0x80) != 0) locked.add(7)
    
    // Check under Swapped assignment (helps if byte-swapped representations are read):
    // b2 is L1 (locks pages 3-7)
    if ((b2 and 0x08) != 0) locked.add(3)
    if ((b2 and 0x10) != 0) locked.add(4)
    if ((b2 and 0x20) != 0) locked.add(5)
    if ((b2 and 0x40) != 0) locked.add(6)
    if ((b2 and 0x80) != 0) locked.add(7)
    // b3 is L0 (locks pages 8-15)
    for (i in 0..7) {
        if ((b3 and (1 shl i)) != 0) {
            locked.add(8 + i)
        }
    }
    
    return locked
}

@Composable
fun HexPageRow(
    idx: Int,
    pageData: String,
    isLocked: Boolean,
    isPage2: Boolean,
    onClick: (Int) -> Unit
) {
    val clickAction = remember(idx, onClick) { { onClick(idx) } }
    val label = remember(idx) { if (idx in 0..511) HexPageLabels[idx] else String.format(Locale.ROOT, "%02X :", idx) }
    
    val labelColor = when {
        isLocked -> MaterialTheme.colorScheme.error
        isPage2 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }
    
    val rowBackground = when {
        isLocked -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(rowBackground)
            .clickable(onClick = clickAction)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = labelColor
            )
            if (isLocked) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Заблокировано",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
        
        if (isPage2 && pageData.length == 8) {
            val firstPart = pageData.substring(0, 4)
            val lockPart = pageData.substring(4, 8)
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = buildAnnotatedString {
                        append(firstPart)
                        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)) {
                            append(lockPart)
                        }
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 0.5.sp
                )
                val blockedSet = getLockedPages(pageData)
                if (blockedSet.isNotEmpty()) {
                    Text(
                        text = "Блок: ${blockedSet.sorted().joinToString(",")}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else if (isLocked) {
            Text(
                text = pageData,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                letterSpacing = 0.5.sp
            )
        } else {
            Text(
                text = pageData,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun HexDumpColumn(
    maxPages: Int,
    stablePages: StablePages,
    onPageClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val page2Hex = remember(stablePages) {
        stablePages.list.firstOrNull { it.index == 2 }?.hex ?: "00000000"
    }
    val lockedPages = remember(page2Hex) {
        getLockedPages(page2Hex)
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Стр | hex",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(ConsoleGreen.copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "READABLE",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = ConsoleGreen
                )
            }
        }
        
        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)))
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
        ) {
            items(
                items = stablePages.list,
                key = { it.index },
                contentType = { "hex_page" }
            ) { item ->
                val isLocked = lockedPages.contains(item.index)
                HexPageRow(
                    idx = item.index,
                    pageData = item.hex,
                    isLocked = isLocked,
                    isPage2 = (item.index == 2),
                    onClick = onPageClick
                )
            }
        }
    }
}

@Composable
fun LogLineRow(
    logLine: String,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val textPrimaryColor = if (isDark) DarkTextPrimary else LightTextPrimary
    val textSecondaryColor = if (isDark) DarkTextSecondary else LightTextSecondary

    val style = remember(logLine, isDark, primaryColor, textPrimaryColor, textSecondaryColor) {
        when {
            logLine == "Журнал" -> Pair(textPrimaryColor, FontWeight.Bold)
            logLine.startsWith("Read mode") || logLine.startsWith("Write mode") -> Pair(if (isDark) ConsoleGreenDark else ConsoleGreen, FontWeight.Bold)
            logLine.contains("UID:") -> Pair(if (isDark) ConsoleGreenDark else ConsoleGreen, FontWeight.Bold)
            logLine.contains("Тип чипа:") || logLine.contains("Спецификация:") -> Pair(primaryColor, FontWeight.Bold)
            logLine.contains("Ошибка") -> Pair(Color(0xFFE53935), FontWeight.Bold)
            logLine.contains("Успешно") || logLine.contains("завершена") -> Pair(if (isDark) ConsoleGreenDark else ConsoleGreen, FontWeight.Bold)
            logLine.startsWith("===") -> Pair(textSecondaryColor.copy(alpha = 0.4f), FontWeight.Normal)
            else -> Pair(textSecondaryColor, FontWeight.Normal)
        }
    }
    Text(
        text = logLine,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = style.second,
        color = style.first,
        modifier = modifier
    )
}

@Composable
fun LogConsole(
    stableLogs: StableLogs,
    uid: String,
    tagType: String,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(8.dp)
    ) {
        Text(
            text = "ЖУРНАЛ КЛОНЕРА",
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        val lazyListState = rememberLazyListState()
        val logsSize = stableLogs.list.size
        LaunchedEffect(logsSize) {
            if (logsSize > 0) {
                lazyListState.scrollToItem(logsSize - 1)
            }
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.weight(1f),
            reverseLayout = false,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            itemsIndexed(
                items = stableLogs.list,
                key = { index, _ -> index },
                contentType = { _, _ -> "log_line" }
            ) { _, logLine ->
                LogLineRow(logLine = logLine, isDark = isDarkTheme)
            }
        }

        if (uid.isNotEmpty() && uid != "00000000000000") {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(6.dp)
            ) {
                Text(
                    text = "ДЕТАЛИ ТЕГА",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "UID: $uid",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = ConsoleGreen,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Тип: $tagType",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ==========================================
// ELEGANT FULL SCREEN OVERLAYS
// ==========================================

@Composable
fun FullScreenSettings(
    writeSystemPages: Boolean,
    isDarkTheme: Boolean,
    enableLockBypass: Boolean,
    writeCfgPages: Boolean,
    onDismiss: () -> Unit,
    onToggleSystemPages: (Boolean) -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleLockBypass: (Boolean) -> Unit,
    onToggleWriteCfgPages: (Boolean) -> Unit,
    onClearDump: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp, top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Настройки",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Закрыть",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // General Settings Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "ОСНОВНЫЕ ПАРАМЕТРЫ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    // Dark Theme Switch Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleDarkTheme(!isDarkTheme) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Темная тема", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Экономит заряд и бережет глаза", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(
                            checked = isDarkTheme,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)))

                    // Write System Pages Switch Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleSystemPages(!writeSystemPages) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Запись системных стр. 0-3", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Перезапись UID (требуется Magic Tag)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(
                            checked = writeSystemPages,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)))

                    // Enable Lock Bypass Switch Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleLockBypass(!enableLockBypass) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(0.85f)) {
                            Text("Обход лок-байтов (Lock-Bait Bypass)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Временное снятие блокировки при записи (использовать только для поддерживающих заготовок)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(
                            checked = enableLockBypass,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)))

                    // Write Config Pages Switch Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleWriteCfgPages(!writeCfgPages) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(0.85f)) {
                            Text("Запись стр. 29-2B (CFG/PWD)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Запись страниц конфигурации и криптопароля (выключите для избежания случайной блокировки)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(
                            checked = writeCfgPages,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Danger Button: Clear dump
            Button(
                onClick = onClearDump,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFCC1D1D),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(
                    text = "ОЧИСТИТЬ ТЕКУЩИЙ ДАМП В 00",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun FullScreenHistory(
    history: List<ScanEntity>,
    onDismiss: () -> Unit,
    onLoad: (ScanEntity) -> Unit,
    onDelete: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp, top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "История сканирований",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (history.isNotEmpty()) {
                        IconButton(
                            onClick = onClearAll,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFFE53935).copy(alpha = 0.15f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Очистить всё",
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Закрыть",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "История пока пустая",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = history,
                        key = { it.id }
                    ) { scan ->
                        HistoryDialogItem(
                            scan = scan,
                            onLoad = { onLoad(scan) },
                            onDelete = { onDelete(scan.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryDialogItem(
    scan: ScanEntity,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onLoadRemember = remember(scan, onLoad) { { onLoad() } }
    val onDeleteRemember = remember(scan.id, onDelete) { { onDelete() } }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onLoadRemember)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(0.85f)) {
                Text(
                    text = scan.title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "UID: ${scan.uid}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = ConsoleGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Сорт: ${scan.tagType}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(
                onClick = onDeleteRemember,
                modifier = Modifier.weight(0.15f)
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun RadioButtonAccent(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            )
        }
    }
}

private val HexPageLabels = List(512) { String.format(Locale.ROOT, "%02X :", it) }

@Immutable
data class PageItem(val index: Int, val hex: String)

@Immutable
data class StablePages(val list: List<PageItem>)

@Immutable
data class StableLogs(val list: List<String>)

@Immutable
data class StableHistory(val list: List<ScanEntity>)

/**
 * One selectable PWD option in the crypto-key dialog.
 * @param value the 8-hex PWD value, or null for generator entries that have no UID yet.
 * @param enabled whether the option can be selected (false for UID generators without a real UID).
 */
@Immutable
data class KeyOption(
    val title: String,
    val subtitle: String,
    val value: String?,
    val isGenerator: Boolean = false,
    val enabled: Boolean = true
)

/**
 * A single selectable key card, shared by both preset and generator options,
 * replacing ~9 nearly identical copy-pasted Card blocks.
 */
@Composable
fun KeyOptionCard(
    option: KeyOption,
    selected: Boolean,
    onSelect: (String) -> Unit
) {
    val value = option.value
    val selectable = option.enabled && value != null
    Card(
        onClick = { if (selectable) onSelect(value!!) },
        enabled = selectable,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selectable) 0.4f else 0.15f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = if (option.isGenerator) Modifier.weight(0.85f) else Modifier) {
                if (option.isGenerator) {
                    Text(
                        option.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (selectable) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        option.subtitle,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        option.title,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        option.subtitle,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            RadioButtonAccent(selected = selected)
        }
    }
}
