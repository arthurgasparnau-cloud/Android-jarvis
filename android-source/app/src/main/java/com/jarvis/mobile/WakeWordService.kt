package com.jarvis.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.concurrent.thread

class WakeWordService : Service() {
    companion object {
        const val ACTION_START = "com.jarvis.mobile.WAKE_START"
        const val ACTION_STOP = "com.jarvis.mobile.WAKE_STOP"
        const val ACTION_STATE = "com.jarvis.mobile.WAKE_STATE"
        const val ACTION_WAKE = "com.jarvis.mobile.WAKE_DETECTED"
        const val ACTION_COMMAND = "com.jarvis.mobile.WAKE_COMMAND"
        const val ACTION_STOP_SPEAKING = "com.jarvis.mobile.STOP_SPEAKING"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_STATE = "state"
        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 7314
        private const val SAMPLE_RATE = 16000
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var commandSessionActive = false
    private var lastCommandAt = 0L

    // A conversa não expira por silêncio: o reconhecedor permanece ativo
    // até um comando explícito de modo silencioso ou desligamento total.
    private val sessionTimeout = Runnable { }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("wake_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendState("Microfone sem permissão")
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification("Escuta local ativa • aguardando Hey JARVIS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val powerManager = getSystemService(PowerManager::class.java)
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS:WakeWord")
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
        if (spotter == null && recordingThread == null) {
            thread(name = "JarvisWakeInit") {
                try {
                    initializeSpotter()
                    startKwsCapture()
                } catch (error: Exception) {
                    mainHandler.post {
                        updateNotification("Falha ao iniciar ativação: ${error.message ?: "erro do mecanismo"}")
                        sendState("Falha ao carregar o modelo de ativação")
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun initializeSpotter() {
        val modelDir = File(filesDir, "wakeword")
        if (!modelDir.exists()) modelDir.mkdirs()
        assets.list("wakeword")?.forEach { name ->
            val target = File(modelDir, name)
            if (!target.exists() || target.length() == 0L) {
                assets.open("wakeword/$name").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-13-avg-2-chunk-16-left-64.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer2",
                modelingUnit = "phone"
            ),
            maxActivePaths = 4,
            keywordsFile = File(modelDir, "keywords.txt").absolutePath,
            keywordsScore = 1.5f,
            keywordsThreshold = 0.35f,
            numTrailingBlanks = 1
        )
        spotter = KeywordSpotter(config = config)
    }

    private fun startKwsCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) throw IllegalStateException("Microfone indisponível")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes * 2, 6400)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Não foi possível inicializar o microfone")
        }
        audioRecord = record
        stream?.release()
        stream = spotter?.createStream()
        recording = true
        record.startRecording()
        sendState("Modo silencioso • aguardando JARVIS")
        updateNotification("Modo silencioso • aguardando JARVIS")
        recordingThread = thread(name = "JarvisWakeAudio") {
            val shortBuffer = ShortArray(1600)
            try {
                while (recording) {
                    val count = record.read(shortBuffer, 0, shortBuffer.size)
                    if (count <= 0) {
                        if (!recording) break
                        continue
                    }
                    val samples = FloatArray(count) { shortBuffer[it] / 32768.0f }
                    val currentStream = stream ?: break
                    val currentSpotter = spotter ?: break
                    currentStream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                    while (currentSpotter.isReady(currentStream)) {
                        currentSpotter.decode(currentStream)
                        val keyword = currentSpotter.getResult(currentStream).keyword
                        if (keyword.isNotBlank()) {
                            currentSpotter.reset(currentStream)
                            recording = false
                            try { record.stop() } catch (_: Exception) {}
                            mainHandler.post { onWakeWordDetected() }
                            return@thread
                        }
                    }
                }
            } catch (_: Exception) {
                if (recording) {
                    mainHandler.post {
                        updateNotification("Erro na captura do microfone")
                        sendState("Erro na captura do microfone")
                    }
                }
            } finally {
                try { record.release() } catch (_: Exception) {}
                if (audioRecord === record) audioRecord = null
                recordingThread = null
            }
        }
    }

    private fun onWakeWordDetected() {
        updateNotification("JARVIS ativado • pode falar")
        sendBroadcast(Intent(ACTION_WAKE).setPackage(packageName))
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            updateNotification("Hey JARVIS detectado • reconhecimento offline de comandos indisponível")
            sendState("Ativação detectada; ASR offline não disponível neste aparelho")
            startKwsAgainAfterNotice()
            return
        }
        mainHandler.postDelayed({ startCommandSession() }, 250L)
    }

    private fun startKwsAgainAfterNotice() {
        mainHandler.postDelayed({
            if (prefs.getBoolean("wake_enabled", true)) {
                try { startKwsCapture() } catch (_: Exception) { stopSelf() }
            }
        }, 2500L)
    }

    private fun startCommandSession() {
        commandSessionActive = true
        lastCommandAt = SystemClock.elapsedRealtime()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    updateNotification("JARVIS ouvindo • fale seu comando")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (commandSessionActive) {
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            updateNotification("Reconhecimento offline pt-BR indisponível • verifique os serviços de voz do Android")
                            sendState("O reconhecimento offline em português não está disponível")
                            endCommandSession()
                        } else {
                            // Erros transitórios e silêncio não encerram a conversa.
                            mainHandler.postDelayed({ startCommandListening() }, 350L)
                        }
                    }
                }
                override fun onResults(results: android.os.Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (phrase.isNotBlank()) handleRecognizedCommand(phrase)
                    if (commandSessionActive) {
                        mainHandler.postDelayed({ startCommandListening() }, 250L)
                    }
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            startCommandListening()
            refreshSessionTimeout()
        } catch (_: Exception) {
            commandSessionActive = false
            speechRecognizer?.destroy()
            speechRecognizer = null
            startKwsAgainAfterNotice()
        }
    }

    private fun startCommandListening() {
        if (!commandSessionActive || speechRecognizer == null) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            if (commandSessionActive) mainHandler.postDelayed({ startCommandListening() }, 500L)
        }
    }

    private fun handleRecognizedCommand(phrase: String) {
        val normalized = java.text.Normalizer.normalize(
            phrase.lowercase(Locale("pt", "BR")), java.text.Normalizer.Form.NFD
        ).replace("\\p{Mn}+".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .trim()
            .replace("\\s+".toRegex(), " ")
        val command = normalized.removePrefix("hey jarvis ").removePrefix("jarvis ").trim()

        if (command == "desligar sistema" || command == "desligue sistema" ||
            command == "desliga sistema" || command == "encerrar sistema de voz") {
            sendBroadcast(Intent(ACTION_STOP_SPEAKING).setPackage(packageName))
            prefs.edit().putBoolean("wake_enabled", false).apply()
            commandSessionActive = false
            sendState("Monitoramento por voz desligado")
            updateNotification("Monitoramento por voz desligado")
            stopSelf()
            return
        }

        if (command == "desligar" || command == "desliga" || command == "desligue" ||
            command == "parar" || command == "pare" || command == "para" ||
            command == "chega" || command == "silencio" || command == "cala a boca" ||
            command == "cale a boca" || command == "pare de falar" || command == "para de falar" ||
            command == "interrompa" || command == "interromper" || command == "stop") {
            sendBroadcast(Intent(ACTION_STOP_SPEAKING).setPackage(packageName))
            updateNotification("Modo silencioso • aguardando JARVIS")
            sendState("Modo silencioso • aguardando JARVIS")
            endCommandSession()
            return
        }

        prefs.edit().putString("pending_voice_command", phrase).apply()
        sendBroadcast(
            Intent(ACTION_COMMAND).setPackage(packageName).putExtra(EXTRA_COMMAND, phrase)
        )
        updateNotification("Comando reconhecido • conversa ativa")
        lastCommandAt = SystemClock.elapsedRealtime()
    }

    private fun refreshSessionTimeout() {
        // Mantido como ponto de compatibilidade; não encerra a conversa por silêncio.
        mainHandler.removeCallbacks(sessionTimeout)
    }

    private fun endCommandSession() {
        commandSessionActive = false
        mainHandler.removeCallbacks(sessionTimeout)
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (prefs.getBoolean("wake_enabled", true)) {
            try {
                startKwsCapture()
            } catch (error: Exception) {
                updateNotification("Não foi possível rearmar a escuta: ${error.message ?: "erro"}")
            }
        }
    }

    private fun sendState(value: String) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, value))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ativação Hey JARVIS", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indica quando a ativação por voz está habilitada"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this, 2, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S. • ativação por voz")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Desativar", stopPending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        // Não apagar a preferência em encerramentos inesperados do processo.
        // O comando explícito ACTION_STOP ou "desligar sistema" já grava false.
        recording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        speechRecognizer?.destroy()
        speechRecognizer = null
        spotter?.release()
        spotter = null
        stream?.release()
        stream = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        mainHandler.removeCallbacksAndMessages(null)
        sendState("Hey JARVIS desligado")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
