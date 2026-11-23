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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
        const val ACTION_RESUME = "com.jarvis.mobile.WAKE_RESUME"
        const val ACTION_STATE = "com.jarvis.mobile.WAKE_STATE"
        const val ACTION_WAKE = "com.jarvis.mobile.WAKE_DETECTED"
        const val ACTION_COMMAND = "com.jarvis.mobile.WAKE_COMMAND"
        const val ACTION_RESPONSE = "com.jarvis.mobile.WAKE_RESPONSE"
        const val EXTRA_RESPONSE = "response"
        const val ACTION_AUDIO_LEVEL = "com.jarvis.mobile.WAKE_AUDIO_LEVEL"
        const val EXTRA_AUDIO_LEVEL = "audio_level"
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
    private var awaitingCommand = false
    @Volatile private var processingCommand = false
    private var lastCommandAt = 0L

    // A conversa nÃ£o expira por silÃªncio: o reconhecedor permanece ativo
    // atÃ© um comando explÃ­cito de modo silencioso ou desligamento total.
    private val sessionTimeout = Runnable { }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resumeRequested = intent?.action == ACTION_RESUME
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("wake_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendState("Microfone sem permissÃ£o")
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification("Escuta local ativa â€¢ aguardando Hey JARVIS")
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
        if (resumeRequested) {
            awaitingCommand = false
            commandSessionActive = false
            mainHandler.post { startCommandSession() }
        } else if (!commandSessionActive) {
            // Reconhecimento Google contínuo dentro do serviço em primeiro plano.
            // Não abre Activity: a transcrição e os comandos são tratados em background.
            startCommandSession()
        }
        /*
            thread(name = "JarvisWakeInit") {
                try {
                    initializeSpotter()
                    startKwsCapture()
                } catch (error: Exception) {
                    mainHandler.post {
                        updateNotification("Falha ao iniciar ativaÃ§Ã£o: ${error.message ?: "erro do mecanismo"}")
                        sendState("Falha ao carregar o modelo de ativaÃ§Ã£o")
                        stopSelf()
                    }
                }
            }
        }
        */
        return START_STICKY
    }

    private fun initializeSpotter() {
        val modelDir = File(filesDir, "wakeword")
        if (!modelDir.exists()) modelDir.mkdirs()
        assets.list("wakeword")?.forEach { name ->
            val target = File(modelDir, name)
            // Atualizar sempre o arquivo de palavras-chave em upgrades do APK.
            // Os modelos ONNX grandes permanecem em cache para evitar cÃ³pias desnecessÃ¡rias.
            if (name == "keywords.txt" || !target.exists() || target.length() == 0L) {
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
            // Limiar menor para acomodar pronúncias brasileiras e variações de microfone.
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1
        )
        spotter = KeywordSpotter(config = config)
    }

    private fun startKwsCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) throw IllegalStateException("Microfone indisponÃ­vel")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes * 2, 6400)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("NÃ£o foi possÃ­vel inicializar o microfone")
        }
        audioRecord = record
        stream?.release()
        stream = spotter?.createStream()
        recording = true
        record.startRecording()
        sendState("Modo silencioso â€¢ aguardando JARVIS")
        updateNotification("Modo silencioso â€¢ aguardando JARVIS")
        recordingThread = thread(name = "JarvisWakeAudio") {
            val shortBuffer = ShortArray(1600)
            try {
                while (recording) {
                    val count = record.read(shortBuffer, 0, shortBuffer.size)
                    if (count <= 0) {
                        if (!recording) break
                        continue
                    }
                    var sumSquares = 0.0
                    var peak = 0f
                    for (i in 0 until count) {
                        val sample = shortBuffer[i] / 32768.0f
                        sumSquares += sample * sample
                        peak = maxOf(peak, kotlin.math.abs(sample))
                    }
                    val rms = kotlin.math.sqrt(sumSquares / count).toFloat()
                    // Envia somente o nÃ­vel do Ã¡udio, nunca o conteÃºdo da fala.
                    sendBroadcast(
                        Intent(ACTION_AUDIO_LEVEL).setPackage(packageName)
                            .putExtra(EXTRA_AUDIO_LEVEL, maxOf(rms, peak * 0.35f))
                    )
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
        prefs.edit().putBoolean("wake_detected_pending", true).apply()
        updateNotification("Hey JARVIS detectado • toque para falar")
        sendBroadcast(Intent(ACTION_WAKE).setPackage(packageName))
        sendState("Hey JARVIS detectado • abra a notificação para falar")
        // Mantém o detector pausado até o usuário abrir a notificação e concluir o comando.
        // Isso evita dois reconhecedores disputando o microfone ao retornar ao app.
    }

    private fun rearmKeywordCapture() {
        if (!prefs.getBoolean("wake_enabled", true) || spotter == null) return
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!prefs.getBoolean("wake_enabled", true)) return
                if (recordingThread != null || recording) {
                    mainHandler.postDelayed(this, 250L)
                    return
                }
                try {
                    startKwsCapture()
                    updateNotification("Escuta local ativa • aguardando Hey JARVIS")
                    sendState("Aguardando Hey JARVIS")
                } catch (error: Exception) {
                    updateNotification("Falha ao rearmar escuta: ${error.message ?: "erro"}")
                    sendState("Falha ao rearmar a escuta Hey JARVIS")
                }
            }
        })
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
            // Mesmo serviÃ§o de reconhecimento usado pelo microfone manual do app.
            // O Android escolhe o mecanismo disponÃ­vel (offline ou online).
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    updateNotification("JARVIS ouvindo â€¢ fale seu comando")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (commandSessionActive) {
                        updateNotification("Reconhecimento Google aguardando • erro $error, tentando novamente")
                        // Alguns provedores Android deixam a sessão antiga presa após um erro.
                        // Recria o SpeechRecognizer em vez de insistir no mesmo objeto.
                        mainHandler.postDelayed({ startCommandSession() }, 900L)
                    }
                }
                override fun onResults(results: android.os.Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    val normalized = java.text.Normalizer.normalize(
                        phrase.lowercase(Locale("pt", "BR")), java.text.Normalizer.Form.NFD
                    ).replace("\\p{Mn}+".toRegex(), "").replace("[^a-z0-9 ]".toRegex(), " ").trim()
                    if (normalized.contains("jarvis")) {
                        val commandAfterWake = phrase.replace(
                            Regex("(?i)^(hey\\s+)?jarvis[,.\\s]*"), ""
                        ).trim()
                        if (commandAfterWake.isNotBlank()) {
                            awaitingCommand = false
                            handleRecognizedCommand(commandAfterWake)
                        } else {
                            awaitingCommand = true
                            updateNotification("Hey JARVIS ativado • fale seu comando")
                            sendState("Hey JARVIS ativado • aguardando comando")
                            mainHandler.postDelayed({ startCommandSession() }, 350L)
                        }
                    } else if (awaitingCommand && phrase.isNotBlank()) {
                        awaitingCommand = false
                        handleRecognizedCommand(phrase)
                    } else if (commandSessionActive) {
                        mainHandler.postDelayed({ startCommandSession() }, 350L)
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
            mainHandler.postDelayed({ startCommandSession() }, 1000L)
        }
    }

    private fun startCommandListening() {
        if (!commandSessionActive || speechRecognizer == null) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            if (commandSessionActive) mainHandler.postDelayed({ startCommandSession() }, 900L)
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
            updateNotification("Modo silencioso â€¢ aguardando JARVIS")
            sendState("Modo silencioso â€¢ aguardando JARVIS")
            endCommandSession()
            return
        }

        commandSessionActive = false
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        speechRecognizer?.destroy()
        speechRecognizer = null
        awaitingCommand = false
        lastCommandAt = SystemClock.elapsedRealtime()
        if (tryLaunchPhoneApp(command)) {
            mainHandler.postDelayed({ startCommandSession() }, 700L)
            return
        }
        processVoiceCommand(phrase)
    }

    private fun tryLaunchPhoneApp(command: String): Boolean {
        val prefixes = listOf("abra ", "abrir ", "abre ", "inicie ", "iniciar ", "execute ", "executar ", "open ", "launch ")
        val prefix = prefixes.firstOrNull { command.startsWith(it) } ?: return false
        var target = command.removePrefix(prefix).trim()
        target = target.replace(Regex("^(o|a|app|aplicativo|aplicação)\\s+"), "").trim()
        if (target.isBlank()) return false

        val canonical = when {
            target in setOf("youtube", "you tube", "yt", "youtube music") -> "YouTube"
            target in setOf("google", "pesquisa google", "pesquisa do google", "google pesquisa") -> "Google"
            target in setOf("chrome", "google chrome", "navegador", "internet") -> "Chrome"
            else -> target
        }
        val knownPackages = when (canonical) {
            "Google" -> listOf("com.google.android.googlequicksearchbox", "com.android.chrome")
            "YouTube" -> listOf("com.google.android.youtube")
            "Chrome" -> listOf("com.android.chrome")
            else -> emptyList()
        }
        val packageMatch = knownPackages.firstNotNullOfOrNull { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)?.let { packageName to it }
        }
        val intent = packageMatch?.second ?: run {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val match = packageManager.queryIntentActivities(launcher, 0).firstOrNull { info ->
                val label = info.loadLabel(packageManager)?.toString().orEmpty()
                val normalizedLabel = java.text.Normalizer.normalize(label.lowercase(Locale("pt", "BR")), java.text.Normalizer.Form.NFD)
                    .replace("\\p{Mn}+".toRegex(), "")
                normalizedLabel.contains(target) || target.contains(normalizedLabel)
            } ?: return false
            packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        }

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            updateNotification("Abrindo $canonical")
            sendState("Abrindo aplicativo: $canonical")
            true
        } catch (_: Exception) {
            val launchPending = PendingIntent.getActivity(
                this, 1000 + canonical.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            updateNotification("Toque em Abrir $canonical para concluir o comando", canonical, launchPending)
            sendState("O Android exige toque na notificação para abrir $canonical")
            true
        }
    }

    // O serviço executa comandos de voz sem depender da Activity estar aberta.
    // A tela recebe apenas o resultado para atualizar o histórico visual.
    private fun processVoiceCommand(phrase: String) {
        if (processingCommand) {
            updateNotification("Comando recebido • aguardando o anterior")
            return
        }
        processingCommand = true
        updateNotification("Comando reconhecido • consultando núcleo")
        sendState("Comando reconhecido • processando no núcleo")
        thread(name = "Jarvis-Voice-Command") {
            var answer = ""
            try {
                val baseUrl = prefs.getString("base_url", "https://arthur.tail25244c.ts.net")
                    ?: "https://arthur.tail25244c.ts.net"
                val token = prefs.getString("token", "").orEmpty()
                if (token.isBlank()) throw IllegalStateException("Conexão com o núcleo não configurada no aplicativo.")
                var conversationId = prefs.getString("conversation_id", "").orEmpty()
                if (conversationId.isBlank()) {
                    val created = coreRequest(baseUrl, token, "POST", "/api/conversations", JSONObject())
                    conversationId = created.optString("id")
                    if (conversationId.isBlank()) throw IllegalStateException("Não foi possível iniciar uma conversa com o núcleo.")
                    prefs.edit().putString("conversation_id", conversationId).apply()
                }
                val body = JSONObject()
                    .put("message", phrase)
                    .put("conversation_id", conversationId)
                    .put("source", "android_background_voice")
                val response = coreRequest(baseUrl, token, "POST", "/api/chat", body)
                answer = response.optString("answer", "").trim()
                if (answer.isBlank()) throw IllegalStateException("O núcleo não retornou uma resposta.")
                prefs.edit().putString("pending_voice_response", answer).apply()
                sendBroadcast(Intent(ACTION_RESPONSE).setPackage(packageName).putExtra(EXTRA_RESPONSE, answer))

                updateNotification("Resposta recebida • gerando voz Brian")
                try {
                    val voice = coreRequest(baseUrl, token, "POST", "/api/tts", JSONObject().put("text", answer))
                    val encoded = voice.optString("audio_base64", "")
                    if (encoded.isBlank()) throw IllegalStateException("O serviço de voz não retornou áudio.")
                    val pcm = Base64.decode(encoded, Base64.DEFAULT)
                    playBrianAudio(pcm, voice.optInt("sample_rate", 24000))
                } catch (voiceError: Exception) {
                    val detail = voiceError.message ?: "erro de áudio"
                    updateNotification("Resposta em texto; falha no áudio Brian: $detail")
                    sendState("Resposta recebida, mas a voz Brian falhou: $detail")
                }
            } catch (error: Exception) {
                val message = error.message ?: "Falha ao processar o comando."
                prefs.edit().putString("pending_voice_response", "Não consegui concluir: $message").apply()
                sendBroadcast(Intent(ACTION_RESPONSE).setPackage(packageName).putExtra(EXTRA_RESPONSE, "Não consegui concluir: $message"))
                sendState("Falha no comando: $message")
            } finally {
                processingCommand = false
                if (prefs.getBoolean("wake_enabled", true)) {
                    mainHandler.post {
                        updateNotification("Google ouvindo • aguardando Hey JARVIS")
                        sendState("Resposta concluída • Google aguardando Hey JARVIS")
                        mainHandler.postDelayed({ startCommandSession() }, 700L)
                    }
                }
            }
        }
    }

    private fun coreRequest(baseUrl: String, token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = if (path == "/api/chat") 190000 else 30000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val result = JSONObject(raw)
            if (code !in 200..299) throw IllegalStateException(result.optString("error", "HTTP $code"))
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun playBrianAudio(bytes: ByteArray, sampleRate: Int) {
        if (bytes.isEmpty() || sampleRate <= 0) throw IllegalArgumentException("Áudio Brian inválido.")
        val sampleCount = bytes.size / 2
        val samples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val low = bytes[i * 2].toInt() and 0xff
            val high = bytes[i * 2 + 1].toInt()
            samples[i] = ((high shl 8) or low).toShort()
        }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IllegalStateException("Saída de áudio indisponível.")
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val audioManager = getSystemService(AudioManager::class.java)
        val focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .build()
        } else null
        val focusGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!focusGranted) throw IllegalStateException("Não foi possível obter foco de áudio do Android.")
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, 8192))
            .build()
        try {
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("Falha na reprodução Brian: $written")
                if (written == 0) continue
                offset += written
            }
            while (track.playbackHeadPosition < sampleCount) Thread.sleep(25L)
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioManager.abandonAudioFocusRequest(focusRequest!!)
            else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }

    private fun refreshSessionTimeout() {
        // Mantido como ponto de compatibilidade; nÃ£o encerra a conversa por silÃªncio.
        mainHandler.removeCallbacks(sessionTimeout)
    }

    private fun endCommandSession() {
        commandSessionActive = false
        mainHandler.removeCallbacks(sessionTimeout)
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        speechRecognizer?.destroy()
        speechRecognizer = null
        awaitingCommand = false
        if (prefs.getBoolean("wake_enabled", true)) {
            updateNotification("Google ouvindo • aguardando Hey JARVIS")
            mainHandler.postDelayed({ startCommandSession() }, 500L)
        }
    }

    private fun sendState(value: String) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, value))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AtivaÃ§Ã£o Hey JARVIS", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Indica quando a ativaÃ§Ã£o por voz estÃ¡ habilitada"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        message: String,
        launchLabel: String? = null,
        launchPending: PendingIntent? = null
    ): Notification {
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
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S. • ativação por voz")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openPending)
            .setOngoing(true)
        if (launchPending != null && !launchLabel.isNullOrBlank()) {
            builder.addAction(android.R.drawable.ic_media_play, "Abrir $launchLabel", launchPending)
        }
        return builder.addAction(android.R.drawable.ic_media_pause, "Desativar", stopPending).build()
    }

    private fun updateNotification(
        message: String,
        launchLabel: String? = null,
        launchPending: PendingIntent? = null
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message, launchLabel, launchPending))
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        // NÃ£o apagar a preferÃªncia em encerramentos inesperados do processo.
        // O comando explÃ­cito ACTION_STOP ou "desligar sistema" jÃ¡ grava false.
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


