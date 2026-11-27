package com.npl.livejptranslator

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiApi {
    data class GeminiRawResult(
        val text: String,
        val promptTokens: Int,
        val outputTokens: Int,
        val audioInputTokens: Int
    )

    fun translateAudio(
        context: Context,
        wavBytes: ByteArray,
        durationSec: Int,
        s: AppSettings,
        activeProfile: StreamerProfile?,
        recent: List<TranscriptItem>
    ): Result<Pair<String, String>> = runCatching {
        if (s.apiKey.isBlank()) error("請先填寫 Gemini API Key")
        val recentContext = recent.take(5).reversed()
            .joinToString("\n") { "原:${it.original} / 譯:${it.translated}" }

        val mergedGlossary = listOfNotNull(
            activeProfile?.let { "目前觀看主播／頻道：${it.name}；專屬人名與口頭禪詞庫：${it.glossary}" },
            s.glossary.takeIf { it.isNotBlank() }?.let { "全域補充詞庫：$it" }
        ).joinToString("\n")

        val prompt = buildString {
            appendLine("你是專業直播即時口譯員。請聆聽這段音訊，精準聽寫並即時翻譯。")
            appendLine("來源語言：${s.sourceLanguage.promptHint}")
            appendLine("輸出翻譯要求：${s.style.instruction}")
            if (s.showFurigana && (s.sourceLanguage == SourceLanguage.JAPANESE || s.sourceLanguage == SourceLanguage.AUTO)) {
                appendLine("振り仮名標註要求：當原文為日文時，請在 orig 欄位為漢字加上括號平假名讀音，例如：初見(しょけん)さん歓迎(かんげい)！今日(きょう)も楽(たの)しいね。")
            }
            if (s.overlapMs > 0) {
                appendLine("防漏字提示：音訊最開頭可能包含約 ${s.overlapMs}ms 與上一句重疊的緩衝聲音，若開頭字詞已在前文出現，請自然銜接勿重複翻譯。")
            }
            if (mergedGlossary.isNotBlank()) {
                appendLine("人名與專屬詞彙參考（請優先採用這些正確寫法與稱呼）：")
                appendLine(mergedGlossary)
            }
            if (recentContext.isNotBlank()) {
                appendLine("前文語境參考（幫助理解上下文，不要重複輸出前文）：")
                appendLine(recentContext)
            }
            appendLine("請只輸出 JSON，不要加 Markdown：{\"orig\":\"聽寫原文\",\"trans\":\"翻譯結果\"}")
            appendLine("若音訊沒有清晰人聲或只有背景音樂/雜音，請輸出 {\"orig\":\"\",\"trans\":\"SILENT\"}")
        }

        val genCfg = JSONObject().apply {
            put("temperature", 0.15)
            put("responseMimeType", "application/json")
            if (s.model.startsWith("gemini-2.5")) {
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/wav")
                        put("data", Base64.encodeToString(wavBytes, Base64.NO_WRAP))
                    }))
                )
            }))
            put("generationConfig", genCfg)
        }

        val res = postGeminiWithRetry(s, s.model, body, estimatedAudioSec = durationSec)
        TranslatorState.recordApiTokens(
            context = context,
            model = s.model,
            promptTokens = res.promptTokens,
            outputTokens = res.outputTokens,
            audioInputTokens = res.audioInputTokens,
            secondsTranslated = durationSec
        )
        val trimmedRes = res.text.trim()
        val parsed = if (trimmedRes.startsWith("[")) {
            JSONArray(trimmedRes).optJSONObject(0) ?: JSONObject()
        } else {
            JSONObject(trimmedRes)
        }
        val orig = parsed.optString("orig", parsed.optString("original", parsed.optString("jp", "")))
        val trans = parsed.optString("trans", parsed.optString("translation", parsed.optString("zh", "")))
        orig to trans
    }

    fun explainPhrase(context: Context, item: TranscriptItem, s: AppSettings): Result<String> = runCatching {
        if (s.apiKey.isBlank()) error("請先填寫 Gemini API Key")
        val prompt = """
            你是一位親切的日語與直播潮語老師。請用簡潔易懂的繁體中文（香港口語風格）拆解以下直播句子：
            - 原文：${item.original}
            - 翻譯：${item.translated}
            
            請分 3 點簡短說明：
            1. 💬 句意與語氣（主播當下係乜嘢語氣、同觀眾傾偈點解咁講）
            2. 🔍 關鍵詞／潮語／縮寫拆解（挑出 1–3 個最值得學嘅詞彙、文法或網語）
            3. 🔄 實用替換說法（1 句自然口語例子）
        """.trimIndent()

        val genCfg = JSONObject().apply {
            put("temperature", 0.3)
            if (s.model.startsWith("gemini-2.5")) {
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", genCfg)
        }
        val res = postGeminiWithRetry(s, s.model, body)
        TranslatorState.recordApiTokens(context, s.model, res.promptTokens, res.outputTokens)
        res.text
    }

    fun synthesizeNaturalVoicePcm(context: Context, text: String, s: AppSettings): ByteArray? {
        if (s.apiKey.isBlank() || text.isBlank()) return null
        return runCatching {
            val spokenPrompt = when (s.style) {
                TranslationStyle.CANTONESE -> "請用自然、親切、有活力的香港廣東話口語朗讀以下句子，不要加任何前言或解釋：$text"
                TranslationStyle.TRADITIONAL_CHINESE -> "請用自然流暢的繁體中文朗讀以下句子，不要加任何前言或解釋：$text"
                TranslationStyle.ENGLISH -> "Read the following line naturally in a friendly livestream voice without extra words: $text"
            }

            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", spokenPrompt)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", s.aiVoiceName)
                        }))
                    })
                })
            }

            val cleanBase = s.baseUrl.trimEnd('/')
            val url = URL("$cleanBase/v1beta/models/gemini-flash-latest:generateContent?key=${s.apiKey.trim()}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 8000
                readTimeout = 14000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null

            val respText = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(respText)
            val usageObj = root.optJSONObject("usageMetadata")
            val pTok = usageObj?.optInt("promptTokenCount", text.length + 30) ?: (text.length + 30)
            val oTok = usageObj?.optInt("candidatesTokenCount", 120) ?: 120
            TranslatorState.recordApiTokens(context, "gemini-2.5-flash-native-audio", pTok, oTok, isAudioOutput = true)

            val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return null

            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                    ?: parts.optJSONObject(i)?.optJSONObject("inline_data")
                val b64 = inline?.optString("data", "") ?: ""
                if (b64.isNotBlank()) {
                    return Base64.decode(b64, Base64.DEFAULT)
                }
            }
            null
        }.getOrNull()
    }

    fun translateVoiceReply(
        context: Context,
        wavBytes: ByteArray,
        durationSec: Int,
        s: AppSettings,
        activeProfile: StreamerProfile?
    ): Result<Pair<String, String>> = runCatching {
        if (s.apiKey.isBlank()) error("請先填寫 Gemini API Key")
        val targetLang = if (s.sourceLanguage == SourceLanguage.AUTO) "日語（日本直播間自然口語）" else s.sourceLanguage.label
        val profileHint = activeProfile?.let { "主播：${it.name}；詞庫：${it.glossary}" } ?: ""
        val prompt = buildString {
            appendLine("用戶正在觀看直播，並用香港廣東話或中文錄製了一段想發送給主播的留言。")
            appendLine("請準確聽懂這段語音，並將其翻譯成自然、親切、地道、適合在直播間留言的「$targetLang」短句。")
            if (profileHint.isNotBlank()) appendLine("參考語境：$profileHint")
            appendLine("""請只輸出 JSON，不要加 Markdown：{"heard":"聽到的廣東話或中文","reply":"可直接貼去直播間的目標語留言"}""")
            appendLine("""若聽不到清晰人聲，輸出 {"heard":"","reply":""}""")
        }

        val genCfg = JSONObject().apply {
            put("temperature", 0.35)
            put("responseMimeType", "application/json")
            if (s.model.startsWith("gemini-2.5")) {
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/wav")
                        put("data", Base64.encodeToString(wavBytes, Base64.NO_WRAP))
                    }))
                )
            }))
            put("generationConfig", genCfg)
        }

        val res = postGeminiWithRetry(s, s.model, body, estimatedAudioSec = durationSec)
        TranslatorState.recordApiTokens(context, s.model, res.promptTokens, res.outputTokens, res.audioInputTokens)
        val parsed = JSONObject(res.text)
        parsed.optString("heard", "") to parsed.optString("reply", "")
    }

    fun translateReply(context: Context, input: String, s: AppSettings, activeProfile: StreamerProfile?): Result<String> = runCatching {
        if (s.apiKey.isBlank()) error("請先填寫 Gemini API Key")
        val targetLang = if (s.sourceLanguage == SourceLanguage.AUTO) "日語（自然直播口語）" else s.sourceLanguage.label
        val profileHint = activeProfile?.let { "（主播設定：${it.name}，詞庫：${it.glossary}）" } ?: ""
        val prompt = """
            請將以下留言翻譯成自然、親切、適合在直播間直接發送的「$targetLang」口語短句。$profileHint
            不要太生硬，不要加解釋或引號，只輸出翻譯後的句子本身。
            留言：$input
        """.trimIndent()

        val genCfg = JSONObject().apply {
            put("temperature", 0.4)
            if (s.model.startsWith("gemini-2.5")) {
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", genCfg)
        }
        val res = postGeminiWithRetry(s, s.model, body)
        TranslatorState.recordApiTokens(context, s.model, res.promptTokens, res.outputTokens)
        res.text
    }

    fun summarizeSession(context: Context, items: List<TranscriptItem>, s: AppSettings, sessionTitle: String): Result<String> = runCatching {
        if (s.apiKey.isBlank()) error("請先填寫 Gemini API Key")
        if (items.isEmpty()) error("該場次未有任何字幕紀錄可供總結")

        val transcriptText = items.take(160).reversed().joinToString("\n") {
            val mark = if (it.starred) "[⭐收藏] " else ""
            val prof = if (it.profileName.isNotBlank()) "(${it.profileName}) " else ""
            "$mark$prof[${it.time}] ${it.original} -> ${it.translated}"
        }

        val prompt = """
            你是一位貼心的直播筆記與語言學習助手。以下是《$sessionTitle》的即時翻譯字幕紀錄（含星號收藏片段）：
            
            $transcriptText
            
            請用清晰易讀的繁體中文整理一份精華筆記，包含以下 3 個部分：
            1. 📌 本場重點話題摘要（以 3–6 個簡潔要點概括主播聊過的核心話題，優先突出標記 ⭐ 的內容）
            2. 🗣️ 學到的實用口語／生字清單（挑選 5–8 個最實用的地道口語、潮語或常用句，格式：原文（若為日文請附平假名讀音）｜中文意思｜簡短用法說明）
            3. ✨ 直播亮點與氣氛總結（1–2 句輕鬆點評）
        """.trimIndent()

        val genCfg = JSONObject().apply {
            put("temperature", 0.35)
            put("maxOutputTokens", 1400)
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", genCfg)
        }
        val res = postGeminiWithRetry(s, s.model, body)
        TranslatorState.recordApiTokens(context, s.model, res.promptTokens, res.outputTokens)
        res.text
    }

    private fun postGeminiWithRetry(s: AppSettings, modelName: String, body: JSONObject, estimatedAudioSec: Int = 0): GeminiRawResult {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                return postGemini(s, modelName, body, estimatedAudioSec)
            } catch (e: Throwable) {
                lastError = e
                if (attempt == 0 && (e.message?.contains("API 4") != true)) {
                    Thread.sleep(300)
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("未知網路錯誤")
    }

    fun testApiConnection(s: AppSettings): String {
        val cleanKey = s.apiKey.trim()
        if (cleanKey.isBlank()) return "❌ 請先填寫 Gemini API Key"
        val cleanBase = s.baseUrl.trim().trimEnd('/')
        val cleanModel = s.model.trim()
        val endpoint = "$cleanBase/v1beta/models/$cleanModel:generateContent"
        return runCatching {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", "Reply OK")))
                }))
            }
            val conn = (URL("$endpoint?key=$cleanKey").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 9000
                readTimeout = 15000
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 200..299) {
                TranslatorState.lastApiError.value = ""
                val reply = runCatching {
                    JSONObject(respText).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text").trim()
                }.getOrDefault("OK")
                "✅ 連線成功！\n模型：$cleanModel\n網址：$endpoint\n回應：$reply"
            } else {
                val errObj = runCatching { JSONObject(respText).optJSONObject("error") }.getOrNull()
                val errMsg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
                val errStatus = errObj?.optString("status")?.takeIf { it.isNotBlank() }
                val sb = StringBuilder()
                sb.append("❌ HTTP $code")
                if (errStatus != null) sb.append(" ($errStatus)")
                sb.append("\n模型：$cleanModel")
                sb.append("\n網址：$endpoint")
                sb.append("\n完整原因：${errMsg ?: respText.replace("\r", "").trim().take(350).ifBlank { "(空回應)" }}")
                if (code == 404) {
                    val modelsList = fetchAvailableModels(cleanBase, cleanKey)
                    if (modelsList.isNotBlank()) {
                        sb.append("\n\n📋 此 API Key 可用模型：\n$modelsList")
                    }
                }
                val report = sb.toString()
                TranslatorState.lastApiError.value = report
                report
            }
        }.getOrElse { e ->
            val msg = "❌ 網路或程式例外：${e.javaClass.simpleName}: ${e.message}\n網址：$endpoint"
            TranslatorState.lastApiError.value = msg
            msg
        }
    }

    private fun fetchAvailableModels(cleanBase: String, cleanKey: String): String = runCatching {
        val conn = (URL("$cleanBase/v1beta/models?key=$cleanKey").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 10000
        }
        if (conn.responseCode !in 200..299) return@runCatching "(查詢模型清單失敗: HTTP ${conn.responseCode})"
        val text = conn.inputStream.bufferedReader().readText()
        val arr = JSONObject(text).optJSONArray("models") ?: return@runCatching ""
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (methods.contains("generateContent")) {
                val rawName = m.optString("name", "").removePrefix("models/")
                if (rawName.contains("gemini")) names.add(rawName)
            }
        }
        names.take(10).joinToString("\n") { "• $it" }
    }.getOrElse { "(無法查詢模型清單: ${it.message})" }

    private fun postGemini(s: AppSettings, modelName: String, body: JSONObject, estimatedAudioSec: Int): GeminiRawResult {
        val cleanBase = s.baseUrl.trim().trimEnd('/')
        val cleanModel = modelName.trim()
        val endpoint = "$cleanBase/v1beta/models/$cleanModel:generateContent"
        val url = URL("$endpoint?key=${s.apiKey.trim()}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Connection", "keep-alive")
            connectTimeout = 9000
            readTimeout = 18000
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val errObj = runCatching { JSONObject(respText).optJSONObject("error") }.getOrNull()
            val errMsg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
            val errStatus = errObj?.optString("status")?.takeIf { it.isNotBlank() }
            val detail = buildString {
                append("HTTP $code")
                if (errStatus != null) append(" ($errStatus)")
                append("\n模型：$cleanModel")
                append("\n網址：$endpoint")
                append("\n原因：")
                append(errMsg ?: respText.replace("\r", "").trim().take(300).ifBlank { "(空回應)" })
            }
            TranslatorState.lastApiError.value = detail
            error(detail)
        }
        TranslatorState.lastApiError.value = ""

        val root = JSONObject(respText)
        val usageMeta = root.optJSONObject("usageMetadata")
        val promptTokens = usageMeta?.optInt("promptTokenCount", 0) ?: 0
        val outputTokens = usageMeta?.optInt("candidatesTokenCount", 0) ?: 0

        // Parse promptTokensDetails if provided by Gemini API to separate AUDIO vs TEXT tokens
        var audioTokens = 0
        val details = usageMeta?.optJSONArray("promptTokensDetails")
        if (details != null) {
            for (i in 0 until details.length()) {
                val d = details.optJSONObject(i)
                if (d?.optString("modality") == "AUDIO") {
                    audioTokens += d.optInt("tokenCount", 0)
                }
            }
        }
        if (audioTokens == 0 && estimatedAudioSec > 0) {
            audioTokens = (estimatedAudioSec * 32).coerceAtMost(promptTokens)
        }

        val text = root
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text").trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        return GeminiRawResult(
            text = text,
            promptTokens = if (promptTokens > 0) promptTokens else (estimatedAudioSec * 32 + 180),
            outputTokens = if (outputTokens > 0) outputTokens else (text.length + 10),
            audioInputTokens = audioTokens
        )
    }
}
