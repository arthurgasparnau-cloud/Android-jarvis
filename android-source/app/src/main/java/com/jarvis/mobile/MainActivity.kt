package com.jarvis.mobile

import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.widget.PopupWindow
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val navy = Color.rgb(5, 12, 22)
    private val panel = Color.rgb(11, 25, 40)
    private val cyan = Color.rgb(73, 218, 255)
    private val white = Color.rgb(229, 244, 250)
    private val muted = Color.rgb(128, 164, 181)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var coreConnected = false
    @Volatile private var connectionCheckRunning = false
    private val connectionMonitor = object : Runnable {
        override fun run() {
            checkCoreConnection()
            mainHandler.postDelayed(this, 30000L)
        }
    }
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }

    private lateinit var root: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var conversationButton: Button
    private lateinit var micButton: Button
    private lateinit var wakeButton: Button
    private var drawerPopup: PopupWindow? = null
    private var drawerPanel: View? = null
    private var wakeReceiverRegistered = false
    private var pendingWakeStart = false
    private val notificationPermissionRequest = 4104
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WakeWordService.ACTION_STATE -> {
                    val state = intent.getStringExtra(WakeWordService.EXTRA_STATE).orEmpty()
                    setStatus(state, state.contains("ativado", true) || state.contains("ouvindo", true))
                    updateWakeButton()
                }
                WakeWordService.ACTION_WAKE -> setStatus("Hey JARVIS detectado • pode falar", true)
                WakeWordService.ACTION_COMMAND -> {
                    val command = intent.getStringExtra(WakeWordService.EXTRA_COMMAND).orEmpty()
                    prefs.edit().remove("pending_voice_command").apply()
                    if (command.isNotBlank()) {
                        input.setText(command)
                        sendMessage()
                    }
                }
                WakeWordService.ACTION_STOP_SPEAKING -> {
                    textToSpeech?.stop()
                    stopBrianPlayback()
                }
            }
        }
    }
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var brianTrack: AudioTrack? = null
    private val audioPermissionRequest = 4102
    private val speechRequest = 4103

    private var baseUrl = ""
    private var token = ""
    private var conversationId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseUrl = prefs.getString("base_url", "https://arthur.tail25244c.ts.net") ?: "https://arthur.tail25244c.ts.net"
        token = prefs.getString("token", "") ?: ""
        buildUi()
        textToSpeech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val languageResult = textToSpeech?.setLanguage(Locale("pt", "BR"))
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA && languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        conversationId = prefs.getString("conversation_id", "") ?: ""
        if (baseUrl.isNotBlank() && token.isNotBlank()) {
            setStatus("Conectando ao núcleo J.A.R.V.I.S.…")
            loadConversations()
        } else {
            setStatus("PRIMEIRA CONEXÃO PENDENTE • MENU ⋮")
            addBubble("Sistema", "Para conectar pela primeira vez, abra o menu ⋮ e informe o PIN do JARVIS Viewer. Depois disso, a conexão será automática.", false)
        }
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(4, 10, 18)
        window.navigationBarColor = Color.rgb(4, 10, 18)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(Color.rgb(4, 10, 18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 21f
            letterSpacing = 0.18f
            setTextColor(cyan)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "PERSONAL AI SYSTEM"
            textSize = 8f
            letterSpacing = 0.22f
            setTextColor(muted)
        }
        brand.addView(title)
        brand.addView(subtitle)
        header.addView(brand, LinearLayout.LayoutParams(0, dp(48), 1f))
        conversationButton = makeButton("☷", false).apply { contentDescription = "Conversas"; setOnClickListener { showConversationPicker() } }
        header.addView(conversationButton, LinearLayout.LayoutParams(dp(44), dp(42)).apply { marginEnd = dp(6) })
        val newButton = makeButton("+", false).apply { textSize = 20f; contentDescription = "Nova conversa"; setOnClickListener { newConversation() } }
        header.addView(newButton, LinearLayout.LayoutParams(dp(44), dp(42)).apply { marginEnd = dp(6) })
        val settings = makeButton("⋮", false).apply { textSize = 20f; contentDescription = "Configurações"; setOnClickListener { showConnectionDialog() } }
        header.addView(settings, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(header)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = outlined(Color.rgb(8, 22, 36), Color.rgb(20, 74, 99), 24)
        }
        hero.addView(ArcReactorView(this), LinearLayout.LayoutParams(dp(116), dp(116)).apply { bottomMargin = dp(4) })
        val systemLabel = TextView(this).apply {
            text = "SISTEMA J.A.R.V.I.S."
            textSize = 10f
            letterSpacing = 0.2f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan)
            gravity = Gravity.CENTER
        }
        hero.addView(systemLabel)
        status = TextView(this).apply {
            text = "AGUARDANDO CONEXÃO"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(9))
        }
        hero.addView(status)
        wakeButton = makeButton("◉   ATIVAR HEY JARVIS", true).apply {
            setOnClickListener { toggleWakeWord() }
        }
        hero.addView(wakeButton, LinearLayout.LayoutParams(-1, dp(42)))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val chatHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chatTitle = TextView(this).apply {
            text = "CONVERSA"
            textSize = 10f
            letterSpacing = 0.18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(muted)
        }
        chatHeading.addView(chatTitle, LinearLayout.LayoutParams(0, dp(28), 1f))
        val live = TextView(this).apply {
            text = "●  LIVE"
            textSize = 9f
            letterSpacing = 0.1f
            setTextColor(Color.rgb(68, 205, 151))
        }
        chatHeading.addView(live)
        root.addView(chatHeading)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(2), 0, dp(6))
        }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = outlined(Color.rgb(8, 20, 32), Color.rgb(22, 62, 82), 20)
        }
        input = EditText(this).apply {
            hint = "Fale com o JARVIS..."
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 15f
            maxLines = 4
            setPadding(dp(9), dp(10), dp(8), dp(10))
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        micButton = makeButton("🎙", true).apply {
            textSize = 21f
            contentDescription = "Falar pelo microfone"
            background = rounded(cyan, 16)
            setTextColor(Color.rgb(3, 16, 25))
            elevation = dp(4).toFloat()
            setOnClickListener { startVoiceInput() }
        }
        composer.addView(micButton, LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginEnd = dp(7) })
        sendButton = makeButton("➤", true).apply { textSize = 18f; contentDescription = "Enviar mensagem"; setOnClickListener { sendMessage() } }
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(composer)
        setContentView(root)
        showWelcome()
    }

    private fun showWelcome() {
        addBubble("J.A.R.V.I.S.", "Olá. Estou pronto para me conectar ao núcleo central.", false)
    }

    private fun makeButton(label: String, primary: Boolean): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(if (primary) Color.rgb(3, 16, 25) else cyan)
        background = if (primary) rounded(cyan, 14) else outlined(panel, Color.rgb(27, 78, 101), 14)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun outlined(color: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private inner class ArcReactorView(context: Context) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.43f
            fillPaint.color = Color.rgb(5, 28, 45)
            canvas.drawCircle(cx, cy, radius * 1.12f, fillPaint)

            ringPaint.color = Color.argb(38, 73, 218, 255)
            ringPaint.strokeWidth = dp(1).toFloat()
            canvas.drawCircle(cx, cy, radius * 1.08f, ringPaint)
            ringPaint.color = Color.rgb(23, 105, 139)
            ringPaint.strokeWidth = dp(2).toFloat()
            canvas.drawCircle(cx, cy, radius * 0.92f, ringPaint)

            bounds.set(cx - radius * 0.78f, cy - radius * 0.78f, cx + radius * 0.78f, cy + radius * 0.78f)
            ringPaint.color = cyan
            ringPaint.strokeWidth = dp(3).toFloat()
            canvas.drawArc(bounds, -218f, 285f, false, ringPaint)

            ringPaint.color = Color.rgb(35, 133, 166)
            ringPaint.strokeWidth = dp(2).toFloat()
            bounds.inset(radius * 0.17f, radius * 0.17f)
            canvas.drawArc(bounds, 28f, 300f, false, ringPaint)

            ringPaint.color = Color.argb(150, 73, 218, 255)
            ringPaint.strokeWidth = dp(1).toFloat()
            for (i in 0 until 12) {
                val angle = Math.toRadians((i * 30.0) - 90.0)
                val inner = radius * 0.62f
                val outer = radius * 0.72f
                canvas.drawLine(
                    cx + kotlin.math.cos(angle).toFloat() * inner,
                    cy + kotlin.math.sin(angle).toFloat() * inner,
                    cx + kotlin.math.cos(angle).toFloat() * outer,
                    cy + kotlin.math.sin(angle).toFloat() * outer,
                    ringPaint
                )
            }

            fillPaint.color = Color.rgb(73, 218, 255)
            canvas.drawCircle(cx, cy, radius * 0.25f, fillPaint)
            fillPaint.color = Color.rgb(190, 247, 255)
            canvas.drawCircle(cx, cy, radius * 0.12f, fillPaint)
        }
    }

    private fun addBubble(author: String, text: String, mine: Boolean) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(5), 0, dp(5))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (mine) outlined(Color.rgb(10, 43, 59), Color.rgb(29, 111, 143), 16) else outlined(Color.rgb(9, 22, 35), Color.rgb(25, 57, 75), 16)
        }
        val label = TextView(this).apply {
            this.text = author.uppercase()
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(cyan)
        }
        val body = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(white)
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(label)
        card.addView(body)
        wrap.addView(card, LinearLayout.LayoutParams(-2, -2).apply { width = (resources.displayMetrics.widthPixels * 0.82f).toInt() })
        messages.addView(wrap)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun friendlyError(error: Exception): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is java.net.UnknownHostException) return "Tailscale não encontrado. Ative o Tailscale no celular e entre na mesma conta do PC."
            if (cause is java.net.ConnectException) return "Não foi possível alcançar o PC. Verifique se ele está ligado e com o JARVIS aberto."
            if (cause is java.net.SocketTimeoutException) return "Tempo esgotado. Verifique a conexão Tailscale e tente novamente."
            cause = cause.cause
        }
        return error.message ?: "Falha na conexão com o núcleo J.A.R.V.I.S."
    }

    private fun setStatus(value: String, good: Boolean = false) {
        runOnUiThread {
            status.text = value.uppercase()
            status.setTextColor(if (good) cyan else muted)
        }
    }

    private fun showConnectionDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val host = EditText(this).apply {
            hint = "https://arthur.tail25244c.ts.net"
            setSingleLine(true)
            setText(baseUrl)
        }
        val pin = EditText(this).apply {
            hint = "PIN de 6 dígitos do JARVIS Viewer"
            inputType = 2
            setSingleLine(true)
        }
        layout.addView(TextView(this).apply { text = "Endereço HTTPS do PC"; setTextColor(white) })
        layout.addView(host)
        layout.addView(TextView(this).apply { text = "PIN de acesso"; setTextColor(white); setPadding(0, dp(12), 0, 0) })
        layout.addView(pin)
        AlertDialog.Builder(this)
            .setTitle("Conectar ao J.A.R.V.I.S.")
            .setMessage("O PC precisa estar ligado, com o JARVIS aberto e o acesso remoto privado configurado.")
            .setView(layout)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Conectar") { _, _ ->
                val enteredHost = host.text.toString().trim().trimEnd('/')
                val enteredPin = pin.text.toString().trim()
                if (!enteredHost.startsWith("https://") || enteredPin.length != 6) {
                    setStatus("Informe um endereço HTTPS e um PIN válido.")
                } else {
                    baseUrl = enteredHost
                    authenticate(enteredPin)
                }
            }
            .show()
    }

    private fun authenticate(pin: String) {
        setStatus("Autenticando conexão…")
        executor.execute {
            try {
                val response = request("POST", "/api/auth", null, JSONObject().put("pin", pin), auth = false)
                token = response.getString("token")
                prefs.edit().putString("base_url", baseUrl).putString("token", token).apply()
                runOnUiThread { messages.removeAllViews() }
                setStatus("Conectado ao núcleo central", true)
                loadConversations()
            } catch (error: Exception) {
                token = ""
                prefs.edit().remove("token").apply()
                setStatus(friendlyError(error))
            }
        }
    }

    private fun loadConversations() {
        executor.execute {
            try {
                val response = request("GET", "/api/conversations", null)
                val list = response.optJSONArray("items") ?: response.optJSONArray("conversations")
                val firstId = if (list != null && list.length() > 0) {
                    list.getJSONObject(0).optString("id")
                } else ""
                if (conversationId.isBlank()) conversationId = firstId
                if (conversationId.isBlank()) {
                    val created = request("POST", "/api/conversations", null, JSONObject())
                    conversationId = created.optString("id")
                }
                prefs.edit().putString("conversation_id", conversationId).apply()
                loadMessages()
                coreConnected = true
                setStatus("Núcleo conectado", true)
            } catch (error: Exception) {
                coreConnected = false
                setStatus(if (token.isBlank()) "Sessão expirada. Reconecte pelo menu ⋮." else friendlyError(error))
            }
        }
    }

    private fun checkCoreConnection() {
        if (token.isBlank() || connectionCheckRunning) return
        connectionCheckRunning = true
        executor.execute {
            try {
                request("GET", "/api/health", null)
                if (!coreConnected) {
                    coreConnected = true
                    setStatus("Conexão com o núcleo restabelecida", true)
                    loadConversations()
                }
            } catch (error: Exception) {
                if (coreConnected) setStatus("Conexão com o PC perdida. Tentando reconectar…")
                coreConnected = false
                if (token.isBlank()) setStatus("Sessão expirada. Reconecte pelo menu ⋮.")
            } finally {
                connectionCheckRunning = false
            }
        }
    }

    private fun showConversationPicker() {
        if (token.isBlank()) {
            showConnectionDialog()
            return
        }
        setStatus("Carregando conversas…")
        executor.execute {
            try {
                val items = request("GET", "/api/conversations", null).optJSONArray("items") ?: JSONArray()
                runOnUiThread {
                    val drawer = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), dp(24), dp(14), dp(16))
                        setBackgroundColor(Color.rgb(7, 18, 31))
                    }
                    val heading = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val headingText = TextView(this).apply {
                        text = "CONVERSAS"
                        textSize = 15f
                        letterSpacing = 0.14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(cyan)
                    }
                    heading.addView(headingText, LinearLayout.LayoutParams(0, dp(44), 1f))
                    val close = TextView(this).apply {
                        text = "✕"
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setTextColor(muted)
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                    }
                    heading.addView(close)
                    drawer.addView(heading)
                    val divider = View(this).apply { setBackgroundColor(Color.rgb(25, 73, 96)) }
                    drawer.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(12) })
                    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    if (items.length() == 0) {
                        list.addView(TextView(this).apply {
                            text = "Nenhuma conversa encontrada."
                            textSize = 14f
                            setTextColor(muted)
                            setPadding(dp(8), dp(16), dp(8), dp(16))
                        })
                    }
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        val id = item.optString("id")
                        val label = item.optString("title", "Conversa ${index + 1}").ifBlank { "Conversa ${index + 1}" }
                        val selected = id == conversationId
                        val row = TextView(this).apply {
                            text = (if (selected) "●  " else "◦  ") + label
                            textSize = 14f
                            maxLines = 2
                            setTextColor(if (selected) cyan else white)
                            setPadding(dp(12), dp(14), dp(10), dp(14))
                            background = if (selected) rounded(Color.rgb(13, 48, 67), 12) else rounded(Color.TRANSPARENT, 12)
                            setOnClickListener {
                                conversationId = id
                                prefs.edit().putString("conversation_id", conversationId).apply()
                                messages.removeAllViews()
                                loadMessages()
                                setStatus("Conversa aberta", true)
                                closeConversationDrawer()
                            }
                        }
                        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
                    }
                    val listScroll = ScrollView(this).apply { isFillViewport = false; addView(list) }
                    drawer.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
                    val popup = PopupWindow(drawer, minOf(dp(330), (resources.displayMetrics.widthPixels * 0.86f).toInt()), -1, true).apply {
                        setBackgroundDrawable(ColorDrawable(Color.rgb(7, 18, 31)))
                        isOutsideTouchable = true
                        elevation = dp(18).toFloat()
                    }
                    drawerPopup = popup
                    drawerPanel = drawer
                    close.setOnClickListener { closeConversationDrawer() }
                    popup.setOnDismissListener {
                        if (drawerPopup === popup) {
                            drawerPopup = null
                            drawerPanel = null
                        }
                    }
                    popup.showAtLocation(root, Gravity.START or Gravity.TOP, 0, 0)
                    drawer.post {
                        drawer.translationX = -drawer.width.toFloat()
                        drawer.alpha = 0.72f
                        drawer.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(260L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    setStatus("Núcleo conectado", true)
                }
            } catch (error: Exception) {
                setStatus(friendlyError(error))
            }
        }
    }

    private fun closeConversationDrawer() {
        val popup = drawerPopup ?: return
        val panel = drawerPanel
        if (panel == null || !popup.isShowing) {
            popup.dismiss()
            return
        }
        panel.animate().cancel()
        panel.animate()
            .translationX(-panel.width.toFloat())
            .alpha(0.72f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (drawerPopup === popup) popup.dismiss() }
            .start()
    }

    private fun newConversation() {
        executor.execute {
            try {
                val created = request("POST", "/api/conversations", null, JSONObject())
                conversationId = created.optString("id")
                prefs.edit().putString("conversation_id", conversationId).apply()
                runOnUiThread {
                    messages.removeAllViews()
                    addBubble("J.A.R.V.I.S.", "Nova conversa iniciada.", false)
                    setStatus("Nova conversa pronta", true)
                }
            } catch (error: Exception) {
                setStatus(friendlyError(error))
            }
        }
    }

    private fun loadMessages() {
        val id = conversationId
        if (id.isBlank()) return
        executor.execute {
            try {
                val path = "/api/messages?conversation_id=" + URLEncoder.encode(id, "UTF-8")
                val response = request("GET", path, null)
                runOnUiThread {
                    messages.removeAllViews()
                    val rows = response.optJSONArray("items") ?: JSONArray()
                    for (index in 0 until rows.length()) {
                        val item = rows.getJSONObject(index)
                        val author = item.optString("author", "assistant")
                        addBubble(if (author == "user") "Você" else "J.A.R.V.I.S.", item.optString("message"), author == "user")
                    }
                }
            } catch (error: Exception) {
                setStatus(friendlyError(error))
            }
        }
    }

    private fun normalizeCommand(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(Locale("pt", "BR")), java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .trim()
            .replace("\\s+".toRegex(), " ")

    private fun getLaunchableApps(): List<Pair<String, String>> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                val packageName = info.activityInfo?.packageName.orEmpty()
                if (label.isBlank() || packageName.isBlank()) null else label to packageName
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale("pt", "BR")) }
    }

    private fun handleLocalAppCommand(message: String): Boolean {
        val command = normalizeCommand(message)
        val listRequests = listOf("listar aplicativos", "listar apps", "quais aplicativos", "quais apps", "apps instalados", "aplicativos instalados")
        if (listRequests.any { command.contains(it) }) {
            val apps = getLaunchableApps()
            val visible = apps.take(35).joinToString(", ") { it.first }
            val suffix = if (apps.size > 35) "\\nE mais ${apps.size - 35} aplicativos." else ""
            addBubble("J.A.R.V.I.S.", if (apps.isEmpty()) "Não encontrei aplicativos disponíveis para abrir." else "Aplicativos disponíveis (${apps.size}):\\n$visible$suffix", false)
            setStatus("Lista de aplicativos atualizada", true)
            return true
        }

        val closePrefixes = listOf("feche ", "fechar ", "encerre ", "encerrar ", "sair do ", "saia do ")
        val homeRequests = listOf("volte para a tela inicial", "ir para tela inicial", "voltar para tela inicial", "volte ao inicio", "voltar ao inicio")
        if (closePrefixes.any { command.startsWith(it) } || homeRequests.any { command.contains(it) }) {
            addBubble("J.A.R.V.I.S.", "Voltando à tela inicial. O Android não permite que um app comum force a parada de outro aplicativo; ele continuará em segundo plano.", false)
            setStatus("Retornando à tela inicial", true)
            window.decorView.post {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(home)
                } catch (_: Exception) {
                    setStatus("Não foi possível abrir a tela inicial.")
                }
            }
            return true
        }

        val openPrefixes = listOf("abra ", "abrir ", "abre ", "inicie ", "iniciar ", "execute ", "executar ", "open ", "launch ")
        val prefix = openPrefixes.firstOrNull { command.startsWith(it) } ?: return false
        var target = command.removePrefix(prefix).trim()
        target = target.removePrefix("o ").removePrefix("a ").removePrefix("app ").removePrefix("aplicativo ").trim()
        if (target.isBlank()) return false

        val apps = getLaunchableApps()
        val match = apps.firstOrNull { normalizeCommand(it.first) == target }
            ?: apps.firstOrNull {
                val label = normalizeCommand(it.first)
                label.contains(target) || target.contains(label)
            }
        if (match == null) {
            addBubble("J.A.R.V.I.S.", "Não encontrei um aplicativo chamado ‘$target’. Você pode dizer ‘listar aplicativos’ para ver os nomes disponíveis.", false)
            setStatus("Aplicativo não encontrado")
            return true
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(match.second)
        if (launchIntent == null) {
            addBubble("J.A.R.V.I.S.", "Encontrei ${match.first}, mas o Android não disponibilizou uma ação de abertura para ele.", false)
            setStatus("Não foi possível abrir o aplicativo")
            return true
        }
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            addBubble("J.A.R.V.I.S.", "Abrindo ${match.first}.", false)
            setStatus("Aplicativo aberto: ${match.first}", true)
        } catch (error: Exception) {
            addBubble("Sistema", "Falha ao abrir ${match.first}: ${error.message ?: "erro desconhecido"}", false)
            setStatus("Falha ao abrir aplicativo")
        }
        return true
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        if (handleLocalAppCommand(message)) {
            input.setText("")
            return
        }
        if (token.isBlank() || conversationId.isBlank()) {
            showConnectionDialog()
            return
        }
        input.setText("")
        addBubble("Você", message, true)
        setStatus("J.A.R.V.I.S. está pensando…")
        sendButton.isEnabled = false
        executor.execute {
            try {
                val body = JSONObject().put("message", message).put("conversation_id", conversationId)
                val response = request("POST", "/api/chat", null, body)
                val answer = response.optString("answer", "Não recebi uma resposta textual.")
                runOnUiThread {
                    addBubble("J.A.R.V.I.S.", answer, false)
                    setStatus("Gerando voz Brian…", true)
                }
                try {
                    val voiceResponse = request("POST", "/api/tts", null, JSONObject().put("text", answer))
                    val encoded = voiceResponse.optString("audio_base64", "")
                    if (encoded.isBlank()) throw IllegalStateException("O núcleo não retornou áudio Brian.")
                    val pcm = Base64.decode(encoded, Base64.DEFAULT)
                    playBrianPcm(pcm, voiceResponse.optInt("sample_rate", 24000))
                    setStatus("Resposta recebida • voz Brian", true)
                } catch (voiceError: Exception) {
                    setStatus("Resposta recebida • voz Brian indisponível")
                    runOnUiThread {
                        addBubble("Sistema", "Não consegui gerar a voz Brian: ${voiceError.message ?: "falha de áudio"}. A chave permanece protegida no PC; não troquei para outra voz.", false)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { addBubble("Sistema", error.message ?: "Falha ao enviar mensagem.", false) }
                setStatus("Falha na comunicação")
            } finally {
                runOnUiThread { sendButton.isEnabled = true }
            }
        }
    }

    private fun playBrianPcm(bytes: ByteArray, sampleRate: Int) {
        if (bytes.isEmpty() || sampleRate <= 0) throw IllegalArgumentException("Áudio PCM inválido.")
        stopBrianPlayback()
        val samples = ShortArray(bytes.size / 2)
        for (i in samples.indices) {
            val lo = bytes[i * 2].toInt() and 0xff
            val hi = bytes[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("Saída de áudio indisponível.")
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, 8192))
            .build()
        brianTrack = track
        Thread({
            try {
                track.play()
                var offset = 0
                while (offset < samples.size && brianTrack === track) {
                    val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) throw IllegalStateException("Falha ao reproduzir áudio Brian: $written")
                    offset += written
                }
                while (brianTrack === track && track.playbackHeadPosition < samples.size) {
                    Thread.sleep(20)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                runOnUiThread { setStatus("Falha na reprodução da voz Brian") }
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
                if (brianTrack === track) brianTrack = null
            }
        }, "Jarvis-Brian-Playback").start()
    }

    private fun stopBrianPlayback() {
        val track = brianTrack
        brianTrack = null
        if (track != null) {
            try { track.pause() } catch (_: Exception) {}
            try { track.flush() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun toggleWakeWord() {
        if (prefs.getBoolean("wake_enabled", false)) {
            prefs.edit().putBoolean("wake_enabled", false).apply()
            startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
            updateWakeButton()
            setStatus("Ativação Hey JARVIS desligada")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingWakeStart = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
            return
        }
        requestNotificationPermissionOrStartWakeWord()
    }

    private fun requestNotificationPermissionOrStartWakeWord() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequest)
            return
        }
        prefs.edit().putBoolean("wake_enabled", true).apply()
        val serviceIntent = Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        updateWakeButton()
        setStatus("Iniciando ativação local Hey JARVIS…")
    }

    private fun updateWakeButton() {
        if (!::wakeButton.isInitialized) return
        val enabled = prefs.getBoolean("wake_enabled", false)
        wakeButton.text = if (enabled) "◉   HEY JARVIS ATIVO" else "◉   ATIVAR HEY JARVIS"
        wakeButton.setTextColor(if (enabled) Color.rgb(3, 16, 25) else cyan)
        wakeButton.background = if (enabled) rounded(cyan, 14) else outlined(panel, Color.rgb(27, 78, 101), 14)
    }

    override fun onStart() {
        super.onStart()
        if (!wakeReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WakeWordService.ACTION_STATE)
                addAction(WakeWordService.ACTION_WAKE)
                addAction(WakeWordService.ACTION_COMMAND)
                addAction(WakeWordService.ACTION_STOP_SPEAKING)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(wakeReceiver, filter)
            wakeReceiverRegistered = true
        }
        updateWakeButton()
        mainHandler.removeCallbacks(connectionMonitor)
        mainHandler.post(connectionMonitor)
        if (prefs.getBoolean("wake_enabled", false)) {
            val serviceIntent = Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        val pending = prefs.getString("pending_voice_command", "").orEmpty()
        if (pending.isNotBlank()) {
            prefs.edit().remove("pending_voice_command").apply()
            input.setText(pending)
            sendMessage()
        }
    }

    override fun onStop() {
        mainHandler.removeCallbacks(connectionMonitor)
        if (wakeReceiverRegistered) {
            try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
            wakeReceiverRegistered = false
        }
        super.onStop()
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioPermissionRequest)
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com o J.A.R.V.I.S.")
            }
            startActivityForResult(intent, speechRequest)
        } catch (_: Exception) {
            setStatus("Reconhecimento de voz indisponível neste celular.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (pendingWakeStart) {
                pendingWakeStart = false
                requestNotificationPermissionOrStartWakeWord()
            } else {
                startVoiceInput()
            }
        } else if (requestCode == audioPermissionRequest) {
            pendingWakeStart = false
            setStatus("Permissão do microfone negada.")
        } else if (requestCode == notificationPermissionRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionOrStartWakeWord()
            } else {
                setStatus("Permita notificações para manter o indicador da escuta contínua.")
            }
        }
    }

    @Deprecated("Deprecated in Android API; retained for speech recognizer compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechRequest && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                input.setText(spoken)
                sendMessage()
            }
        }
    }

    private fun request(
        method: String,
        path: String,
        unused: String? = null,
        body: JSONObject? = null,
        auth: Boolean = true
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection)
        connection.requestMethod = method
        connection.connectTimeout = 12000
        connection.readTimeout = if (path == "/api/chat") 190000 else 20000
        connection.setRequestProperty("Accept", "application/json")
        if (auth && token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val result = JSONObject(text)
            if (code == 401 && auth) {
                token = ""
                prefs.edit().remove("token").apply()
            }
            if (code !in 200..299) throw IllegalStateException(result.optString("error", "HTTP $code"))
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        textToSpeech?.stop()
        stopBrianPlayback()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}
