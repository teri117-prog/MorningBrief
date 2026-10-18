package com.morningbrief

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 뉴스·날씨를 가져와 TTS로 읽어주는 포그라운드 서비스.
 * 알람 시각에 AlarmReceiver가 시작하거나, 앱의 "지금 들어보기" 버튼으로 시작한다.
 */
class BriefingService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL_ID = "briefing"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.morningbrief.STOP"
        private const val LAST_ID = "brief_end"

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, BriefingService::class.java)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // 백그라운드 시작 제한 등으로 실패한 경우 무시 (다음 알람에 재시도)
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, BriefingService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (e: Exception) {
                ctx.stopService(Intent(ctx, BriefingService::class.java))
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var script: String? = null
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(buildNotification("브리핑을 준비하고 있어요…"))

        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        isRunning = true

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MorningBrief:speak").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 최대 10분
        }

        tts = TextToSpeech(this, this)
        executor.execute {
            val text = BriefingBuilder.build(this)
            main.post {
                if (!isRunning) return@post
                script = text
                notify(buildNotification("아침 브리핑을 읽고 있어요"))
                speakIfReady()
            }
        }

        // 안전장치: 10분이 지나면 자동 종료
        main.postDelayed({ finish() }, 10 * 60 * 1000L)
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            finish()
            return
        }
        val res = engine.setLanguage(Locale.KOREAN)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 한국어 음성이 없으면 기본 언어로 시도
            engine.setLanguage(Locale.getDefault())
        }
        engine.setAudioAttributes(audioAttrs)
        engine.setSpeechRate(Prefs.speechRate(this))
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == LAST_ID) main.post { finish() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == LAST_ID) main.post { finish() }
            }
        })
        ttsReady = true
        speakIfReady()
    }

    private fun speakIfReady() {
        val engine = tts ?: return
        val text = script ?: return
        if (!ttsReady) return
        requestFocus()

        val maxLen = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3000)
        val chunks = text.split("\n")
            .flatMap { line -> line.chunked(maxLen) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        chunks.forEachIndexed { i, chunk ->
            val id = if (i == chunks.lastIndex) LAST_ID else "part_$i"
            engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
            if (i != chunks.lastIndex) engine.playSilentUtterance(400, TextToSpeech.QUEUE_ADD, "gap_$i")
        }
        if (chunks.isEmpty()) finish()
    }

    private fun requestFocus() {
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun finish() {
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (tts != null || isRunning) {
            tts?.shutdown()
            tts = null
            wakeLock?.let { if (it.isHeld) it.release() }
            isRunning = false
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    // ----- 알림 -----

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "아침 브리핑", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, BriefingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("모닝 브리핑")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "멈추기", stop).build()
            )
            .build()
    }
}
