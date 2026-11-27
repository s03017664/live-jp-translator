package com.npl.livejptranslator

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class TranslationService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        private const val CHANNEL_ID = "live_jp_channel"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apiMutex = Mutex()
    private val isVoiceReplying = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var voiceReplyJob: Job? = null
    private var voiceReplyPcm = ByteArrayOutputStream()
    private var voiceReplyStartMs = 0L
    private var mediaButtonReceiver: BroadcastReceiver? = null

    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayRoot: FrameLayout? = null
    private var expandedBox: LinearLayout? = null
    private var miniDotView: TextView? = null
    private var overlayBg: GradientDrawable? = null

    private var tvOrig: TextView? = null
    private var tvTrans: TextView? = null
    private var tvStatus: TextView? = null
    private var tvCostBadge: TextView? = null
    private var btnStar: TextView? = null
    private var btnVoiceReply: TextView? = null
    private var btnProfileQuick: TextView? = null
    private var btnDubbing: TextView? = null
    private var btnFurigana: TextView? = null
    private var btnPause: TextView? = null
    private var btnOpacity: TextView? = null
    private var btnBilingual: TextView? = null
    private var btnLock: TextView? = null
    private var phraseScroll: HorizontalScrollView? = null
    private var phraseContainer: LinearLayout? = null
    private var profileScroll: HorizontalScrollView? = null
    private var profileContainer: LinearLayout? = null
    private var replyRow: LinearLayout? = null
    private var etReply: EditText? = null
    private var btnSendReply: TextView? = null

    private var isMinimized = false
    private var expandedWidthPx = 900
    private var savedExpandedY = 150
    private var savedDotX = 24
    private var savedDotY = 300

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TtsManager.init(this)
        registerEarphoneReceiver()
    }

    private fun registerEarphoneReceiver() {
        if (mediaButtonReceiver != null) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
                    val ke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (ke != null && ke.action == KeyEvent.ACTION_DOWN) {
                        when (ke.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                togglePauseFromExternal()
                                abortBroadcast()
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                if (!TranslatorState.isPaused.value) togglePauseFromExternal()
                                abortBroadcast()
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                if (TranslatorState.isPaused.value) togglePauseFromExternal()
                                abortBroadcast()
                            }
                        }
                    }
                }
            }
        }
        mediaButtonReceiver = rx
        val filter = IntentFilter(Intent.ACTION_MEDIA_BUTTON).apply { priority = 999 }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rx, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(rx, filter)
        }
    }

    private fun togglePauseFromExternal() {
        val next = !TranslatorState.isPaused.value
        TranslatorState.isPaused.value = next
        if (next) TtsManager.stop()
        scope.launch(Dispatchers.Main) {
            btnPause?.text = if (next) "▶" else "⏸"
            miniDotView?.text = if (next) "⏸" else "譯"
            updateOverlayUi(if (next) "🎧 耳機鍵已暫停" else "🎧 耳機鍵繼續聆聽", null, null)
            Toast.makeText(this@TranslationService, if (next) "已暫停即時翻譯" else "已恢復即時翻譯", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        val settings = TranslatorState.settings.value
        TranslatorState.resetSessionCounter(this)

        val ch = NotificationChannel(CHANNEL_ID, "即時翻譯服務", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)

        val profileTag = TranslatorState.activeProfile()?.name?.let { " · $it" } ?: ""
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("直播即時譯 v1.8.2 運作中")
            .setContentText("即時翻譯：${settings.sourceLanguage.label}$profileTag")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        val fgsType = if (settings.captureMode == CaptureMode.INTERNAL) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { startForeground(1001, notification, fgsType) }
                .onFailure {
                    val fallbackType = if (settings.captureMode == CaptureMode.INTERNAL) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    startForeground(1001, notification, fallbackType)
                }
        } else {
            startForeground(1001, notification)
        }

        if (settings.captureMode == CaptureMode.INTERNAL) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (code == Activity.RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(code, data)
            } else {
                TranslatorState.statusText.value = "未取得內錄權限"
                stopEverything()
                return START_NOT_STICKY
            }
        }

        TranslatorState.isPaused.value = false
        showOverlay()
        startCaptureLoop()
        TranslatorState.isRunning.value = true
        return START_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun profileShortLabel(): String {
        val name = TranslatorState.activeProfile()?.name ?: return "👤通用"
        return "👤" + name.take(4)
    }

    private fun formatCostBadge(): String {
        val s = TranslatorState.settings.value
        val u = TranslatorState.usage.value
        val sessHkd = u.sessionCostHkd(s.usdToHkdRate)
        val remHkd = u.remainingCreditHkd(s)
        return String.format(Locale.US, "本場$%.2f · 餘$%.2f", sessHkd, remHkd)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayRoot != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val s = TranslatorState.settings.value
        expandedWidthPx = (resources.displayMetrics.widthPixels * 0.94f).toInt().coerceAtMost(1440)
        savedDotX = resources.displayMetrics.widthPixels - dp(68)
        savedDotY = dp(180)

        overlayBg = GradientDrawable().apply {
            setColor(Color.parseColor(s.overlayOpacity.bgHex))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.parseColor(if (s.overlayLocked) "#AAF59E0B" else "#55818CF8"))
        }

        val frame = FrameLayout(this)

        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(8), dp(11), dp(10))
            background = overlayBg
        }

        fun makeAction(label: String, color: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(color))
            textSize = 11.2f
            setPadding(dp(5), dp(3), dp(5), dp(3))
            setOnClickListener { onClick() }
        }

        // 第 1 列：狀態 + 實時本場花費／餘額徽章 + ⭐ + 🎤按講 + 💬快貼 + ⌨譯 + — + ✕
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvStatus = TextView(this).apply {
            text = "● ${s.sourceLanguage.shortCode}"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10.5f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvCostBadge = TextView(this).apply {
            text = formatCostBadge()
            setTextColor(Color.parseColor("#FDE68A"))
            textSize = 10f
            setPadding(dp(5), dp(2), dp(5), dp(2))
            visibility = if (s.showCostOnOverlay) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#292524"))
                cornerRadius = dp(8).toFloat()
            }
        }

        btnStar = makeAction(if (TranslatorState.latestStarred.value) "★" else "☆", if (TranslatorState.latestStarred.value) "#FBBF24" else "#CBD5E1") {
            val res = TranslatorState.toggleLatestStar(this)
            if (res == null) {
                Toast.makeText(this, "暫未有字幕可收藏", Toast.LENGTH_SHORT).show()
            } else {
                btnStar?.text = if (res) "★" else "☆"
                btnStar?.setTextColor(Color.parseColor(if (res) "#FBBF24" else "#CBD5E1"))
                val msg = if (res) "⭐ 已收藏並加入生字卡" else "已取消收藏"
                updateOverlayUi(msg, null, null)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        btnVoiceReply = TextView(this).apply {
            text = "🎤按講"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 11.2f
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A1F3D"))
                cornerRadius = dp(10).toFloat()
            }
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { startHoldVoiceReply(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopHoldVoiceReplyAndTranslate(); true }
                    else -> false
                }
            }
        }

        val btnPhrases = makeAction("💬快貼", "#6EE7B7") {
            closeSubTrays(keepPhrases = true)
            val open = phraseScroll?.visibility != View.VISIBLE
            if (open) populateQuickPhrasesTray()
            phraseScroll?.visibility = if (open) View.VISIBLE else View.GONE
        }

        val btnTypeReply = makeAction("⌨譯", "#F472B6") {
            closeSubTrays(keepReply = true)
            setReplyInputVisible(replyRow?.visibility != View.VISIBLE)
        }

        val btnMini = makeAction("—", "#94A3B8") { setMinimizedMode(true) }
        val btnClose = makeAction("✕", "#FB7185") { stopEverything() }

        row1.addView(tvStatus)
        row1.addView(tvCostBadge)
        row1.addView(btnStar)
        row1.addView(btnVoiceReply)
        row1.addView(btnPhrases)
        row1.addView(btnTypeReply)
        row1.addView(btnMini)
        row1.addView(btnClose)

        val row2Inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }

        btnProfileQuick = makeAction(profileShortLabel(), "#C4B5FD") {
            closeSubTrays(keepProfiles = true)
            val open = profileScroll?.visibility != View.VISIBLE
            if (open) populateProfileTray()
            profileScroll?.visibility = if (open) View.VISIBLE else View.GONE
        }

        btnDubbing = makeAction(if (s.liveDubbing) "🔊粵配開" else "🔇粵配關", if (s.liveDubbing) "#34D399" else "#94A3B8") {
            val cur = TranslatorState.settings.value
            val next = !cur.liveDubbing
            StorageHelper.saveSettings(this, cur.copy(liveDubbing = next))
            btnDubbing?.text = if (next) "🔊粵配開" else "🔇粵配關"
            btnDubbing?.setTextColor(Color.parseColor(if (next) "#34D399" else "#94A3B8"))
            if (!next) TtsManager.stop()
            val tip = if (next) "已開啟即時廣東話語音配音" else "已關閉語音配音"
            updateOverlayUi("● $tip", null, null)
            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
        }

        btnFurigana = makeAction(if (s.showFurigana) "仮名開" else "仮名關", if (s.showFurigana) "#7DD3FC" else "#94A3B8") {
            val cur = TranslatorState.settings.value
            val next = !cur.showFurigana
            StorageHelper.saveSettings(this, cur.copy(showFurigana = next))
            btnFurigana?.text = if (next) "仮名開" else "仮名關"
            btnFurigana?.setTextColor(Color.parseColor(if (next) "#7DD3FC" else "#94A3B8"))
        }

        btnBilingual = makeAction(if (s.showOriginal) "雙語" else "單譯", "#A5B4FC") {
            val cur = TranslatorState.settings.value
            val next = !cur.showOriginal
            StorageHelper.saveSettings(this, cur.copy(showOriginal = next))
            btnBilingual?.text = if (next) "雙語" else "單譯"
            tvOrig?.visibility = if (next) View.VISIBLE else View.GONE
        }

        btnOpacity = makeAction(s.overlayOpacity.label, "#CBD5E1") {
            val cur = TranslatorState.settings.value
            val next = cur.overlayOpacity.next()
            StorageHelper.saveSettings(this, cur.copy(overlayOpacity = next))
            btnOpacity?.text = next.label
            overlayBg?.setColor(Color.parseColor(next.bgHex))
        }

        val btnMinus = makeAction("A-", "#E2E8F0") { adjustFont(-2f) }
        val btnPlus = makeAction("A+", "#E2E8F0") { adjustFont(2f) }
        btnLock = makeAction(if (s.overlayLocked) "🔒" else "🔓", if (s.overlayLocked) "#FBBF24" else "#94A3B8") {
            toggleOverlayLock()
        }
        btnPause = makeAction("⏸", "#FDE047") { togglePauseFromExternal() }

        row2Inner.addView(btnProfileQuick); row2Inner.addView(btnDubbing); row2Inner.addView(btnFurigana)
        row2Inner.addView(btnBilingual); row2Inner.addView(btnOpacity); row2Inner.addView(btnMinus)
        row2Inner.addView(btnPlus); row2Inner.addView(btnLock); row2Inner.addView(btnPause)

        val row2Scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row2Inner)
        }

        tvOrig = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = (s.overlayFontSize - 4f).coerceAtLeast(11.5f)
            visibility = if (s.showOriginal) View.VISIBLE else View.GONE
            setPadding(0, dp(3), 0, 0)
        }
        tvTrans = TextView(this).apply {
            text = if (s.overlayLocked) "等待主播說話…（雙擊解鎖）" else "等待主播說話…（實時 API 費用與餘額已就緒）"
            setTextColor(Color.WHITE)
            textSize = s.overlayFontSize
            setPadding(0, dp(4), 0, 0)
        }

        phraseContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        phraseScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(phraseContainer)
        }

        profileContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        profileScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(profileContainer)
        }

        etReply = EditText(this).apply {
            hint = "打中文即時翻成日文並複製…"
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#6366F1"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSendReply = TextView(this).apply {
            text = "翻並複製"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4F46E5"))
                cornerRadius = dp(10).toFloat()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(6), 0, 0, 0)
            layoutParams = lp
            setOnClickListener { performOverlayReplyTranslate() }
        }
        val btnCancelReply = makeAction("✕", "#94A3B8") { setReplyInputVisible(false) }

        replyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(2))
            visibility = View.GONE
            addView(etReply); addView(btnSendReply); addView(btnCancelReply)
        }

        expanded.addView(row1); expanded.addView(row2Scroll); expanded.addView(tvOrig)
        expanded.addView(tvTrans); expanded.addView(phraseScroll); expanded.addView(profileScroll); expanded.addView(replyRow)

        val dotSize = dp(48)
        miniDotView = TextView(this).apply {
            text = "譯"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61E1B4B"))
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize)
            visibility = View.GONE
        }

        frame.addView(expanded, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        frame.addView(miniDotView)

        val params = WindowManager.LayoutParams(
            expandedWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = savedExpandedY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleOverlayLock()
                return true
            }
        })

        var initY = 0; var touchY = 0f
        expanded.setOnTouchListener { _, e ->
            gestureDetector.onTouchEvent(e)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { initY = params.y; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    if (!TranslatorState.settings.value.overlayLocked) {
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dy) > 4) {
                            params.y = initY + dy
                            savedExpandedY = params.y
                            windowManager?.updateViewLayout(frame, params)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        var dotStartX = 0; var dotStartY = 0
        var dotRawX = 0f; var dotRawY = 0f
        var dotMoved = false
        miniDotView?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dotStartX = params.x; dotStartY = params.y
                    dotRawX = e.rawX; dotRawY = e.rawY
                    dotMoved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - dotRawX).toInt()
                    val dy = (e.rawY - dotRawY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        dotMoved = true
                        params.x = dotStartX + dx; params.y = dotStartY + dy
                        savedDotX = params.x; savedDotY = params.y
                        windowManager?.updateViewLayout(frame, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dotMoved) setMinimizedMode(false)
                    true
                }
                else -> false
            }
        }

        overlayRoot = frame
        expandedBox = expanded
        overlayParams = params
        windowManager?.addView(frame, params)
    }

    private fun closeSubTrays(keepPhrases: Boolean = false, keepProfiles: Boolean = false, keepReply: Boolean = false) {
        if (!keepPhrases) phraseScroll?.visibility = View.GONE
        if (!keepProfiles) profileScroll?.visibility = View.GONE
        if (!keepReply && replyRow?.visibility == View.VISIBLE) setReplyInputVisible(false)
    }

    @SuppressLint("MissingPermission")
    private fun startHoldVoiceReply() {
        if (isVoiceReplying.get()) return
        isVoiceReplying.set(true)
        voiceReplyStartMs = System.currentTimeMillis()
        voiceReplyPcm.reset()
        TtsManager.stop()

        btnVoiceReply?.text = "🔴講緊…"
        btnVoiceReply?.setTextColor(Color.WHITE)
        (btnVoiceReply?.background as? GradientDrawable)?.setColor(Color.parseColor("#E11D48"))
        updateOverlayUi("🎤 講廣東話留言，放手即翻…", null, null)

        voiceReplyJob = scope.launch {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            }.getOrNull()

            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                isVoiceReplying.set(false)
                withContext(Dispatchers.Main) {
                    resetVoiceButtonVisual()
                    Toast.makeText(this@TranslationService, "無法啟用咪高峰錄音", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                rec.startRecording()
                val buf = ShortArray(1024)
                while (isActive && isVoiceReplying.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            voiceReplyPcm.write(v and 0xFF)
                            voiceReplyPcm.write((v shr 8) and 0xFF)
                        }
                    }
                }
            } finally {
                runCatching { rec.stop(); rec.release() }
            }
        }
    }

    private fun stopHoldVoiceReplyAndTranslate() {
        if (!isVoiceReplying.getAndSet(false)) return
        val heldMs = System.currentTimeMillis() - voiceReplyStartMs
        resetVoiceButtonVisual()

        scope.launch {
            voiceReplyJob?.join()
            val pcm = voiceReplyPcm.toByteArray()
            if (heldMs < 350 || pcm.size < 4000) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TranslationService, "請按住「🎤按講」唔放講廣東話，放手即翻！", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            updateOverlayUi("● 語音翻日文留言中…", null, null)
            val durSec = (pcm.size / 32000.0).roundToInt().coerceAtLeast(1)
            val wav = addWavHeader(normalizePcm(pcm, boostStrong = false), 16000)
            val res = GeminiApi.translateVoiceReply(this@TranslationService, wav, durSec, TranslatorState.settings.value, TranslatorState.activeProfile())
            withContext(Dispatchers.Main) {
                res.onSuccess { (heard, reply) ->
                    if (reply.isNotBlank()) {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("VoiceReply", reply))
                        updateOverlayUi("✓ 已複製：${reply.take(14)}", null, null)
                        val heardPreview = if (heard.isNotBlank()) "聽到：$heard\n" else ""
                        Toast.makeText(this@TranslationService, "${heardPreview}已複製留言：$reply", Toast.LENGTH_LONG).show()
                    } else {
                        updateOverlayUi("● 聽唔清楚，請再按住重講", null, null)
                        Toast.makeText(this@TranslationService, "聽唔清楚人聲，請講大聲少少再試", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    updateOverlayUi("⚠ 語音留言失敗", null, null)
                    Toast.makeText(this@TranslationService, "語音翻譯失敗：${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resetVoiceButtonVisual() {
        btnVoiceReply?.text = "🎤按講"
        btnVoiceReply?.setTextColor(Color.parseColor("#FCA5A5"))
        (btnVoiceReply?.background as? GradientDrawable)?.setColor(Color.parseColor("#2A1F3D"))
    }

    private fun populateProfileTray() {
        val container = profileContainer ?: return
        container.removeAllViews()
        val activeId = TranslatorState.settings.value.activeProfileId

        fun addChip(label: String, selected: Boolean, onClick: () -> Unit) {
            val pill = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(11), dp(6), dp(11), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (selected) "#4F46E5" else "#334155"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor(if (selected) "#818CF8" else "#475569"))
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, dp(7), 0)
                layoutParams = lp
                setOnClickListener { onClick() }
            }
            container.addView(pill)
        }

        addChip("通用詞庫", activeId == 0L) {
            TranslatorState.selectProfile(this, null)
            btnProfileQuick?.text = profileShortLabel()
            profileScroll?.visibility = View.GONE
            updateOverlayUi("● 已切換：通用詞庫", null, null)
        }
        TranslatorState.profiles.value.forEach { prof ->
            addChip("${prof.name} (${prof.language.shortCode})", activeId == prof.id) {
                TranslatorState.selectProfile(this, prof)
                btnProfileQuick?.text = profileShortLabel()
                profileScroll?.visibility = View.GONE
                updateOverlayUi("● 已換台：${prof.name}", null, null)
                Toast.makeText(this, "已套用詞庫：${prof.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setMinimizedMode(minimize: Boolean) {
        val frame = overlayRoot ?: return
        val params = overlayParams ?: return
        if (minimize) closeSubTrays()
        isMinimized = minimize

        if (minimize) {
            expandedBox?.visibility = View.GONE
            miniDotView?.visibility = View.VISIBLE
            miniDotView?.text = if (TranslatorState.isPaused.value) "⏸" else "譯"
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.START
            params.x = savedDotX
            params.y = savedDotY
        } else {
            miniDotView?.visibility = View.GONE
            expandedBox?.visibility = View.VISIBLE
            params.width = expandedWidthPx
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = savedExpandedY
        }
        windowManager?.updateViewLayout(frame, params)
    }

    private fun setReplyInputVisible(visible: Boolean) {
        val frame = overlayRoot ?: return
        val params = overlayParams ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        if (visible) {
            replyRow?.visibility = View.VISIBLE
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            windowManager?.updateViewLayout(frame, params)
            etReply?.requestFocus()
            etReply?.postDelayed({
                etReply?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
            }, 120)
        } else {
            etReply?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            etReply?.clearFocus()
            replyRow?.visibility = View.GONE
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(frame, params)
        }
    }

    private fun performOverlayReplyTranslate() {
        val input = etReply?.text?.toString()?.trim() ?: ""
        if (input.isBlank()) return
        btnSendReply?.text = "翻譯中…"
        btnSendReply?.isEnabled = false
        val settingsSnap = TranslatorState.settings.value
        val profileSnap = TranslatorState.activeProfile()

        scope.launch {
            val res = GeminiApi.translateReply(this@TranslationService, input, settingsSnap, profileSnap)
            withContext(Dispatchers.Main) {
                btnSendReply?.text = "翻並複製"
                btnSendReply?.isEnabled = true
                res.onSuccess { translated ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("OverlayReply", translated))
                    etReply?.setText("")
                    setReplyInputVisible(false)
                    updateOverlayUi("✓ 已複製留言：${translated.take(14)}", null, null)
                    Toast.makeText(this@TranslationService, "已複製：$translated", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    Toast.makeText(this@TranslationService, "翻譯失敗：${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleOverlayLock() {
        val cur = TranslatorState.settings.value
        val nextLocked = !cur.overlayLocked
        StorageHelper.saveSettings(this, cur.copy(overlayLocked = nextLocked))
        btnLock?.text = if (nextLocked) "🔒" else "🔓"
        btnLock?.setTextColor(Color.parseColor(if (nextLocked) "#FBBF24" else "#94A3B8"))
        overlayBg?.setStroke(dp(1), Color.parseColor(if (nextLocked) "#AAF59E0B" else "#55818CF8"))
        val msg = if (nextLocked) "🔒 已鎖定位置（雙擊解鎖）" else "🔓 已解鎖位置（可拖拉）"
        updateOverlayUi(msg, null, null)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun populateQuickPhrasesTray() {
        val container = phraseContainer ?: return
        container.removeAllViews()
        val langCode = TranslatorState.settings.value.sourceLanguage.shortCode
        val phrases = TranslatorState.quickPhrases.value.filter {
            langCode == "AUTO" || it.langCode == langCode || it.langCode == "JA"
        }

        phrases.forEach { p ->
            val pill = TextView(this).apply {
                text = p.text
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(11), dp(6), dp(11), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#334155"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#6366F1"))
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, dp(7), 0)
                layoutParams = lp
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QuickPhrase", p.text))
                    phraseScroll?.visibility = View.GONE
                    updateOverlayUi("✓ 已複製：${p.text.take(12)}", null, null)
                    Toast.makeText(this@TranslationService, "已複製，可直接貼上留言！", Toast.LENGTH_SHORT).show()
                }
            }
            container.addView(pill)
        }
    }

    private fun adjustFont(delta: Float) {
        val cur = TranslatorState.settings.value
        val next = (cur.overlayFontSize + delta).coerceIn(13f, 28f)
        StorageHelper.saveSettings(this, cur.copy(overlayFontSize = next))
        tvTrans?.textSize = next
        tvOrig?.textSize = (next - 4f).coerceAtLeast(11f)
    }

    @SuppressLint("MissingPermission")
    private fun startCaptureLoop() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val settings = TranslatorState.settings.value

        audioRecord = if (settings.captureMode == CaptureMode.INTERNAL && mediaProjection != null) {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } else {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        }

        audioRecord?.startRecording()

        captureJob = scope.launch {
            val frameSamples = 1600
            val readBuf = ShortArray(frameSamples)
            val preRollFrames = ArrayDeque<ByteArray>()
            val utteranceOut = ByteArrayOutputStream()

            var hasSpoken = false
            var onsetSpeechFrames = 0
            var trailingSilenceMs = 0
            var idleSilenceMs = 0
            var utteranceMs = 0
            var carryOverOverlap = ByteArray(0)

            while (isActive) {
                val cur = TranslatorState.settings.value
                val skipForMicConflict = cur.captureMode == CaptureMode.MICROPHONE && (isVoiceReplying.get() || TtsManager.isDubbingSpeaking.get())
                if (TranslatorState.isPaused.value || skipForMicConflict) {
                    utteranceOut.reset(); preRollFrames.clear()
                    hasSpoken = false; onsetSpeechFrames = 0; trailingSilenceMs = 0; utteranceMs = 0
                    carryOverOverlap = ByteArray(0)
                    delay(200)
                    continue
                }

                var samplesRead = 0
                var sumSq = 0.0
                val frameBytes = ByteArray(frameSamples * 2)

                while (samplesRead < frameSamples && isActive && !TranslatorState.isPaused.value) {
                    val n = audioRecord?.read(readBuf, 0, frameSamples - samplesRead) ?: 0
                    if (n > 0) {
                        for (i in 0 until n) {
                            val v = readBuf[i].toInt()
                            sumSq += v * v
                            val idx = (samplesRead + i) * 2
                            frameBytes[idx] = (v and 0xFF).toByte()
                            frameBytes[idx + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        samplesRead += n
                    }
                }
                if (samplesRead == 0 || TranslatorState.isPaused.value) continue

                val rms = sqrt(sumSq / samplesRead).toInt()
                TranslatorState.currentRms.value = rms
                val effectiveThreshold = if (cur.lowVolumeBoost) (cur.silenceThreshold / 4).coerceAtLeast(35) else cur.silenceThreshold
                val isSpeech = rms >= effectiveThreshold

                if (!hasSpoken) {
                    preRollFrames.addLast(frameBytes)
                    while (preRollFrames.size > 3) preRollFrames.removeFirst()

                    if (isSpeech) {
                        onsetSpeechFrames++
                        idleSilenceMs = 0
                        if (onsetSpeechFrames >= 2) {
                            hasSpoken = true
                            utteranceOut.reset()
                            if (carryOverOverlap.isNotEmpty()) utteranceOut.write(carryOverOverlap)
                            else preRollFrames.forEach { utteranceOut.write(it) }
                            preRollFrames.clear()
                            utteranceMs = utteranceOut.size() / 32
                            trailingSilenceMs = 0
                            updateOverlayUi("● ${cur.sourceLanguage.shortCode} 收音中…", null, null)
                        }
                    } else {
                        onsetSpeechFrames = 0
                        idleSilenceMs += 100
                        if (idleSilenceMs >= 120_000) {
                            TranslatorState.isPaused.value = true
                            scope.launch(Dispatchers.Main) {
                                btnPause?.text = "▶"
                                miniDotView?.text = "⏸"
                            }
                            updateOverlayUi("● 靜音 2 分鐘已暫停", null, null)
                        } else if (idleSilenceMs % 1500 == 0) {
                            updateOverlayUi("● 靜音待機（音量 $rms）", null, null)
                        }
                    }
                } else {
                    utteranceOut.write(frameBytes)
                    utteranceMs += 100
                    trailingSilenceMs = if (isSpeech) 0 else trailingSilenceMs + 100

                    val maxMs = cur.chunkSeconds.coerceIn(3, 8) * 1000
                    val pauseTrigger = cur.smartVad && trailingSilenceMs >= cur.pauseMs.coerceIn(250, 900) && utteranceMs >= 1000
                    val maxLenTrigger = utteranceMs >= maxMs

                    if (pauseTrigger || maxLenTrigger) {
                        val rawPcm = utteranceOut.toByteArray()
                        val overlapLen = (cur.overlapMs.coerceIn(0, 1000) * 32).coerceAtMost(rawPcm.size)
                        val keepLen = if (maxLenTrigger) overlapLen else (250 * 32).coerceAtMost(overlapLen)
                        carryOverOverlap = if (keepLen > 0) rawPcm.copyOfRange(rawPcm.size - keepLen, rawPcm.size) else ByteArray(0)

                        val durationSec = (rawPcm.size / 32000.0).roundToInt().coerceAtLeast(1)
                        val reason = if (pauseTrigger) "停頓斷句" else "重疊切段"

                        utteranceOut.reset()
                        hasSpoken = false; onsetSpeechFrames = 0; trailingSilenceMs = 0; utteranceMs = 0

                        val processedPcm = if (cur.autoGain || cur.lowVolumeBoost) {
                            normalizePcm(rawPcm, boostStrong = cur.lowVolumeBoost)
                        } else rawPcm
                        dispatchTranslation(processedPcm, durationSec, reason, cur, TranslatorState.activeProfile())
                    }
                }
            }
        }
    }

    private fun normalizePcm(pcm: ByteArray, boostStrong: Boolean): ByteArray {
        val sampleCount = pcm.size / 2
        var peak = 1
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            val a = abs(s)
            if (a > peak) peak = a
        }
        val minPeak = if (boostStrong) 40 else 400
        val maxGain = if (boostStrong) 14.0 else 4.0
        if (peak !in minPeak..16000) return pcm
        val gain = (22000.0 / peak).coerceAtMost(maxGain)
        val out = ByteArray(pcm.size)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = (hi shl 8) or lo
            val boosted = (s * gain).roundToInt().coerceIn(-32767, 32767)
            out[i * 2] = (boosted and 0xFF).toByte()
            out[i * 2 + 1] = ((boosted shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun dispatchTranslation(
        pcmBytes: ByteArray,
        durationSec: Int,
        reason: String,
        settingsSnapshot: AppSettings,
        profileSnapshot: StreamerProfile?
    ) {
        scope.launch {
            apiMutex.withLock {
                updateOverlayUi("● $reason，翻譯中…", null, null)
                val wavBytes = addWavHeader(pcmBytes, 16000)
                val result = GeminiApi.translateAudio(this@TranslationService, wavBytes, durationSec, settingsSnapshot, profileSnapshot, TranslatorState.history.value)
                result.onSuccess { (orig, trans) ->
                    if (trans.isNotBlank() && trans != "SILENT") {
                        TranslatorState.addItem(
                            TranscriptItem(
                                original = orig,
                                translated = trans,
                                langCode = settingsSnapshot.sourceLanguage.shortCode,
                                profileName = profileSnapshot?.name ?: ""
                            )
                        )
                        StorageHelper.saveHistory(this@TranslationService)
                        val latestCfg = TranslatorState.settings.value
                        if (latestCfg.liveDubbing && !TranslatorState.isPaused.value) {
                            TtsManager.speakDubbing(this@TranslationService, trans, latestCfg)
                        }
                        val pTag = profileSnapshot?.name?.take(5)?.let { "·$it" } ?: ""
                        updateOverlayUi("● ${settingsSnapshot.sourceLanguage.shortCode}$pTag", orig, trans, resetStar = true)
                    } else {
                        updateOverlayUi("● 智能聆聽中…", null, null)
                    }
                }.onFailure { err ->
                    val fullMsg = err.message ?: "未知錯誤"
                    TranslatorState.lastApiError.value = fullMsg
                    val headline = fullMsg.lineSequence().firstOrNull()?.take(36) ?: "API 錯誤"
                    updateOverlayUi("⚠ $headline", "完整錯誤（可到主控台複製）", fullMsg)
                }
            }
        }
    }

    private fun updateOverlayUi(status: String, orig: String?, trans: String?, resetStar: Boolean = false) {
        TranslatorState.statusText.value = status
        val showCost = TranslatorState.settings.value.showCostOnOverlay
        val costText = formatCostBadge()
        scope.launch(Dispatchers.Main) {
            tvStatus?.text = status
            tvCostBadge?.visibility = if (showCost) View.VISIBLE else View.GONE
            tvCostBadge?.text = costText
            if (orig != null) tvOrig?.text = orig
            if (trans != null) tvTrans?.text = trans
            if (resetStar) {
                btnStar?.text = "☆"
                btnStar?.setTextColor(Color.parseColor("#CBD5E1"))
                if (isMinimized) miniDotView?.text = "譯•"
            }
        }
    }

    private fun addWavHeader(pcm: ByteArray, sampleRate: Int): ByteArray {
        val totalLen = pcm.size + 36
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        fun writeStr(o: Int, s: String) = s.forEachIndexed { i, c -> header[o + i] = c.code.toByte() }
        fun writeInt(o: Int, v: Int) {
            header[o] = (v and 0xff).toByte(); header[o + 1] = (v shr 8 and 0xff).toByte()
            header[o + 2] = (v shr 16 and 0xff).toByte(); header[o + 3] = (v shr 24 and 0xff).toByte()
        }
        writeStr(0, "RIFF"); writeInt(4, totalLen); writeStr(8, "WAVE"); writeStr(12, "fmt ")
        writeInt(16, 16); header[20] = 1; header[22] = 1; writeInt(24, sampleRate)
        writeInt(28, byteRate); header[32] = 2; header[34] = 16; writeStr(36, "data"); writeInt(40, pcm.size)
        return header + pcm
    }

    private fun stopEverything() {
        isVoiceReplying.set(false)
        voiceReplyJob?.cancel()
        captureJob?.cancel()
        TtsManager.stop()
        mediaButtonReceiver?.let {
            runCatching { unregisterReceiver(it) }
            mediaButtonReceiver = null
        }
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        runCatching { mediaProjection?.stop() }
        overlayRoot?.let { runCatching { windowManager?.removeView(it) } }
        overlayRoot = null
        expandedBox = null
        miniDotView = null
        overlayParams = null
        isMinimized = false
        TranslatorState.isRunning.value = false
        TranslatorState.isPaused.value = false
        TranslatorState.currentRms.value = 0
        TranslatorState.statusText.value = "已停止"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }
}
