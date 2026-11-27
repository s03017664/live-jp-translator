package com.npl.livejptranslator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object TtsManager {
    private val dubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var activeFocusRequest: AudioFocusRequest? = null
    private var activeAudioTrack: AudioTrack? = null
    private val ready = AtomicBoolean(false)
    val isDubbingSpeaking = AtomicBoolean(false)

    private val furiganaRegex = Regex("[\(（][ぁ-んァ-ンー]+[\)）]")

    fun init(context: Context) {
        val appCtx = context.applicationContext
        if (audioManager == null) {
            audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        if (tts != null) return
        tts = TextToSpeech(appCtx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready.set(true)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId?.startsWith("dub_") == true) isDubbingSpeaking.set(true)
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith("dub_") == true) {
                            isDubbingSpeaking.set(false)
                            abandonDuckFocus()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId?.startsWith("dub_") == true) {
                            isDubbingSpeaking.set(false)
                            abandonDuckFocus()
                        }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId?.startsWith("dub_") == true) {
                            isDubbingSpeaking.set(false)
                            abandonDuckFocus()
                        }
                    }
                })
            }
        }
    }

    fun stripFurigana(text: String): String = furiganaRegex.replace(text, "").trim()

    fun speakOriginal(context: Context, rawText: String, langCode: String): Boolean {
        init(context)
        val engine = tts ?: return false
        if (!ready.get()) return false
        val clean = stripFurigana(rawText)
        if (clean.isBlank()) return false

        val locale = when (langCode.uppercase(Locale.US)) {
            "JA" -> Locale.JAPANESE
            "EN" -> Locale.US
            "KO" -> Locale.KOREAN
            "TH" -> Locale.forLanguageTag("th-TH")
            "FR" -> Locale.FRENCH
            "ES" -> Locale.forLanguageTag("es-ES")
            "ZH" -> Locale.TAIWAN
            else -> Locale.JAPANESE
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(0.95f)
        setBestLocale(engine, listOf(locale, Locale.JAPANESE))
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), "orig_${System.currentTimeMillis()}")
        return true
    }

    fun speakDubbing(context: Context, text: String, settings: AppSettings) {
        val appCtx = context.applicationContext
        init(appCtx)
        if (text.isBlank()) return

        if (settings.dubbingEngine == DubbingEngineMode.AI_NATURAL && settings.apiKey.isNotBlank()) {
            dubScope.launch {
                val pcm = GeminiApi.synthesizeNaturalVoicePcm(appCtx, text, settings)
                if (pcm != null && pcm.isNotEmpty()) {
                    playPcmDubbing(pcm, settings)
                } else {
                    withContext(Dispatchers.Main) { speakLocalDubbing(text, settings) }
                }
            }
            return
        }

        speakLocalDubbing(text, settings)
    }

    private fun speakLocalDubbing(text: String, settings: AppSettings) {
        val engine = tts ?: return
        if (!ready.get()) return

        val usage = if (settings.dubbingIndependentChannel) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        engine.setAudioAttributes(attrs)
        engine.setSpeechRate(settings.dubbingSpeed.coerceIn(0.9f, 1.8f))

        val candidates = when (settings.style) {
            TranslationStyle.CANTONESE -> listOf(
                Locale.forLanguageTag("yue-HK"),
                Locale.forLanguageTag("zh-HK"),
                Locale.TAIWAN,
                Locale.TRADITIONAL_CHINESE
            )
            TranslationStyle.TRADITIONAL_CHINESE -> listOf(
                Locale.TAIWAN,
                Locale.forLanguageTag("zh-HK"),
                Locale.TRADITIONAL_CHINESE
            )
            TranslationStyle.ENGLISH -> listOf(Locale.US, Locale.UK)
        }
        setBestLocale(engine, candidates)

        if (settings.dubbingDuckOriginal) requestDuckFocus(attrs)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "dub_${System.currentTimeMillis()}")
    }

    private fun playPcmDubbing(pcm: ByteArray, settings: AppSettings) {
        val sampleRate = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(pcm.size)

        val usage = if (settings.dubbingIndependentChannel) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (settings.dubbingDuckOriginal) requestDuckFocus(attrs)

        stopActiveAudioTrack()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        activeAudioTrack = track
        isDubbingSpeaking.set(true)
        try {
            track.playbackRate = (sampleRate * settings.dubbingSpeed.coerceIn(0.9f, 1.6f)).toInt()
            track.play()
            track.write(pcm, 0, pcm.size)
            track.stop()
        } finally {
            isDubbingSpeaking.set(false)
            abandonDuckFocus()
            runCatching { track.release() }
            if (activeAudioTrack === track) activeAudioTrack = null
        }
    }

    private fun stopActiveAudioTrack() {
        activeAudioTrack?.let { t ->
            runCatching { t.stop(); t.release() }
            if (activeAudioTrack === t) activeAudioTrack = null
        }
    }

    private fun setBestLocale(engine: TextToSpeech, locales: List<Locale>) {
        for (loc in locales) {
            if (engine.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = loc
                return
            }
        }
        engine.language = locales.first()
    }

    private fun requestDuckFocus(attrs: AudioAttributes) {
        val am = audioManager ?: return
        abandonDuckFocus()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        activeFocusRequest = req
        am.requestAudioFocus(req)
    }

    private fun abandonDuckFocus() {
        val am = audioManager ?: return
        activeFocusRequest?.let {
            runCatching { am.abandonAudioFocusRequest(it) }
            activeFocusRequest = null
        }
    }

    fun stop() {
        isDubbingSpeaking.set(false)
        stopActiveAudioTrack()
        abandonDuckFocus()
        runCatching { tts?.stop() }
    }
}
