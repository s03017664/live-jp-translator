package com.npl.livejptranslator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    widthSizeClass: WindowWidthSizeClass,
    onToggleStart: () -> Unit,
    onCopyText: (String) -> Unit,
    onSpeakOriginal: (String, String) -> Unit,
    onTestDubbingVoice: () -> Unit,
    onToggleStar: (Long) -> Unit,
    onDeleteSession: (String) -> Unit,
    onAddPhrase: (String, String, String) -> Unit,
    onDeletePhrase: (Long) -> Unit,
    onSelectProfile: (StreamerProfile?) -> Unit,
    onUpsertProfile: (StreamerProfile) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onSaveSummary: (String, String) -> Unit,
    onImportCardsFromSummary: (String, String, String) -> Unit,
    onMarkCardMastered: (Long) -> Unit,
    onDeleteCard: (Long) -> Unit,
    onExportSessionMd: (String, List<TranscriptItem>) -> Unit,
    onClearHistory: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: (overwrite: Boolean) -> Unit,
    onSaveSettings: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val isRunning by TranslatorState.isRunning.collectAsState()
    val status by TranslatorState.statusText.collectAsState()
    val currentRms by TranslatorState.currentRms.collectAsState()
    val latestOrig by TranslatorState.latestOriginal.collectAsState()
    val latestTrans by TranslatorState.latestTranslated.collectAsState()
    val history by TranslatorState.history.collectAsState()
    val settings by TranslatorState.settings.collectAsState()
    val usage by TranslatorState.usage.collectAsState()
    val phrases by TranslatorState.quickPhrases.collectAsState()
    val profiles by TranslatorState.profiles.collectAsState()
    val sessionSummaries by TranslatorState.sessionSummaries.collectAsState()
    val flashcards by TranslatorState.flashcards.collectAsState()

    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var starredOnly by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    var summarySessionKey by remember { mutableStateOf<String?>(null) }
    var isSummarizing by remember { mutableStateOf(false) }

    var explainingItem by remember { mutableStateOf<TranscriptItem?>(null) }
    var explainText by remember { mutableStateOf("") }
    var isExplaining by remember { mutableStateOf(false) }

    var editingProfile by remember { mutableStateOf<StreamerProfile?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }

    // 餘額管理／加值彈窗
    var showBalanceDialog by remember { mutableStateOf(false) }

    val isFoldOrTablet = widthSizeClass != WindowWidthSizeClass.Compact
    val activeProfile = profiles.firstOrNull { it.id == settings.activeProfileId }

    fun runAiSummaryForSession(sessionKey: String, items: List<TranscriptItem>) {
        if (items.isEmpty()) return
        summarySessionKey = sessionKey
        isSummarizing = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { GeminiApi.summarizeSession(context, items, settings, "$sessionKey 直播") }
            isSummarizing = false
            res.onSuccess { sum ->
                onSaveSummary(sessionKey, sum)
                val lang = items.firstOrNull()?.langCode ?: settings.sourceLanguage.shortCode
                onImportCardsFromSummary(sum, lang, sessionKey)
            }.onFailure { onSaveSummary(sessionKey, "總結生成失敗：${it.message}") }
        }
    }

    fun runPhraseExplain(item: TranscriptItem) {
        explainingItem = item
        explainText = ""
        isExplaining = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { GeminiApi.explainPhrase(context, item, settings) }
            isExplaining = false
            explainText = res.getOrElse { "拆解失敗：${it.message}" }
        }
    }

    if (showBalanceDialog) {
        BalanceAdjustDialog(
            settings = settings,
            usage = usage,
            onDismiss = { showBalanceDialog = false },
            onTopUpHkd = { addHkd ->
                TranslatorState.topUpBalanceHkd(context, addHkd)
                showBalanceDialog = false
            },
            onResetLedger = { newHkd, proUsd, rate ->
                onSaveSettings(settings.copy(monthlyProCreditUsd = proUsd, usdToHkdRate = rate))
                TranslatorState.resetBalanceLedger(context, newHkd)
                showBalanceDialog = false
            },
            onUpdateMeta = { proUsd, rate ->
                onSaveSettings(settings.copy(monthlyProCreditUsd = proUsd, usdToHkdRate = rate))
                showBalanceDialog = false
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("匯入 JSON 紀錄與設定檔") },
            text = { Text("你想覆蓋現有紀錄與設定檔，還是與現有資料合併？") },
            confirmButton = { TextButton(onClick = { showImportDialog = false; onImportJson(false) }) { Text("合併") } },
            dismissButton = { TextButton(onClick = { showImportDialog = false; onImportJson(true) }) { Text("覆蓋") } }
        )
    }

    if (showProfileDialog) {
        ProfileEditDialog(
            initial = editingProfile,
            onDismiss = { showProfileDialog = false },
            onSave = { prof ->
                onUpsertProfile(prof)
                onSelectProfile(prof)
                showProfileDialog = false
            }
        )
    }

    explainingItem?.let { target ->
        AlertDialog(
            onDismissRequest = { explainingItem = null },
            icon = { Icon(Icons.Default.School, null, tint = MaterialTheme.colorScheme.secondary) },
            title = { Text("日文文法／潮語拆解") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (target.original.isNotBlank()) {
                        Text(target.original, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(target.translated, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider()
                    if (isExplaining) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            Text("AI 正在拆解句子結構與潮語…")
                        }
                    } else {
                        Text(explainText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !isExplaining && explainText.isNotBlank(), onClick = { onCopyText(explainText) }) {
                    Text("複製解析")
                }
            },
            dismissButton = {
                TextButton(onClick = { explainingItem = null }) { Text("關閉") }
            }
        )
    }

    summarySessionKey?.let { key ->
        val summaryText = sessionSummaries[key] ?: ""
        val sessionItems = history.filter { it.date.ifBlank { "較早紀錄" } == key }
        AlertDialog(
            onDismissRequest = { summarySessionKey = null },
            icon = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("《$key》AI 精華筆記") },
            text = {
                Box(modifier = Modifier.heightIn(min = 120.dp, max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (isSummarizing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("AI 正在整理本場重點話題、實用口語並自動生成生字卡…")
                        }
                    } else {
                        Text(summaryText.ifBlank { "尚無筆記內容" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = !isSummarizing && summaryText.isNotBlank(), onClick = { onCopyText(summaryText) }) {
                        Text("複製")
                    }
                    FilledTonalButton(
                        enabled = !isSummarizing && (summaryText.isNotBlank() || sessionItems.isNotEmpty()),
                        onClick = { onExportSessionMd(key, sessionItems) }
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("匯出 .md")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { summarySessionKey = null }) { Text("關閉") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("直播即時譯 v1.8.0", fontWeight = FontWeight.Bold)
                        AssistChip(
                            onClick = { showBalanceDialog = true },
                            label = {
                                Text(
                                    String.format(Locale.US, "餘 HK$%.2f", usage.remainingCreditHkd(settings)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val next = when (settings.themeMode) {
                            ThemeMode.SYSTEM -> ThemeMode.DARK
                            ThemeMode.DARK -> ThemeMode.LIGHT
                            ThemeMode.LIGHT -> ThemeMode.SYSTEM
                        }
                        onSaveSettings(settings.copy(themeMode = next))
                    }) {
                        Icon(
                            when (settings.themeMode) {
                                ThemeMode.DARK -> Icons.Default.DarkMode
                                ThemeMode.LIGHT -> Icons.Default.LightMode
                                ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            },
                            contentDescription = "切換深淺色"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!isFoldOrTablet) {
                NavigationBar {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Subtitles, null) }, label = { Text("即時翻譯") })
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Translate, null) }, label = { Text("互動快捷") })
                    NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Style, null) }, label = { Text("生字卡") })
                    NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("設定") })
                }
            }
        }
    ) { pad ->
        if (isFoldOrTablet) {
            Row(modifier = Modifier.fillMaxSize().padding(pad)) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(8.dp))
                    NavigationRailItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Subtitles, null) }, label = { Text("主控台") })
                    NavigationRailItem(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Translate, null) }, label = { Text("互動") })
                    NavigationRailItem(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Style, null) }, label = { Text("生字卡") })
                    NavigationRailItem(selected = selectedTab == 3, onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("設定") })
                }
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.weight(0.95f).fillMaxHeight()) {
                        when (selectedTab) {
                            0 -> ControlPanel(
                                isRunning, status, currentRms, latestOrig, latestTrans,
                                settings, usage, profiles, onToggleStart, onSaveSettings,
                                onSelectProfile,
                                onAddProfileClick = { editingProfile = null; showProfileDialog = true },
                                onOpenBalanceDialog = { showBalanceDialog = true }
                            )
                            1 -> QuickInteractPanel(settings, activeProfile, phrases, onCopyText, onSpeakOriginal, onAddPhrase, onDeletePhrase)
                            2 -> FlashcardReviewPanel(flashcards, onSpeakOriginal, onMarkCardMastered, onDeleteCard)
                            else -> SettingsPanel(
                                settings, currentRms, profiles, onSaveSettings, onTestDubbingVoice,
                                onOpenBalanceDialog = { showBalanceDialog = true },
                                onAddProfileClick = { editingProfile = null; showProfileDialog = true },
                                onEditProfile = { editingProfile = it; showProfileDialog = true },
                                onDeleteProfile = onDeleteProfile,
                                onExportJson = onExportJson, onExportCsv = onExportCsv,
                                onOpenImport = { showImportDialog = true }
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                        SessionGroupedHistoryPanel(
                            history = history,
                            sessionSummaries = sessionSummaries,
                            searchQuery = searchQuery,
                            starredOnly = starredOnly,
                            onSearchChange = { searchQuery = it },
                            onToggleStarredOnly = { starredOnly = !starredOnly },
                            onToggleStar = onToggleStar,
                            onSpeakOriginal = onSpeakOriginal,
                            onExplainItem = { runPhraseExplain(it) },
                            onCopyText = onCopyText,
                            onSummarizeSession = { k, list -> runAiSummaryForSession(k, list) },
                            onViewSavedSummary = { k -> summarySessionKey = k },
                            onExportSessionMd = onExportSessionMd,
                            onDeleteSession = onDeleteSession,
                            onClearAll = onClearHistory
                        )
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp, vertical = 8.dp)) {
                when (selectedTab) {
                    0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        ControlPanel(
                            isRunning, status, currentRms, latestOrig, latestTrans,
                            settings, usage, profiles, onToggleStart, onSaveSettings,
                            onSelectProfile,
                            onAddProfileClick = { editingProfile = null; showProfileDialog = true },
                            onOpenBalanceDialog = { showBalanceDialog = true }
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SessionGroupedHistoryPanel(
                                history = history,
                                sessionSummaries = sessionSummaries,
                                searchQuery = searchQuery,
                                starredOnly = starredOnly,
                                onSearchChange = { searchQuery = it },
                                onToggleStarredOnly = { starredOnly = !starredOnly },
                                onToggleStar = onToggleStar,
                                onSpeakOriginal = onSpeakOriginal,
                                onExplainItem = { runPhraseExplain(it) },
                                onCopyText = onCopyText,
                                onSummarizeSession = { k, list -> runAiSummaryForSession(k, list) },
                                onViewSavedSummary = { k -> summarySessionKey = k },
                                onExportSessionMd = onExportSessionMd,
                                onDeleteSession = onDeleteSession,
                                onClearAll = onClearHistory
                            )
                        }
                    }
                    1 -> QuickInteractPanel(settings, activeProfile, phrases, onCopyText, onSpeakOriginal, onAddPhrase, onDeletePhrase)
                    2 -> FlashcardReviewPanel(flashcards, onSpeakOriginal, onMarkCardMastered, onDeleteCard)
                    else -> SettingsPanel(
                        settings, currentRms, profiles, onSaveSettings, onTestDubbingVoice,
                        onOpenBalanceDialog = { showBalanceDialog = true },
                        onAddProfileClick = { editingProfile = null; showProfileDialog = true },
                        onEditProfile = { editingProfile = it; showProfileDialog = true },
                        onDeleteProfile = onDeleteProfile,
                        onExportJson = onExportJson, onExportCsv = onExportCsv,
                        onOpenImport = { showImportDialog = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceAdjustDialog(
    settings: AppSettings,
    usage: ApiUsageRecord,
    onDismiss: () -> Unit,
    onTopUpHkd: (Double) -> Unit,
    onResetLedger: (Double, Double, Double) -> Unit,
    onUpdateMeta: (Double, Double) -> Unit
) {
    var balanceText by remember { mutableStateOf(String.format(Locale.US, "%.2f", settings.initialBalanceHkd)) }
    var proCreditText by remember { mutableStateOf(String.format(Locale.US, "%.2f", settings.monthlyProCreditUsd)) }
    var rateText by remember { mutableStateOf(String.format(Locale.US, "%.2f", settings.usdToHkdRate)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AccountBalanceWallet, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("API 帳單餘額與用量管理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            String.format(Locale.US, "目前可用剩餘額度：HK$%.2f（約 US$%.2f）", usage.remainingCreditHkd(settings), usage.remainingCreditHkd(settings) / settings.usdToHkdRate),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            String.format(Locale.US, "今日花費：HK$%.4f（US$%.5f · %d 次請求）", usage.todayCostHkd(settings.usdToHkdRate), usage.todayCostUsd, usage.requests),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            String.format(Locale.US, "累計已扣：HK$%.4f（In %,d / Out %,d tokens）", usage.totalSpentHkd(settings.usdToHkdRate), usage.totalPromptTokens, usage.totalOutputTokens),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (usage.lastTokenBreakdown.isNotBlank()) {
                            Text(
                                String.format(Locale.US, "上一句消耗：HK$%.5f（%s）", usage.lastRequestCostUsd * settings.usdToHkdRate, usage.lastTokenBreakdown),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                Text("快速記錄充值（與 AI Studio 同步）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onTopUpHkd(40.0) }, modifier = Modifier.weight(1f)) { Text("+HK$40") }
                    OutlinedButton(onClick = { onTopUpHkd(160.0) }, modifier = Modifier.weight(1f)) { Text("+HK$160") }
                }

                HorizontalDivider()
                OutlinedTextField(
                    value = balanceText, onValueChange = { balanceText = it },
                    label = { Text("Cloud Prepay 預付本金 (HKD)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = proCreditText, onValueChange = { proCreditText = it },
                    label = { Text("Pro 每月免費 API 贈金 (USD，如 10)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = rateText, onValueChange = { rateText = it },
                    label = { Text("美元兌港元匯率 (USD → HKD)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "註：Google API 不提供自動查詢餘額的公開接口，本程式會根據每次 Gemini 回傳的真實 usageMetadata Token 數量精準扣減，誤差極微；你亦隨時可按上方按鈕校正為 AI Studio 顯示的實際餘額。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    val pro = proCreditText.toDoubleOrNull() ?: settings.monthlyProCreditUsd
                    val rate = (rateText.toDoubleOrNull() ?: settings.usdToHkdRate).coerceAtLeast(1.0)
                    onUpdateMeta(pro, rate)
                }) { Text("僅更新匯率/贈金") }
                FilledTonalButton(onClick = {
                    val bal = balanceText.toDoubleOrNull() ?: 40.0
                    val pro = proCreditText.toDoubleOrNull() ?: 0.0
                    val rate = (rateText.toDoubleOrNull() ?: 7.80).coerceAtLeast(1.0)
                    onResetLedger(bal, pro, rate)
                }) { Text("重設並校正餘額") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } }
    )
}

@Composable
private fun FlashcardReviewPanel(
    cards: List<FlashcardItem>,
    onSpeakOriginal: (String, String) -> Unit,
    onMarkMastered: (Long) -> Unit,
    onDeleteCard: (Long) -> Unit
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }

    if (cards.isEmpty()) {
        ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Style, null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                Text("生字卡溫習庫暫時未有卡片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("在直播時點懸浮框「☆ / ★」收藏金句，或按「AI 總結」整理口語，就會自動加入這裡方便溫習！", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val safeIdx = index.coerceIn(0, cards.lastIndex)
    val card = cards[safeIdx]

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("翻卡溫習（${safeIdx + 1} / ${cards.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            AssistChip(onClick = {}, label = { Text(card.sourceTag, style = MaterialTheme.typography.labelSmall) })
        }

        ElevatedCard(
            onClick = { revealed = !revealed },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("已熟讀 ${card.masteredCount} 次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Row {
                        IconButton(onClick = { onSpeakOriginal(card.reading.ifBlank { card.term }, card.langCode) }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "朗讀發音", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = {
                            onDeleteCard(card.id)
                            revealed = false
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "移除卡片")
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(card.term, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (card.reading.isNotBlank() && card.reading != card.term) {
                        Spacer(Modifier.height(6.dp))
                        Text(card.reading, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp))
                    if (revealed) {
                        Text(card.meaning, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        if (card.example.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(card.example, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        FilledTonalButton(onClick = { revealed = true }) {
                            Text("點卡或按此顯示意思與例句")
                        }
                    }
                }

                Text("點擊卡片切換顯示／隱藏答案", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    revealed = false
                    index = if (safeIdx > 0) safeIdx - 1 else cards.lastIndex
                },
                modifier = Modifier.weight(1f)
            ) { Text("上一張") }
            Button(
                onClick = {
                    onMarkMastered(card.id)
                    revealed = false
                    index = (safeIdx + 1) % cards.size
                },
                modifier = Modifier.weight(1.4f)
            ) { Text("熟讀＋下一張") }
        }
    }
}

@Composable
private fun ProfileEditDialog(
    initial: StreamerProfile?,
    onDismiss: () -> Unit,
    onSave: (StreamerProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var glossary by remember { mutableStateOf(initial?.glossary ?: "") }
    var lang by remember { mutableStateOf(initial?.language ?: SourceLanguage.JAPANESE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增主播詞彙設定檔" else "編輯詞彙設定檔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("主播／頻道名稱（例：Yuki ミクチャ台）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("該主播預設語言", style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SourceLanguage.entries) { l ->
                        FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(l.shortCode) })
                    }
                }
                OutlinedTextField(
                    value = glossary, onValueChange = { glossary = it },
                    label = { Text("專屬詞庫（人名、口頭禪、粉絲稱呼）") },
                    placeholder = { Text("例：ゆきちゃん(Yuki)、リスナー名、愛貓叫Mochi") },
                    minLines = 3, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(StreamerProfile(initial?.id ?: System.currentTimeMillis(), name.trim(), glossary.trim(), lang))
                }
            }) { Text("儲存並套用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ControlPanel(
    isRunning: Boolean, status: String, currentRms: Int, orig: String, trans: String,
    settings: AppSettings, usage: ApiUsageRecord, profiles: List<StreamerProfile>,
    onToggleStart: () -> Unit, onSaveSettings: (AppSettings) -> Unit,
    onSelectProfile: (StreamerProfile?) -> Unit, onAddProfileClick: () -> Unit,
    onOpenBalanceDialog: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF0F172A))))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(status, style = MaterialTheme.typography.labelMedium, color = Color(0xFF38BDF8), fontWeight = FontWeight.SemiBold)
                    if (isRunning) {
                        Text("音量 $currentRms", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
                    }
                }
                if (isRunning) {
                    val level = (currentRms / 1200f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { level },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = if (currentRms >= settings.silenceThreshold) Color(0xFF38BDF8) else Color(0xFF475569),
                        trackColor = Color(0xFF1E293B)
                    )
                }
                if (settings.showOriginal && orig.isNotBlank()) {
                    Text(orig, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                }
                Text(trans, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // 實時 API $ 用量及餘額儀表卡（點擊可充值或校正餘額）
        Surface(
            onClick = onOpenBalanceDialog,
            tonalElevation = 3.dp,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val rate = settings.usdToHkdRate
            val todayHkd = usage.todayCostHkd(rate)
            val sessHkd = usage.sessionCostHkd(rate)
            val remHkd = usage.remainingCreditHkd(settings)
            val pct = usage.remainingPercent(settings)

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            String.format(Locale.US, "剩餘額度：HK$%.2f", remHkd),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        String.format(Locale.US, "本場：HK$%.3f · 今日：HK$%.3f", sessHkd, todayHkd),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (pct > 0.2f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        String.format(Locale.US, "今日：%.1f 分鐘 · %,d tokens", usage.secondsTranslated / 60.0, usage.todayPromptTokens + usage.todayOutputTokens),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("點此加值／校正 ▸", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
                FilterChip(
                    selected = settings.activeProfileId == 0L,
                    onClick = { onSelectProfile(null) },
                    leadingIcon = { Icon(Icons.Default.PersonOutline, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("通用詞庫", style = MaterialTheme.typography.labelMedium) }
                )
            }
            items(profiles, key = { it.id }) { prof ->
                FilterChip(
                    selected = settings.activeProfileId == prof.id,
                    onClick = { onSelectProfile(prof) },
                    leadingIcon = { Icon(Icons.Default.Person, null, modifier = Modifier.size(16.dp)) },
                    label = { Text(prof.name, style = MaterialTheme.typography.labelMedium) }
                )
            }
            item {
                AssistChip(
                    onClick = onAddProfileClick,
                    leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("新增主播", style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                FilterChip(
                    selected = settings.liveDubbing,
                    onClick = { onSaveSettings(settings.copy(liveDubbing = !settings.liveDubbing)) },
                    leadingIcon = { Icon(Icons.Default.RecordVoiceOver, null, modifier = Modifier.size(16.dp)) },
                    label = { Text(if (settings.liveDubbing) "粵語即時配音：開" else "粵語即時配音：關", style = MaterialTheme.typography.labelSmall) }
                )
            }
            item {
                FilterChip(
                    selected = settings.showFurigana,
                    onClick = { onSaveSettings(settings.copy(showFurigana = !settings.showFurigana)) },
                    label = { Text("漢字標平假名", style = MaterialTheme.typography.labelSmall) }
                )
            }
            items(SourceLanguage.entries) { lang ->
                FilterChip(
                    selected = settings.sourceLanguage == lang,
                    onClick = { onSaveSettings(settings.copy(sourceLanguage = lang)) },
                    label = { Text(lang.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = settings.captureMode == CaptureMode.INTERNAL,
                onClick = { onSaveSettings(settings.copy(captureMode = CaptureMode.INTERNAL)) },
                label = { Text("內錄原聲", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = settings.captureMode == CaptureMode.MICROPHONE,
                onClick = { onSaveSettings(settings.copy(captureMode = CaptureMode.MICROPHONE)) },
                label = { Text("收音咪", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = settings.model == "gemini-2.5-flash",
                onClick = {
                    val next = if (settings.model == "gemini-2.5-flash") "gemini-2.5-flash-lite" else "gemini-2.5-flash"
                    onSaveSettings(settings.copy(model = next))
                },
                label = { Text(if (settings.model == "gemini-2.5-flash") "高準度" else "極速省錢", style = MaterialTheme.typography.labelSmall) }
            )
        }

        Button(
            onClick = onToggleStart,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
        ) {
            Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRunning) "停止懸浮翻譯" else "啟動懸浮即時翻譯", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SessionGroupedHistoryPanel(
    history: List<TranscriptItem>,
    sessionSummaries: Map<String, String>,
    searchQuery: String,
    starredOnly: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleStarredOnly: () -> Unit,
    onToggleStar: (Long) -> Unit,
    onSpeakOriginal: (String, String) -> Unit,
    onExplainItem: (TranscriptItem) -> Unit,
    onCopyText: (String) -> Unit,
    onSummarizeSession: (String, List<TranscriptItem>) -> Unit,
    onViewSavedSummary: (String) -> Unit,
    onExportSessionMd: (String, List<TranscriptItem>) -> Unit,
    onDeleteSession: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    val filtered = remember(history, searchQuery, starredOnly) {
        history.filter { item ->
            val matchStar = !starredOnly || item.starred
            val q = searchQuery.trim()
            val matchQuery = q.isBlank() || item.original.contains(q, true) || item.translated.contains(q, true) || item.profileName.contains(q, true)
            matchStar && matchQuery
        }
    }

    val grouped = remember(filtered) {
        filtered.groupBy { it.date.ifBlank { "較早紀錄" } }
            .entries
            .sortedByDescending { it.key }
    }

    val expandedState = remember { mutableStateMapOf<String, Boolean>() }

    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("場次字幕紀錄（${grouped.size} 場 · ${filtered.size} 句）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    IconButton(onClick = onClearAll, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空全部", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("搜尋字幕或主播名…", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { onSearchChange("") }) { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = starredOnly,
                    onClick = onToggleStarredOnly,
                    leadingIcon = { Icon(if (starredOnly) Icons.Default.Star else Icons.Default.StarBorder, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("收藏") }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (grouped.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("尚無符合條件的字幕紀錄", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(grouped, key = { it.key }) { (dateKey, sessionItems) ->
                        val isNewest = grouped.firstOrNull()?.key == dateKey
                        val expanded = expandedState[dateKey] ?: (isNewest || searchQuery.isNotBlank() || starredOnly)
                        val starredCount = sessionItems.count { it.starred }
                        val hasSavedNote = !sessionSummaries[dateKey].isNullOrBlank()
                        val displayDate = if (dateKey == today) "$dateKey（今日）" else dateKey

                        Surface(
                            tonalElevation = 2.dp,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { expandedState[dateKey] = !expanded }
                                ) {
                                    Icon(
                                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = "展開摺疊",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(displayDate, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text(
                                            "${sessionItems.size} 句" + if (starredCount > 0) " · ⭐ $starredCount" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (hasSavedNote) {
                                        IconButton(onClick = { onViewSavedSummary(dateKey) }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Description, contentDescription = "查看筆記", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    IconButton(onClick = { onExportSessionMd(dateKey, sessionItems) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Default.Download, contentDescription = "匯出 .md 筆記", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }
                                    FilledTonalButton(
                                        onClick = { onSummarizeSession(dateKey, sessionItems) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text("總結", style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { onDeleteSession(dateKey) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "刪除此場次", modifier = Modifier.size(16.dp))
                                    }
                                }

                                if (expanded) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), thickness = 0.5.dp)
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        sessionItems.forEach { item ->
                                            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    val meta = buildString {
                                                        append("${item.time} · ${item.langCode}")
                                                        if (item.profileName.isNotBlank()) append(" · ${item.profileName}")
                                                    }
                                                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                    if (item.original.isNotBlank()) {
                                                        Text(item.original, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    Text(item.translated, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                                }
                                                IconButton(onClick = { onExplainItem(item) }, modifier = Modifier.size(30.dp)) {
                                                    Icon(Icons.Default.School, contentDescription = "文法／潮語拆解", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                                }
                                                if (item.original.isNotBlank()) {
                                                    IconButton(
                                                        onClick = { onSpeakOriginal(item.original, item.langCode) },
                                                        modifier = Modifier.size(30.dp)
                                                    ) {
                                                        Icon(Icons.Default.VolumeUp, contentDescription = "朗讀原文發音", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                                IconButton(onClick = { onToggleStar(item.id) }, modifier = Modifier.size(30.dp)) {
                                                    Icon(
                                                        if (item.starred) Icons.Default.Star else Icons.Default.StarBorder,
                                                        contentDescription = "星號收藏",
                                                        tint = if (item.starred) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                }
                                                IconButton(onClick = { onCopyText("${item.original}\n${item.translated}".trim()) }, modifier = Modifier.size(30.dp)) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "複製", modifier = Modifier.size(15.dp))
                                                }
                                            }
                                            HorizontalDivider(thickness = 0.4.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickInteractPanel(
    settings: AppSettings,
    activeProfile: StreamerProfile?,
    phrases: List<QuickPhrase>,
    onCopyText: (String) -> Unit,
    onSpeakOriginal: (String, String) -> Unit,
    onAddPhrase: (String, String, String) -> Unit,
    onDeletePhrase: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var output by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        var newText by remember { mutableStateOf("") }
        var newMeaning by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增常用互動短句（同步懸浮快貼列）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newText, onValueChange = { newText = it }, label = { Text("直播留言內容（如日文/英文）") }, singleLine = true)
                    OutlinedTextField(value = newMeaning, onValueChange = { newMeaning = it }, label = { Text("中文意思備註") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newText.isNotBlank()) {
                        onAddPhrase(newText, newMeaning.ifBlank { "自訂短句" }, settings.sourceLanguage.shortCode)
                        showAddDialog = false
                    }
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AI 留言翻譯（目標：${settings.sourceLanguage.label}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("提示：睇直播時直接按住懸浮框「🎤按講」講廣東話，放手即翻日文並自動複製！", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("輸入你想同主播講嘅嘢（廣東話/中文）") },
                    placeholder = { Text("例：今日你把聲好好聽呀，香港支持你！") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
                Button(
                    onClick = {
                        if (input.isBlank()) return@Button
                        loading = true
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { GeminiApi.translateReply(context, input, settings, activeProfile) }
                            loading = false
                            res.onSuccess { txt -> output = txt; onCopyText(txt) }
                                .onFailure { output = "翻譯失敗：${it.message}" }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "翻譯中…" else "翻成地道留言並自動複製")
                }
                if (output.isNotBlank()) {
                    Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(output, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onSpeakOriginal(output, settings.sourceLanguage.shortCode) }) { Icon(Icons.Default.VolumeUp, "試聽發音") }
                            IconButton(onClick = { onCopyText(output) }) { Icon(Icons.Default.ContentCopy, "複製") }
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("常用互動短句快捷板", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("點文字複製 · 點喇叭聽發音 · 同步懸浮「💬快貼」", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增")
            }
        }

        val filteredPhrases = phrases.filter {
            settings.sourceLanguage == SourceLanguage.AUTO || it.langCode == settings.sourceLanguage.shortCode || it.langCode == "JA"
        }

        filteredPhrases.forEach { p ->
            ElevatedCard(
                onClick = { onCopyText(p.text) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(p.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onSpeakOriginal(p.text, p.langCode) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "試聽", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    }
                    Icon(Icons.Default.ContentCopy, contentDescription = "複製", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    if (p.id > 100) {
                        IconButton(onClick = { onDeletePhrase(p.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "刪除", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    settings: AppSettings,
    currentRms: Int,
    profiles: List<StreamerProfile>,
    onSave: (AppSettings) -> Unit,
    onTestDubbingVoice: () -> Unit,
    onOpenBalanceDialog: () -> Unit,
    onAddProfileClick: () -> Unit,
    onEditProfile: (StreamerProfile) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onOpenImport: () -> Unit
) {
    val aiVoices = listOf("Aoede", "Puck", "Kore", "Charon", "Fenrir")

    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = settings.apiKey, onValueChange = { onSave(settings.copy(apiKey = it)) },
            label = { Text("Gemini API Key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        // API 帳單餘額與顯示設定
        HorizontalDivider()
        Text("API 帳單餘額與實時費用顯示", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("懸浮字幕框實時顯示本場費用與餘額", modifier = Modifier.weight(1f))
            Switch(checked = settings.showCostOnOverlay, onCheckedChange = { onSave(settings.copy(showCostOnOverlay = it)) })
        }
        OutlinedButton(onClick = onOpenBalanceDialog, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("管理預付餘額 / 登記充值 / 查看 Token 明細")
        }

        HorizontalDivider()
        Text("即時廣東話語音配音（同聲傳譯模式）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("即時讀出廣東話翻譯語音", modifier = Modifier.weight(1f))
            Switch(checked = settings.liveDubbing, onCheckedChange = { onSave(settings.copy(liveDubbing = it)) })
        }

        Text("配音引擎選擇", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DubbingEngineMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.dubbingEngine == mode,
                    onClick = { onSave(settings.copy(dubbingEngine = mode)) },
                    label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        if (settings.dubbingEngine == DubbingEngineMode.AI_NATURAL) {
            Text("AI 自然人聲聲線", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(aiVoices) { voice ->
                    FilterChip(
                        selected = settings.aiVoiceName == voice,
                        onClick = { onSave(settings.copy(aiVoiceName = voice)) },
                        label = { Text(voice, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("配音時自動壓低直播原聲 (Ducking)", modifier = Modifier.weight(1f))
            Switch(checked = settings.dubbingDuckOriginal, onCheckedChange = { onSave(settings.copy(dubbingDuckOriginal = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("獨立聲道輸出（避開媒體低音量）", modifier = Modifier.weight(1f))
            Switch(checked = settings.dubbingIndependentChannel, onCheckedChange = { onSave(settings.copy(dubbingIndependentChannel = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("極低原聲微音放大（配細聲直播用）", modifier = Modifier.weight(1f))
            Switch(checked = settings.lowVolumeBoost, onCheckedChange = { onSave(settings.copy(lowVolumeBoost = it)) })
        }
        Text(String.format(Locale.US, "配音朗讀語速：%.2fx（建議 1.2x – 1.4x 追貼語速）", settings.dubbingSpeed), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = settings.dubbingSpeed,
            onValueChange = { onSave(settings.copy(dubbingSpeed = (it * 20).toInt() / 20f)) },
            valueRange = 0.9f..1.8f, steps = 17
        )
        OutlinedButton(onClick = onTestDubbingVoice) {
            Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("試聽目前廣東話配音效果")
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("多主播詞彙設定檔（Profiles）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = onAddProfileClick) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增")
            }
        }
        profiles.forEach { prof ->
            Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${prof.name} (${prof.language.shortCode})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(prof.glossary.ifBlank { "未設定專屬詞彙" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    IconButton(onClick = { onEditProfile(prof) }) { Icon(Icons.Default.Edit, "編輯", modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { onDeleteProfile(prof.id) }) { Icon(Icons.Default.Delete, "刪除", modifier = Modifier.size(18.dp)) }
                }
            }
        }

        HorizontalDivider()
        Text("一鍵效能預設（速度與準確度）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedButton(onClick = {
                    onSave(settings.copy(model = "gemini-2.5-flash", smartVad = true, pauseMs = 300, overlapMs = 400, chunkSeconds = 4, autoGain = true))
                }) { Text("⚡ 極速低延遲") }
            }
            item {
                OutlinedButton(onClick = {
                    onSave(settings.copy(model = "gemini-2.5-flash", smartVad = true, pauseMs = 350, overlapMs = 500, chunkSeconds = 5, autoGain = true))
                }) { Text("⚖ 平衡推薦") }
            }
            item {
                OutlinedButton(onClick = {
                    onSave(settings.copy(model = "gemini-2.5-flash", smartVad = true, pauseMs = 450, overlapMs = 600, chunkSeconds = 6, autoGain = true))
                }) { Text("🎯 超高準度") }
            }
        }

        OutlinedTextField(
            value = settings.baseUrl, onValueChange = { onSave(settings.copy(baseUrl = it)) },
            label = { Text("API Base URL（開 VPN 直連不用改）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        Text("翻譯輸出語言／風格", style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TranslationStyle.entries) { st ->
                FilterChip(selected = settings.style == st, onClick = { onSave(settings.copy(style = st)) }, label = { Text(st.label) })
            }
        }

        HorizontalDivider()
        Text("智能收音與字幕設定", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("日文漢字自動加註平假名（振り仮名）", modifier = Modifier.weight(1f))
            Switch(checked = settings.showFurigana, onCheckedChange = { onSave(settings.copy(showFurigana = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("智能語音停頓斷句 (Smart VAD)", modifier = Modifier.weight(1f))
            Switch(checked = settings.smartVad, onCheckedChange = { onSave(settings.copy(smartVad = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自動人聲增益 (AGC)", modifier = Modifier.weight(1f))
            Switch(checked = settings.autoGain, onCheckedChange = { onSave(settings.copy(autoGain = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("懸浮框預設鎖定位置（亦支援雙擊切換）", modifier = Modifier.weight(1f))
            Switch(checked = settings.overlayLocked, onCheckedChange = { onSave(settings.copy(overlayLocked = it)) })
        }
        if (settings.smartVad) {
            Text("停頓觸發：${settings.pauseMs}ms", style = MaterialTheme.typography.bodySmall)
            Slider(value = settings.pauseMs.toFloat(), onValueChange = { onSave(settings.copy(pauseMs = (it.toInt() / 50) * 50)) }, valueRange = 250f..800f, steps = 10)
        }
        Text(String.format(Locale.US, "防漏字重疊緩衝：%.1f 秒", settings.overlapMs / 1000.0), style = MaterialTheme.typography.bodySmall)
        Slider(value = settings.overlapMs.toFloat(), onValueChange = { onSave(settings.copy(overlapMs = (it.toInt() / 100) * 100)) }, valueRange = 0f..1000f, steps = 9)
        Text("最長單句上限：${settings.chunkSeconds} 秒", style = MaterialTheme.typography.bodySmall)
        Slider(value = settings.chunkSeconds.toFloat(), onValueChange = { onSave(settings.copy(chunkSeconds = it.toInt())) }, valueRange = 3f..8f, steps = 4)
        Text("靜音過濾門檻：${settings.silenceThreshold}（目前音量：$currentRms）", style = MaterialTheme.typography.bodySmall)
        Slider(value = settings.silenceThreshold.toFloat(), onValueChange = { onSave(settings.copy(silenceThreshold = (it.toInt() / 20) * 20)) }, valueRange = 100f..600f, steps = 24)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("懸浮框顯示雙語原文", modifier = Modifier.weight(1f))
            Switch(checked = settings.showOriginal, onCheckedChange = { onSave(settings.copy(showOriginal = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("保持螢幕常亮（防休眠）", modifier = Modifier.weight(1f))
            Switch(checked = settings.keepScreenOn, onCheckedChange = { onSave(settings.copy(keepScreenOn = it)) })
        }
        OutlinedTextField(
            value = settings.glossary, onValueChange = { onSave(settings.copy(glossary = it)) },
            label = { Text("全域補充詞彙表（對所有主播生效）") },
            modifier = Modifier.fillMaxWidth(), minLines = 2
        )

        HorizontalDivider()
        Text("資料匯出與匯入（手機／電腦互通）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportJson) { Text("匯出 JSON") }
            OutlinedButton(onClick = onExportCsv) { Text("匯出 CSV") }
            OutlinedButton(onClick = onOpenImport) { Text("匯入 JSON") }
        }
    }
}
