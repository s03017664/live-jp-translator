package com.npl.livejptranslator

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CaptureMode { INTERNAL, MICROPHONE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class DubbingEngineMode(val label: String) {
    LOCAL_TTS("本機極速語音 (TTS)"),
    AI_NATURAL("AI 自然人聲配音 (Gemini Native Audio)")
}

enum class SourceLanguage(val label: String, val shortCode: String, val promptHint: String) {
    JAPANESE("日語（日本直播）", "JA", "日語（包含日本直播常用口語、縮寫、網絡潮語、粉絲互動語氣）"),
    AUTO("自動偵測（多語言）", "AUTO", "自動偵測講者的語言（可能是日語、英語、韓語、泰語、法語、西班牙語或國語等）"),
    ENGLISH("英語 (English)", "EN", "英語（包含日常口語、直播 slang 與慣用語）"),
    KOREAN("韓語 (한국어)", "KO", "韓語（包含韓國直播口語與流行語）"),
    THAI("泰語 (ภาษาไทย)", "TH", "泰語（包含日常直播口語）"),
    MANDARIN("國語／普通話", "ZH", "國語／普通話"),
    FRENCH("法語 (Français)", "FR", "法語口語"),
    SPANISH("西班牙語 (Español)", "ES", "西班牙語口語")
}

enum class TranslationStyle(val label: String, val instruction: String) {
    CANTONESE("地道香港廣東話", "翻譯成自然地道的香港廣東話口語（繁體字），保留直播主同觀眾傾偈嘅語氣、笑聲同潮語。"),
    TRADITIONAL_CHINESE("自然繁體中文", "翻譯成自然流暢的繁體中文書面口語，保留直播互動語氣。"),
    ENGLISH("Natural English", "Translate into natural, conversational English preserving the livestream tone.")
}

enum class OverlayOpacity(val label: String, val bgHex: String) {
    SOLID("全黑", "#F00E1320"),
    MEDIUM("半透", "#C4121624"),
    LIGHT("輕透", "#78121624");

    fun next(): OverlayOpacity = entries[(ordinal + 1) % entries.size]
}

data class QuickPhrase(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val meaning: String,
    val langCode: String = "JA"
)

data class StreamerProfile(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val glossary: String,
    val language: SourceLanguage = SourceLanguage.JAPANESE
)

data class FlashcardItem(
    val id: Long = System.currentTimeMillis(),
    val term: String,
    val reading: String,
    val meaning: String,
    val example: String = "",
    val langCode: String = "JA",
    val sourceTag: String = "收藏金句",
    val masteredCount: Int = 0
)

data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val model: String = "gemini-2.5-flash",
    val sourceLanguage: SourceLanguage = SourceLanguage.JAPANESE,
    val style: TranslationStyle = TranslationStyle.CANTONESE,
    val activeProfileId: Long = 1L,
    val smartVad: Boolean = true,
    val pauseMs: Int = 350,
    val overlapMs: Int = 500,
    val chunkSeconds: Int = 5,
    val silenceThreshold: Int = 200,
    val autoGain: Boolean = true,
    val showFurigana: Boolean = true,
    val liveDubbing: Boolean = false,
    val dubbingEngine: DubbingEngineMode = DubbingEngineMode.LOCAL_TTS,
    val aiVoiceName: String = "Aoede",
    val dubbingSpeed: Float = 1.25f,
    val dubbingDuckOriginal: Boolean = true,
    val dubbingIndependentChannel: Boolean = true,
    val lowVolumeBoost: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.INTERNAL,
    val showOriginal: Boolean = true,
    val overlayOpacity: OverlayOpacity = OverlayOpacity.MEDIUM,
    val overlayLocked: Boolean = false,
    val glossary: String = "",
    val overlayFontSize: Float = 17f,
    val keepScreenOn: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // API 實時帳單與餘額設定（預設 HK$40.00，可自行加值或調整匯率）
    val initialBalanceHkd: Double = 40.0,
    val monthlyProCreditUsd: Double = 0.0,
    val usdToHkdRate: Double = 7.80,
    val showCostOnOverlay: Boolean = true
)

data class TranscriptItem(
    val id: Long = System.currentTimeMillis(),
    val date: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
    val time: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    val original: String = "",
    val translated: String = "",
    val langCode: String = "JA",
    val profileName: String = "",
    val starred: Boolean = false
)

data class ApiUsageRecord(
    val date: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
    val secondsTranslated: Int = 0,
    val requests: Int = 0,
    // 今日即時累積
    val todayPromptTokens: Long = 0L,
    val todayOutputTokens: Long = 0L,
    val todayCostUsd: Double = 0.0,
    // 直播場次即時累積（每次啟動翻譯歸零重新計算本場花費）
    val sessionCostUsd: Double = 0.0,
    val sessionRequests: Int = 0,
    // 自設定餘額基準以來的總累計花費（用於即時扣減餘額）
    val totalSpentUsd: Double = 0.0,
    val totalPromptTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val lastRequestCostUsd: Double = 0.0,
    val lastTokenBreakdown: String = ""
) {
    fun todayCostHkd(rate: Double): Double = todayCostUsd * rate
    fun sessionCostHkd(rate: Double): Double = sessionCostUsd * rate
    fun totalSpentHkd(rate: Double): Double = totalSpentUsd * rate

    fun remainingCreditHkd(s: AppSettings): Double {
        val totalPoolHkd = s.initialBalanceHkd + (s.monthlyProCreditUsd * s.usdToHkdRate)
        return (totalPoolHkd - totalSpentHkd(s.usdToHkdRate)).coerceAtLeast(0.0)
    }

    fun remainingPercent(s: AppSettings): Float {
        val totalPoolHkd = s.initialBalanceHkd + (s.monthlyProCreditUsd * s.usdToHkdRate)
        if (totalPoolHkd <= 0.0) return 0f
        return (remainingCreditHkd(s) / totalPoolHkd).toFloat().coerceIn(0f, 1f)
    }
}

object PricingCalculator {
    // 依據 Google AI Studio 官方標準單價（每百萬 tokens USD）精準計算
    fun calculateUsd(
        model: String,
        promptTokens: Int,
        outputTokens: Int,
        audioInputTokens: Int = 0,
        isAudioOutput: Boolean = false
    ): Double {
        val m = model.lowercase(Locale.US)
        val textPromptTokens = (promptTokens - audioInputTokens).coerceAtLeast(0)

        return when {
            isAudioOutput || m.contains("native-audio") -> {
                val audioInRate = 3.00 / 1_000_000.0
                val textInRate = 0.50 / 1_000_000.0
                val outRate = 2.00 / 1_000_000.0
                (audioInputTokens * audioInRate) + (textPromptTokens * textInRate) + (outputTokens * outRate)
            }
            m.contains("flash-lite") && m.contains("3.1") -> {
                val inRate = 0.25 / 1_000_000.0
                val outRate = 1.50 / 1_000_000.0
                (promptTokens * inRate) + (outputTokens * outRate)
            }
            m.contains("flash-lite") -> {
                // gemini-2.5-flash-lite: $0.10 / 1M input, $0.40 / 1M output
                val inRate = 0.10 / 1_000_000.0
                val outRate = 0.40 / 1_000_000.0
                (promptTokens * inRate) + (outputTokens * outRate)
            }
            m.contains("pro") -> {
                val inRate = 1.25 / 1_000_000.0
                val outRate = 10.00 / 1_000_000.0
                (promptTokens * inRate) + (outputTokens * outRate)
            }
            else -> {
                // gemini-2.5-flash: $0.30 / 1M text input, $1.00 / 1M audio input, $2.50 / 1M output
                val audioInRate = 1.00 / 1_000_000.0
                val textInRate = 0.30 / 1_000_000.0
                val outRate = 2.50 / 1_000_000.0
                (audioInputTokens * audioInRate) + (textPromptTokens * textInRate) + (outputTokens * outRate)
            }
        }
    }
}

object DefaultPhrases {
    val list = listOf(
        QuickPhrase(1, "初見です！よろしくお願いします！", "第一次看！請多指教", "JA"),
        QuickPhrase(2, "こんにちは！", "你好！", "JA"),
        QuickPhrase(3, "こんばんは！", "晚上好！", "JA"),
        QuickPhrase(4, "草", "好好笑 (笑死)", "JA"),
        QuickPhrase(5, "かわいい！", "好可愛！", "JA"),
        QuickPhrase(6, "香港から見てます！🇭🇰", "我喺香港睇緊！", "JA"),
        QuickPhrase(7, "応援してます！頑張って！", "支持你！加油！", "JA"),
        QuickPhrase(8, "声めっちゃ好きです！", "好鍾意你把聲！", "JA"),
        QuickPhrase(9, "すごい！！", "好勁呀！！", "JA"),
        QuickPhrase(10, "お疲れ様でした！また来ます！", "辛苦了！下次再來", "JA"),
        QuickPhrase(11, "Hello from Hong Kong! 🇭🇰", "香港觀眾打招呼", "EN"),
        QuickPhrase(12, "Love this stream! Keep it up!", "好鍾意呢個直播！加油！", "EN"),
        QuickPhrase(13, "안녕하세요! 홍콩에서 보고 있어요!", "你好！我喺香港睇緊！", "KO"),
        QuickPhrase(14, "화이팅!! 응원해요!", "加油！！支持你！", "KO")
    )
}

object DefaultProfiles {
    val list = listOf(
        StreamerProfile(
            id = 1L,
            name = "ミクチャ／日語雜談台",
            glossary = "ミクチャ(MixChannel)、ふわっち、初見(第一次看)、枠(直播間)、コメント(留言)、アイテム(禮物)、潜る(潛水聽播)、推し(推的主播)",
            language = SourceLanguage.JAPANESE
        )
    )
}

object TranslatorState {
    val isRunning = MutableStateFlow(false)
    val isPaused = MutableStateFlow(false)
    val statusText = MutableStateFlow("準備就緒")
    val lastApiError = MutableStateFlow("")
    val currentRms = MutableStateFlow(0)
    val latestItemId = MutableStateFlow<Long?>(null)
    val latestStarred = MutableStateFlow(false)
    val latestOriginal = MutableStateFlow("")
    val latestTranslated = MutableStateFlow("按下方按鈕啟動懸浮字幕，再去開直播 App")
    val history = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val settings = MutableStateFlow(AppSettings())
    val usage = MutableStateFlow(ApiUsageRecord())
    val quickPhrases = MutableStateFlow<List<QuickPhrase>>(DefaultPhrases.list)
    val profiles = MutableStateFlow<List<StreamerProfile>>(DefaultProfiles.list)
    val sessionSummaries = MutableStateFlow<Map<String, String>>(emptyMap())
    val flashcards = MutableStateFlow<List<FlashcardItem>>(emptyList())

    fun activeProfile(): StreamerProfile? =
        profiles.value.firstOrNull { it.id == settings.value.activeProfileId }

    fun resetSessionCounter(context: Context) {
        val updated = usage.value.copy(sessionCostUsd = 0.0, sessionRequests = 0)
        usage.value = updated
        StorageHelper.saveUsage(context, updated)
    }

    fun topUpBalanceHkd(context: Context, addHkd: Double) {
        val cur = settings.value
        val updated = cur.copy(initialBalanceHkd = (cur.initialBalanceHkd + addHkd).coerceAtLeast(0.0))
        StorageHelper.saveSettings(context, updated)
    }

    fun resetBalanceLedger(context: Context, newBalanceHkd: Double) {
        val curSettings = settings.value.copy(initialBalanceHkd = newBalanceHkd.coerceAtLeast(0.0))
        StorageHelper.saveSettings(context, curSettings)
        val curUsage = usage.value.copy(
            totalSpentUsd = 0.0,
            totalPromptTokens = 0L,
            totalOutputTokens = 0L,
            todayCostUsd = 0.0,
            todayPromptTokens = 0L,
            todayOutputTokens = 0L,
            sessionCostUsd = 0.0,
            sessionRequests = 0
        )
        usage.value = curUsage
        StorageHelper.saveUsage(context, curUsage)
    }

    fun recordApiTokens(
        context: Context,
        model: String,
        promptTokens: Int,
        outputTokens: Int,
        audioInputTokens: Int = 0,
        secondsTranslated: Int = 0,
        isAudioOutput: Boolean = false
    ) {
        val costUsd = PricingCalculator.calculateUsd(model, promptTokens, outputTokens, audioInputTokens, isAudioOutput)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val cur = usage.value
        val isSameDay = cur.date == today
        val breakdown = "In:${promptTokens}t / Out:${outputTokens}t"

        val updated = ApiUsageRecord(
            date = today,
            secondsTranslated = (if (isSameDay) cur.secondsTranslated else 0) + secondsTranslated,
            requests = (if (isSameDay) cur.requests else 0) + 1,
            todayPromptTokens = (if (isSameDay) cur.todayPromptTokens else 0L) + promptTokens,
            todayOutputTokens = (if (isSameDay) cur.todayOutputTokens else 0L) + outputTokens,
            todayCostUsd = (if (isSameDay) cur.todayCostUsd else 0.0) + costUsd,
            sessionCostUsd = cur.sessionCostUsd + costUsd,
            sessionRequests = cur.sessionRequests + 1,
            totalSpentUsd = cur.totalSpentUsd + costUsd,
            totalPromptTokens = cur.totalPromptTokens + promptTokens,
            totalOutputTokens = cur.totalOutputTokens + outputTokens,
            lastRequestCostUsd = costUsd,
            lastTokenBreakdown = breakdown
        )
        usage.value = updated
        StorageHelper.saveUsage(context, updated)
    }

    fun addItem(item: TranscriptItem) {
        latestItemId.value = item.id
        latestStarred.value = item.starred
        latestOriginal.value = item.original
        latestTranslated.value = item.translated
        history.value = listOf(item) + history.value.take(999)
    }

    fun toggleStar(context: Context, id: Long): Boolean {
        var newState = false
        var targetItem: TranscriptItem? = null
        history.value = history.value.map {
            if (it.id == id) {
                newState = !it.starred
                val updated = it.copy(starred = newState)
                targetItem = updated
                updated
            } else it
        }
        if (latestItemId.value == id) {
            latestStarred.value = newState
        }
        if (newState && targetItem != null) {
            addFlashcardFromTranscript(context, targetItem!!)
        }
        StorageHelper.saveHistory(context)
        return newState
    }

    fun addFlashcardFromTranscript(context: Context, item: TranscriptItem) {
        val cleanTerm = TtsManager.stripFurigana(item.original).ifBlank { item.translated }
        if (cleanTerm.isBlank()) return
        if (flashcards.value.any { it.term == cleanTerm }) return
        val card = FlashcardItem(
            id = item.id,
            term = cleanTerm,
            reading = item.original,
            meaning = item.translated,
            example = item.original,
            langCode = item.langCode,
            sourceTag = "⭐ 收藏金句"
        )
        flashcards.value = listOf(card) + flashcards.value.take(299)
        StorageHelper.saveFlashcards(context)
    }

    fun importFlashcardsFromSummary(context: Context, summaryText: String, langCode: String, sessionKey: String) {
        if (summaryText.isBlank()) return
        val added = mutableListOf<FlashcardItem>()
        summaryText.lines().forEachIndexed { idx, line ->
            val trimmed = line.trim().removePrefix("-").removePrefix("*").trim()
            if (trimmed.contains("｜")) {
                val parts = trimmed.split("｜")
                val rawLeft = parts.getOrNull(0)?.trim().orEmpty()
                val meaning = parts.getOrNull(1)?.trim().orEmpty()
                val usageNote = parts.getOrNull(2)?.trim().orEmpty()
                val termOnly = TtsManager.stripFurigana(rawLeft)
                    .replace(Regex("""^[0-9]+\.?\s*"""), "")
                    .trim()
                if (termOnly.isNotBlank() && meaning.isNotBlank() && flashcards.value.none { it.term == termOnly }) {
                    added.add(
                        FlashcardItem(
                            id = System.currentTimeMillis() + idx,
                            term = termOnly,
                            reading = rawLeft,
                            meaning = meaning,
                            example = usageNote,
                            langCode = langCode,
                            sourceTag = "🗣️ $sessionKey 筆記"
                        )
                    )
                }
            }
        }
        if (added.isNotEmpty()) {
            flashcards.value = added + flashcards.value.take(299)
            StorageHelper.saveFlashcards(context)
        }
    }

    fun markFlashcardMastered(context: Context, id: Long) {
        flashcards.value = flashcards.value.map {
            if (it.id == id) it.copy(masteredCount = it.masteredCount + 1) else it
        }
        StorageHelper.saveFlashcards(context)
    }

    fun deleteFlashcard(context: Context, id: Long) {
        flashcards.value = flashcards.value.filterNot { it.id == id }
        StorageHelper.saveFlashcards(context)
    }

    fun toggleLatestStar(context: Context): Boolean? {
        val targetId = latestItemId.value ?: history.value.firstOrNull()?.id ?: return null
        latestItemId.value = targetId
        return toggleStar(context, targetId)
    }

    fun deleteSession(context: Context, dateKey: String) {
        history.value = history.value.filterNot { it.date.ifBlank { "較早紀錄" } == dateKey }
        StorageHelper.saveHistory(context)
    }

    fun addPhrase(context: Context, text: String, meaning: String, langCode: String) {
        val item = QuickPhrase(System.currentTimeMillis(), text.trim(), meaning.trim(), langCode)
        quickPhrases.value = listOf(item) + quickPhrases.value
        StorageHelper.savePhrases(context)
    }

    fun deletePhrase(context: Context, id: Long) {
        quickPhrases.value = quickPhrases.value.filterNot { it.id == id }
        StorageHelper.savePhrases(context)
    }

    fun upsertProfile(context: Context, profile: StreamerProfile) {
        val exists = profiles.value.any { it.id == profile.id }
        profiles.value = if (exists) {
            profiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            listOf(profile) + profiles.value
        }
        StorageHelper.saveProfiles(context)
    }

    fun deleteProfile(context: Context, id: Long) {
        profiles.value = profiles.value.filterNot { it.id == id }
        if (settings.value.activeProfileId == id) {
            StorageHelper.saveSettings(context, settings.value.copy(activeProfileId = 0L))
        }
        StorageHelper.saveProfiles(context)
    }

    fun selectProfile(context: Context, profile: StreamerProfile?) {
        val cur = settings.value
        val updated = if (profile == null) {
            cur.copy(activeProfileId = 0L)
        } else {
            cur.copy(activeProfileId = profile.id, sourceLanguage = profile.language)
        }
        StorageHelper.saveSettings(context, updated)
    }

    fun saveSessionSummary(context: Context, sessionKey: String, text: String) {
        val next = sessionSummaries.value.toMutableMap()
        next[sessionKey] = text
        sessionSummaries.value = next
        StorageHelper.saveSummaries(context)
    }

    fun clearHistory(context: Context) {
        history.value = emptyList()
        latestItemId.value = null
        latestStarred.value = false
        StorageHelper.saveHistory(context)
    }
}

object StorageHelper {
    private const val PREF = "live_jp_translator_prefs"

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        TranslatorState.settings.value = AppSettings(
            apiKey = sp.getString("apiKey", "") ?: "",
            baseUrl = sp.getString("baseUrl", "https://generativelanguage.googleapis.com") ?: "https://generativelanguage.googleapis.com",
            model = sp.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash",
            sourceLanguage = runCatching { SourceLanguage.valueOf(sp.getString("sourceLanguage", SourceLanguage.JAPANESE.name)!!) }.getOrDefault(SourceLanguage.JAPANESE),
            style = runCatching { TranslationStyle.valueOf(sp.getString("style", TranslationStyle.CANTONESE.name)!!) }.getOrDefault(TranslationStyle.CANTONESE),
            activeProfileId = sp.getLong("activeProfileId", 1L),
            smartVad = sp.getBoolean("smartVad", true),
            pauseMs = sp.getInt("pauseMs", 350),
            overlapMs = sp.getInt("overlapMs", 500),
            chunkSeconds = sp.getInt("chunkSeconds", 5),
            silenceThreshold = sp.getInt("silenceThreshold", 200),
            autoGain = sp.getBoolean("autoGain", true),
            showFurigana = sp.getBoolean("showFurigana", true),
            liveDubbing = sp.getBoolean("liveDubbing", false),
            dubbingEngine = runCatching { DubbingEngineMode.valueOf(sp.getString("dubbingEngine", DubbingEngineMode.LOCAL_TTS.name)!!) }.getOrDefault(DubbingEngineMode.LOCAL_TTS),
            aiVoiceName = sp.getString("aiVoiceName", "Aoede") ?: "Aoede",
            dubbingSpeed = sp.getFloat("dubbingSpeed", 1.25f),
            dubbingDuckOriginal = sp.getBoolean("dubbingDuckOriginal", true),
            dubbingIndependentChannel = sp.getBoolean("dubbingIndependentChannel", true),
            lowVolumeBoost = sp.getBoolean("lowVolumeBoost", false),
            captureMode = runCatching { CaptureMode.valueOf(sp.getString("captureMode", CaptureMode.INTERNAL.name)!!) }.getOrDefault(CaptureMode.INTERNAL),
            showOriginal = sp.getBoolean("showOriginal", sp.getBoolean("showJapanese", true)),
            overlayOpacity = runCatching { OverlayOpacity.valueOf(sp.getString("overlayOpacity", OverlayOpacity.MEDIUM.name)!!) }.getOrDefault(OverlayOpacity.MEDIUM),
            overlayLocked = sp.getBoolean("overlayLocked", false),
            glossary = sp.getString("glossary", "") ?: "",
            overlayFontSize = sp.getFloat("overlayFontSize", 17f),
            keepScreenOn = sp.getBoolean("keepScreenOn", true),
            themeMode = runCatching { ThemeMode.valueOf(sp.getString("themeMode", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM),
            initialBalanceHkd = Double.fromBits(sp.getLong("initialBalanceHkdBits", java.lang.Double.doubleToRawLongBits(40.0))),
            monthlyProCreditUsd = Double.fromBits(sp.getLong("monthlyProCreditUsdBits", java.lang.Double.doubleToRawLongBits(0.0))),
            usdToHkdRate = Double.fromBits(sp.getLong("usdToHkdRateBits", java.lang.Double.doubleToRawLongBits(7.80))),
            showCostOnOverlay = sp.getBoolean("showCostOnOverlay", true)
        )

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val uDate = sp.getString("usageDate", today) ?: today
        val isSameDay = uDate == today
        TranslatorState.usage.value = ApiUsageRecord(
            date = today,
            secondsTranslated = if (isSameDay) sp.getInt("usageSec", 0) else 0,
            requests = if (isSameDay) sp.getInt("usageReq", 0) else 0,
            todayPromptTokens = if (isSameDay) sp.getLong("todayPromptTokens", 0L) else 0L,
            todayOutputTokens = if (isSameDay) sp.getLong("todayOutputTokens", 0L) else 0L,
            todayCostUsd = if (isSameDay) Double.fromBits(sp.getLong("todayCostUsdBits", 0L)) else 0.0,
            sessionCostUsd = 0.0,
            sessionRequests = 0,
            totalSpentUsd = Double.fromBits(sp.getLong("totalSpentUsdBits", 0L)),
            totalPromptTokens = sp.getLong("totalPromptTokens", 0L),
            totalOutputTokens = sp.getLong("totalOutputTokens", 0L),
            lastRequestCostUsd = Double.fromBits(sp.getLong("lastRequestCostUsdBits", 0L)),
            lastTokenBreakdown = sp.getString("lastTokenBreakdown", "") ?: ""
        )

        val sumRaw = sp.getString("sessionSummaries", null)
        if (sumRaw != null) {
            runCatching {
                val obj = JSONObject(sumRaw)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.optString(k, "")
                }
                TranslatorState.sessionSummaries.value = map
            }
        }

        val profRaw = sp.getString("profiles", null)
        if (profRaw != null) {
            runCatching {
                val arr = JSONArray(profRaw)
                val list = mutableListOf<StreamerProfile>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val lang = runCatching { SourceLanguage.valueOf(o.optString("language", SourceLanguage.JAPANESE.name)) }.getOrDefault(SourceLanguage.JAPANESE)
                    list.add(StreamerProfile(o.optLong("id", i.toLong() + 1), o.optString("name"), o.optString("glossary"), lang))
                }
                TranslatorState.profiles.value = list
            }
        }

        runCatching {
            val arr = JSONArray(sp.getString("history", "[]") ?: "[]")
            val list = mutableListOf<TranscriptItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    TranscriptItem(
                        id = o.optLong("id", System.currentTimeMillis() + i),
                        date = o.optString("date", "較早紀錄"),
                        time = o.optString("time", ""),
                        original = o.optString("original", o.optString("japanese", "")),
                        translated = o.optString("translated", o.optString("chinese", "")),
                        langCode = o.optString("langCode", "JA"),
                        profileName = o.optString("profileName", ""),
                        starred = o.optBoolean("starred", false)
                    )
                )
            }
            TranslatorState.history.value = list
            list.firstOrNull()?.let {
                TranslatorState.latestItemId.value = it.id
                TranslatorState.latestStarred.value = it.starred
            }
        }

        runCatching {
            val arr = JSONArray(sp.getString("flashcards", "[]") ?: "[]")
            val list = mutableListOf<FlashcardItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    FlashcardItem(
                        id = o.optLong("id", System.currentTimeMillis() + i),
                        term = o.optString("term", ""),
                        reading = o.optString("reading", ""),
                        meaning = o.optString("meaning", ""),
                        example = o.optString("example", ""),
                        langCode = o.optString("langCode", "JA"),
                        sourceTag = o.optString("sourceTag", "收藏金句"),
                        masteredCount = o.optInt("masteredCount", 0)
                    )
                )
            }
            TranslatorState.flashcards.value = list
        }

        val phrasesRaw = sp.getString("quickPhrases", null)
        if (phrasesRaw != null) {
            runCatching {
                val arr = JSONArray(phrasesRaw)
                val list = mutableListOf<QuickPhrase>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(QuickPhrase(o.optLong("id", i.toLong()), o.optString("text"), o.optString("meaning"), o.optString("langCode", "JA")))
                }
                if (list.isNotEmpty()) TranslatorState.quickPhrases.value = list
            }
        }
    }

    fun saveSettings(context: Context, s: AppSettings) {
        TranslatorState.settings.value = s
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("apiKey", s.apiKey)
            .putString("baseUrl", s.baseUrl)
            .putString("model", s.model)
            .putString("sourceLanguage", s.sourceLanguage.name)
            .putString("style", s.style.name)
            .putLong("activeProfileId", s.activeProfileId)
            .putBoolean("smartVad", s.smartVad)
            .putInt("pauseMs", s.pauseMs)
            .putInt("overlapMs", s.overlapMs)
            .putInt("chunkSeconds", s.chunkSeconds)
            .putInt("silenceThreshold", s.silenceThreshold)
            .putBoolean("autoGain", s.autoGain)
            .putBoolean("showFurigana", s.showFurigana)
            .putBoolean("liveDubbing", s.liveDubbing)
            .putString("dubbingEngine", s.dubbingEngine.name)
            .putString("aiVoiceName", s.aiVoiceName)
            .putFloat("dubbingSpeed", s.dubbingSpeed)
            .putBoolean("dubbingDuckOriginal", s.dubbingDuckOriginal)
            .putBoolean("dubbingIndependentChannel", s.dubbingIndependentChannel)
            .putBoolean("lowVolumeBoost", s.lowVolumeBoost)
            .putString("captureMode", s.captureMode.name)
            .putBoolean("showOriginal", s.showOriginal)
            .putString("overlayOpacity", s.overlayOpacity.name)
            .putBoolean("overlayLocked", s.overlayLocked)
            .putString("glossary", s.glossary)
            .putFloat("overlayFontSize", s.overlayFontSize)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putString("themeMode", s.themeMode.name)
            .putLong("initialBalanceHkdBits", java.lang.Double.doubleToRawLongBits(s.initialBalanceHkd))
            .putLong("monthlyProCreditUsdBits", java.lang.Double.doubleToRawLongBits(s.monthlyProCreditUsd))
            .putLong("usdToHkdRateBits", java.lang.Double.doubleToRawLongBits(s.usdToHkdRate))
            .putBoolean("showCostOnOverlay", s.showCostOnOverlay)
            .apply()
    }

    fun saveUsage(context: Context, u: ApiUsageRecord) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("usageDate", u.date)
            .putInt("usageSec", u.secondsTranslated)
            .putInt("usageReq", u.requests)
            .putLong("todayPromptTokens", u.todayPromptTokens)
            .putLong("todayOutputTokens", u.todayOutputTokens)
            .putLong("todayCostUsdBits", java.lang.Double.doubleToRawLongBits(u.todayCostUsd))
            .putLong("totalSpentUsdBits", java.lang.Double.doubleToRawLongBits(u.totalSpentUsd))
            .putLong("totalPromptTokens", u.totalPromptTokens)
            .putLong("totalOutputTokens", u.totalOutputTokens)
            .putLong("lastRequestCostUsdBits", java.lang.Double.doubleToRawLongBits(u.lastRequestCostUsd))
            .putString("lastTokenBreakdown", u.lastTokenBreakdown)
            .apply()
    }

    fun saveSummaries(context: Context) {
        val obj = JSONObject()
        TranslatorState.sessionSummaries.value.forEach { (k, v) -> obj.put(k, v) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("sessionSummaries", obj.toString()).apply()
    }

    fun saveProfiles(context: Context) {
        val arr = JSONArray()
        TranslatorState.profiles.value.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("glossary", it.glossary); put("language", it.language.name)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("profiles", arr.toString()).apply()
    }

    fun saveFlashcards(context: Context) {
        val arr = JSONArray()
        TranslatorState.flashcards.value.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("term", c.term); put("reading", c.reading)
                put("meaning", c.meaning); put("example", c.example)
                put("langCode", c.langCode); put("sourceTag", c.sourceTag)
                put("masteredCount", c.masteredCount)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("flashcards", arr.toString()).apply()
    }

    fun savePhrases(context: Context) {
        val arr = JSONArray()
        TranslatorState.quickPhrases.value.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("text", it.text); put("meaning", it.meaning); put("langCode", it.langCode)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("quickPhrases", arr.toString()).apply()
    }

    fun saveHistory(context: Context) {
        val arr = JSONArray()
        TranslatorState.history.value.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("date", it.date); put("time", it.time)
                put("original", it.original); put("translated", it.translated)
                put("langCode", it.langCode); put("profileName", it.profileName); put("starred", it.starred)
            })
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("history", arr.toString()).apply()
    }

    fun exportSessionMarkdown(sessionKey: String, items: List<TranscriptItem>, summary: String): String {
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val chronological = items.reversed()
        val starred = chronological.filter { it.starred }
        val profilesUsed = chronological.mapNotNull { it.profileName.takeIf { p -> p.isNotBlank() } }.distinct().joinToString("、")

        return buildString {
            appendLine("# 直播精華筆記 — $sessionKey")
            appendLine()
            appendLine("- **匯出時間**：$exportedAt")
            appendLine("- **總翻譯句數**：${chronological.size} 句（⭐ 收藏：${starred.size} 句）")
            if (profilesUsed.isNotBlank()) appendLine("- **相關主播設定檔**：$profilesUsed")
            appendLine()

            if (summary.isNotBlank()) {
                appendLine("## ✨ AI 重點摘要與實用口語")
                appendLine()
                appendLine(summary.trim())
                appendLine()
            }

            if (starred.isNotEmpty()) {
                appendLine("## ⭐ 星號收藏金句")
                appendLine()
                starred.forEach { item ->
                    appendLine("- **[${item.time}]** ${item.translated}")
                    if (item.original.isNotBlank()) appendLine("  - 原文：${item.original}")
                }
                appendLine()
            }

            appendLine("## 📝 完整雙語字幕紀錄")
            appendLine()
            appendLine("| 時間 | 語言 | 原文 | 翻譯 |")
            appendLine("| :--- | :--- | :--- | :--- |")
            chronological.forEach { item ->
                val starMark = if (item.starred) "⭐ " else ""
                val orig = item.original.replace("|", "\\|").replace("\n", " ")
                val trans = item.translated.replace("|", "\\|").replace("\n", " ")
                appendLine("| ${item.time} | ${item.langCode} | $orig | $starMark$trans |")
            }
        }
    }

    fun exportJson(): String {
        val s = TranslatorState.settings.value
        val u = TranslatorState.usage.value
        val root = JSONObject()
        root.put("schemaVersion", 8)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))
        root.put("settings", JSONObject().apply {
            put("baseUrl", s.baseUrl); put("model", s.model)
            put("sourceLanguage", s.sourceLanguage.name); put("style", s.style.name)
            put("activeProfileId", s.activeProfileId)
            put("smartVad", s.smartVad); put("pauseMs", s.pauseMs); put("overlapMs", s.overlapMs)
            put("chunkSeconds", s.chunkSeconds); put("silenceThreshold", s.silenceThreshold)
            put("autoGain", s.autoGain); put("showFurigana", s.showFurigana)
            put("liveDubbing", s.liveDubbing); put("dubbingEngine", s.dubbingEngine.name)
            put("aiVoiceName", s.aiVoiceName); put("dubbingSpeed", s.dubbingSpeed)
            put("dubbingDuckOriginal", s.dubbingDuckOriginal)
            put("dubbingIndependentChannel", s.dubbingIndependentChannel)
            put("lowVolumeBoost", s.lowVolumeBoost)
            put("showOriginal", s.showOriginal)
            put("overlayOpacity", s.overlayOpacity.name); put("overlayLocked", s.overlayLocked)
            put("glossary", s.glossary); put("overlayFontSize", s.overlayFontSize)
            put("initialBalanceHkd", s.initialBalanceHkd)
            put("monthlyProCreditUsd", s.monthlyProCreditUsd)
            put("usdToHkdRate", s.usdToHkdRate)
            put("showCostOnOverlay", s.showCostOnOverlay)
        })
        root.put("billingLedger", JSONObject().apply {
            put("totalSpentUsd", u.totalSpentUsd)
            put("totalPromptTokens", u.totalPromptTokens)
            put("totalOutputTokens", u.totalOutputTokens)
        })
        val sumObj = JSONObject()
        TranslatorState.sessionSummaries.value.forEach { (k, v) -> sumObj.put(k, v) }
        root.put("sessionSummaries", sumObj)

        val cardArr = JSONArray()
        TranslatorState.flashcards.value.forEach { c ->
            cardArr.put(JSONObject().apply {
                put("id", c.id); put("term", c.term); put("reading", c.reading)
                put("meaning", c.meaning); put("example", c.example)
                put("langCode", c.langCode); put("sourceTag", c.sourceTag)
                put("masteredCount", c.masteredCount)
            })
        }
        root.put("flashcards", cardArr)

        val profArr = JSONArray()
        TranslatorState.profiles.value.forEach {
            profArr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("glossary", it.glossary); put("language", it.language.name)
            })
        }
        root.put("profiles", profArr)

        val pArr = JSONArray()
        TranslatorState.quickPhrases.value.forEach {
            pArr.put(JSONObject().apply {
                put("id", it.id); put("text", it.text); put("meaning", it.meaning); put("langCode", it.langCode)
            })
        }
        root.put("quickPhrases", pArr)

        val arr = JSONArray()
        TranslatorState.history.value.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("date", it.date); put("time", it.time)
                put("original", it.original); put("translated", it.translated)
                put("langCode", it.langCode); put("profileName", it.profileName); put("starred", it.starred)
            })
        }
        root.put("history", arr)
        return root.toString(2)
    }

    fun exportCsv(): String {
        val sb = StringBuilder("\uFEFF日期,時間,主播設定檔,已收藏,語言,原文,翻譯\n")
        TranslatorState.history.value.forEach {
            val orig = it.original.replace("\"", "\"\"")
            val trans = it.translated.replace("\"", "\"\"")
            val prof = it.profileName.replace("\"", "\"\"")
            val star = if (it.starred) "★" else ""
            sb.append("\"${it.date}\",\"${it.time}\",\"$prof\",\"$star\",\"${it.langCode}\",\"$orig\",\"$trans\"\n")
        }
        return sb.toString()
    }

    fun importJson(context: Context, raw: String, overwrite: Boolean): Result<Int> = runCatching {
        val root = JSONObject(raw)
        if (!root.has("history")) error("JSON 檔案格式不正確：缺少 history 欄位")
        root.optJSONObject("settings")?.let { so ->
            val cur = TranslatorState.settings.value
            saveSettings(context, cur.copy(
                baseUrl = so.optString("baseUrl", cur.baseUrl),
                model = so.optString("model", cur.model),
                sourceLanguage = runCatching { SourceLanguage.valueOf(so.optString("sourceLanguage")) }.getOrDefault(cur.sourceLanguage),
                style = runCatching { TranslationStyle.valueOf(so.optString("style")) }.getOrDefault(cur.style),
                activeProfileId = so.optLong("activeProfileId", cur.activeProfileId),
                smartVad = so.optBoolean("smartVad", cur.smartVad),
                pauseMs = so.optInt("pauseMs", cur.pauseMs),
                overlapMs = so.optInt("overlapMs", cur.overlapMs),
                chunkSeconds = so.optInt("chunkSeconds", cur.chunkSeconds),
                silenceThreshold = so.optInt("silenceThreshold", cur.silenceThreshold),
                autoGain = so.optBoolean("autoGain", cur.autoGain),
                showFurigana = so.optBoolean("showFurigana", cur.showFurigana),
                liveDubbing = so.optBoolean("liveDubbing", cur.liveDubbing),
                dubbingEngine = runCatching { DubbingEngineMode.valueOf(so.optString("dubbingEngine")) }.getOrDefault(cur.dubbingEngine),
                aiVoiceName = so.optString("aiVoiceName", cur.aiVoiceName),
                dubbingSpeed = so.optDouble("dubbingSpeed", cur.dubbingSpeed.toDouble()).toFloat(),
                dubbingDuckOriginal = so.optBoolean("dubbingDuckOriginal", cur.dubbingDuckOriginal),
                dubbingIndependentChannel = so.optBoolean("dubbingIndependentChannel", cur.dubbingIndependentChannel),
                lowVolumeBoost = so.optBoolean("lowVolumeBoost", cur.lowVolumeBoost),
                showOriginal = so.optBoolean("showOriginal", so.optBoolean("showJapanese", cur.showOriginal)),
                overlayOpacity = runCatching { OverlayOpacity.valueOf(so.optString("overlayOpacity")) }.getOrDefault(cur.overlayOpacity),
                overlayLocked = so.optBoolean("overlayLocked", cur.overlayLocked),
                glossary = so.optString("glossary", cur.glossary),
                overlayFontSize = so.optDouble("overlayFontSize", cur.overlayFontSize.toDouble()).toFloat(),
                initialBalanceHkd = so.optDouble("initialBalanceHkd", cur.initialBalanceHkd),
                monthlyProCreditUsd = so.optDouble("monthlyProCreditUsd", cur.monthlyProCreditUsd),
                usdToHkdRate = so.optDouble("usdToHkdRate", cur.usdToHkdRate),
                showCostOnOverlay = so.optBoolean("showCostOnOverlay", cur.showCostOnOverlay)
            ))
        }

        root.optJSONObject("billingLedger")?.let { bo ->
            val u = TranslatorState.usage.value.copy(
                totalSpentUsd = bo.optDouble("totalSpentUsd", TranslatorState.usage.value.totalSpentUsd),
                totalPromptTokens = bo.optLong("totalPromptTokens", TranslatorState.usage.value.totalPromptTokens),
                totalOutputTokens = bo.optLong("totalOutputTokens", TranslatorState.usage.value.totalOutputTokens)
            )
            TranslatorState.usage.value = u
            saveUsage(context, u)
        }

        root.optJSONObject("sessionSummaries")?.let { so ->
            val map = if (overwrite) mutableMapOf() else TranslatorState.sessionSummaries.value.toMutableMap()
            val keys = so.keys()
            while (keys.hasNext()) { val k = keys.next(); map[k] = so.optString(k, "") }
            TranslatorState.sessionSummaries.value = map
            saveSummaries(context)
        }

        root.optJSONArray("flashcards")?.let { ca ->
            val cList = mutableListOf<FlashcardItem>()
            for (i in 0 until ca.length()) {
                val o = ca.getJSONObject(i)
                cList.add(
                    FlashcardItem(
                        id = o.optLong("id", System.currentTimeMillis() + i),
                        term = o.optString("term", ""),
                        reading = o.optString("reading", ""),
                        meaning = o.optString("meaning", ""),
                        example = o.optString("example", ""),
                        langCode = o.optString("langCode", "JA"),
                        sourceTag = o.optString("sourceTag", "收藏金句"),
                        masteredCount = o.optInt("masteredCount", 0)
                    )
                )
            }
            if (cList.isNotEmpty()) {
                TranslatorState.flashcards.value = if (overwrite) cList else (cList + TranslatorState.flashcards.value).distinctBy { it.term }
                saveFlashcards(context)
            }
        }

        root.optJSONArray("profiles")?.let { pa ->
            val list = mutableListOf<StreamerProfile>()
            for (i in 0 until pa.length()) {
                val o = pa.getJSONObject(i)
                val lang = runCatching { SourceLanguage.valueOf(o.optString("language", SourceLanguage.JAPANESE.name)) }.getOrDefault(SourceLanguage.JAPANESE)
                list.add(StreamerProfile(o.optLong("id", System.currentTimeMillis() + i), o.optString("name"), o.optString("glossary"), lang))
            }
            if (list.isNotEmpty()) {
                TranslatorState.profiles.value = if (overwrite) list else (list + TranslatorState.profiles.value).distinctBy { it.name }
                saveProfiles(context)
            }
        }

        root.optJSONArray("quickPhrases")?.let { pa ->
            val pList = mutableListOf<QuickPhrase>()
            for (i in 0 until pa.length()) {
                val o = pa.getJSONObject(i)
                pList.add(QuickPhrase(o.optLong("id", System.currentTimeMillis() + i), o.optString("text"), o.optString("meaning"), o.optString("langCode", "JA")))
            }
            if (pList.isNotEmpty()) {
                TranslatorState.quickPhrases.value = if (overwrite) pList else (pList + TranslatorState.quickPhrases.value).distinctBy { it.text }
                savePhrases(context)
            }
        }

        val arr = root.getJSONArray("history")
        val imported = mutableListOf<TranscriptItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            imported.add(
                TranscriptItem(
                    id = o.optLong("id", System.currentTimeMillis() + i),
                    date = o.optString("date", "較早紀錄"),
                    time = o.optString("time", ""),
                    original = o.optString("original", o.optString("japanese", "")),
                    translated = o.optString("translated", o.optString("chinese", "")),
                    langCode = o.optString("langCode", "JA"),
                    profileName = o.optString("profileName", ""),
                    starred = o.optBoolean("starred", false)
                )
            )
        }
        TranslatorState.history.value = if (overwrite) imported else (imported + TranslatorState.history.value).distinctBy { it.id }
        saveHistory(context)
        imported.size
    }
}
