package com.npl.livejptranslator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var pendingImportOverwrite = false
    private var pendingMarkdownContent = ""

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        proceedStartCapture()
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val svc = Intent(this, TranslationService::class.java).apply {
                action = TranslationService.ACTION_START
                putExtra(TranslationService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(TranslationService.EXTRA_RESULT_DATA, res.data)
            }
            startForegroundService(svc)
        } else {
            Toast.makeText(this, "需要允許擷取畫面聲音才能內錄翻譯", Toast.LENGTH_SHORT).show()
        }
    }

    private val exportJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os -> os.write(StorageHelper.exportJson().toByteArray()) }
            Toast.makeText(this, "已匯出 JSON", Toast.LENGTH_SHORT).show()
        }
    }

    private val exportCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os -> os.write(StorageHelper.exportCsv().toByteArray()) }
            Toast.makeText(this, "已匯出 CSV", Toast.LENGTH_SHORT).show()
        }
    }

    private val exportMdLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os -> os.write(pendingMarkdownContent.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "已匯出 Markdown (.md) 筆記！", Toast.LENGTH_SHORT).show()
        }
    }

    private val importJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val raw = contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: return@let
            StorageHelper.importJson(this, raw, pendingImportOverwrite)
                .onSuccess { n -> Toast.makeText(this, "成功匯入 $n 筆紀錄", Toast.LENGTH_SHORT).show() }
                .onFailure { e -> Toast.makeText(this, "匯入失敗：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StorageHelper.load(this)
        TtsManager.init(this)
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val settings by TranslatorState.settings.collectAsState()
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LiveJPTheme(settings.themeMode) {
                MainAppScreen(
                    widthSizeClass = windowSizeClass.widthSizeClass,
                    onToggleStart = { handleToggle() },
                    onCopyText = { txt ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("LiveTranslator", txt))
                        Toast.makeText(this, "已複製到剪貼簿！", Toast.LENGTH_SHORT).show()
                    },
                    onSpeakOriginal = { text, langCode ->
                        val ok = TtsManager.speakOriginal(this, text, langCode)
                        if (!ok) Toast.makeText(this, "語音引擎初始化中，請稍後再試", Toast.LENGTH_SHORT).show()
                    },
                    onTestDubbingVoice = {
                        TtsManager.speakDubbing(
                            this,
                            "哈囉！廣東話即時語音配音已經準備好啦，而家可以邊睇日本直播邊聽廣東話！",
                            TranslatorState.settings.value
                        )
                    },
                    onToggleStar = { id -> TranslatorState.toggleStar(this, id) },
                    onDeleteSession = { dateKey -> TranslatorState.deleteSession(this, dateKey) },
                    onAddPhrase = { t, m, l -> TranslatorState.addPhrase(this, t, m, l) },
                    onDeletePhrase = { id -> TranslatorState.deletePhrase(this, id) },
                    onSelectProfile = { prof -> TranslatorState.selectProfile(this, prof) },
                    onUpsertProfile = { prof -> TranslatorState.upsertProfile(this, prof) },
                    onDeleteProfile = { id -> TranslatorState.deleteProfile(this, id) },
                    onSaveSummary = { k, sum -> TranslatorState.saveSessionSummary(this, k, sum) },
                    onImportCardsFromSummary = { sum, lang, key -> TranslatorState.importFlashcardsFromSummary(this, sum, lang, key) },
                    onMarkCardMastered = { id -> TranslatorState.markFlashcardMastered(this, id) },
                    onDeleteCard = { id -> TranslatorState.deleteFlashcard(this, id) },
                    onExportSessionMd = { sessionKey, items ->
                        val summary = TranslatorState.sessionSummaries.value[sessionKey] ?: ""
                        pendingMarkdownContent = StorageHelper.exportSessionMarkdown(sessionKey, items, summary)
                        val safeKey = sessionKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        exportMdLauncher.launch("LiveNotes_${safeKey}.md")
                    },
                    onClearHistory = { TranslatorState.clearHistory(this) },
                    onExportJson = {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                        exportJsonLauncher.launch("LiveTranslator_$ts.json")
                    },
                    onExportCsv = {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                        exportCsvLauncher.launch("LiveTranslator_$ts.csv")
                    },
                    onImportJson = { overwrite ->
                        pendingImportOverwrite = overwrite
                        importJsonLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                    onSaveSettings = { StorageHelper.saveSettings(this, it) }
                )
            }
        }
    }

    private fun handleToggle() {
        if (TranslatorState.isRunning.value) {
            startService(Intent(this, TranslationService::class.java).apply { action = TranslationService.ACTION_STOP })
            return
        }
        if (TranslatorState.settings.value.apiKey.isBlank()) {
            Toast.makeText(this, "請先到「設定」頁貼上 Gemini API Key", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請開啟「顯示在其他應用程式上層」權限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun proceedStartCapture() {
        if (TranslatorState.settings.value.captureMode == CaptureMode.INTERNAL) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            startForegroundService(Intent(this, TranslationService::class.java).apply { action = TranslationService.ACTION_START })
        }
    }
}
