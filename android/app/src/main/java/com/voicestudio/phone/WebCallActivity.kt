package com.voicestudio.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Foreground browser. The existing live engine owns the mic; pages only receive converted PCM after a tap. */
class WebCallActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("phone", MODE_PRIVATE) }
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var desktopButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var controlsPanel: LinearLayout
    private lateinit var stateChip: TextView
    private lateinit var fold: Button
    private var desktop = true
    private var foreground = false
    private var destroyed = false
    private var supported = false
    private var ready = false
    private var audioState = ""
    private var pageOrigin: String? = null
    private var reply: JavaScriptReplyProxy? = null
    private var engine: LiveEngine? = null
    private var token: String? = null
    private var started = false
    private var waiting = false
    private var sentAt = 0L
    private var sequence = 0
    private var senders = 0
    private var queuedMs = 0
    private var message = str(R.string.ui_start_live_conversion_then_sign_in_to)
    private var fileResult: ValueCallback<Array<Uri>>? = null
    private val popups = mutableSetOf<WebView>()

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun row(parent: LinearLayout) = LinearLayout(this).also { parent.addView(it) }
    private fun button(row: LinearLayout, label: String, action: () -> Unit) = Button(this, null, 0, R.style.SoftButton).apply {
        text = label; textSize = 12f; minWidth = 0; minimumWidth = 0
        gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
        setOnClickListener { action() }; row.addView(this)
    }

    private fun clearLogin() {
        AlertDialog.Builder(this).setMessage(str(R.string.ui_clear_all_website_sign_ins_and_stored))
            .setNegativeButton(str(R.string.ui_cancel), null).setPositiveButton(str(R.string.ui_delete)) { _, _ ->
                if (destroyed) return@setPositiveButton
                disconnect(); web.stopLoading(); web.loadUrl("about:blank")
                CookieManager.getInstance().removeAllCookies {
                    if (!destroyed) { CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData()
                        web.clearCache(true); web.clearHistory(); message = str(R.string.ui_website_data_cleared_enter_an_address); showStatus() }
                }
            }.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { navigateBack() }
        }
        desktop = prefs.getBoolean("web_desktop", true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bg))
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        root.requestApplyInsets()
        val header = row(root).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        Button(this, null, 0, R.style.SoftButton).apply {
            text = "‹"; textSize = 28f; gravity = Gravity.CENTER; contentDescription = str(R.string.ui_back_to_conversion_settings)
            setPadding(0,0,0,0); layoutParams = LinearLayout.LayoutParams(dp(44),dp(44))
            setOnClickListener { finish() }; header.addView(this)
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10),0,dp(6),0)
            layoutParams = LinearLayout.LayoutParams(0,-2,1f)
            addView(TextView(this@WebCallActivity).apply {
                text = "voice / lounge"; textSize = 20f; setTextColor(getColor(R.color.accent_strong))
                typeface = Typeface.create("sans-serif",Typeface.BOLD)
            })
            addView(TextView(this@WebCallActivity).apply { text = str(R.string.ui_send_your_voice_to_a_website); textSize = 11f; setTextColor(getColor(R.color.muted)) })
        })
        stateChip = TextView(this).apply {
            textSize = 11f; setPadding(dp(9),dp(5),dp(9),dp(5)); setBackgroundResource(R.drawable.chip)
            setTextColor(getColor(R.color.accent_strong)); header.addView(this)
        }
        fold = Button(this, null, 0, R.style.SoftButton).apply {
            text = "⌃"; textSize = 20f; gravity = Gravity.CENTER; contentDescription = str(R.string.ui_collapse_controls)
            setPadding(0,0,0,0); layoutParams = LinearLayout.LayoutParams(dp(44),dp(44)).apply { marginStart=dp(6) }
            setOnClickListener {
                val opening = controlsPanel.visibility != View.VISIBLE
                controlsPanel.visibility = if (opening) View.VISIBLE else View.GONE
                text = if (opening) "⌃" else "⌄"
                contentDescription = if (opening) str(R.string.ui_collapse_controls) else str(R.string.ui_expand_controls)
            }; header.addView(this)
        }
        controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12),0,dp(12),dp(10)); root.addView(this)
        }
        val location = row(controlsPanel).apply { gravity = Gravity.CENTER_VERTICAL }
        address = EditText(this).apply {
            setSingleLine(); hint = str(R.string.ui_https_web_address); textSize = 14f
            setTextColor(getColor(R.color.text)); setHintTextColor(getColor(R.color.muted))
            setPadding(dp(12),0,dp(12),0); setBackgroundResource(R.drawable.card)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        location.addView(address)
        Button(this,null,0,R.style.SoftButton).apply {
            text = "→"; contentDescription = str(R.string.ui_open_address); textSize = 20f; gravity = Gravity.CENTER; setPadding(0,0,0,0)
            layoutParams = LinearLayout.LayoutParams(dp(46),dp(46)).apply { marginStart=dp(6) }
            setOnClickListener { navigate(address.text.toString()) }; location.addView(this)
        }
        val options = row(controlsPanel).apply { setPadding(0,dp(8),0,dp(8)) }
        button(options, str(R.string.ui_choose_site)) {
            val names = arrayOf("Discord", "Google Meet", "Instagram", "Messenger")
            val urls = arrayOf("https://discord.com/app", "https://meet.google.com/", "https://www.instagram.com/", "https://www.messenger.com/")
            AlertDialog.Builder(this).setTitle(str(R.string.ui_open_website)).setItems(names) { _, i -> navigate(urls[i]) }.show()
        }
        desktopButton = button(options, str(R.string.ui_desktop_view)) {
            if (!destroyed) { desktop = !desktop; prefs.edit().putBoolean("web_desktop", desktop).apply()
                disconnect(); setDesktop(); web.reload() }
        }
        button(options, str(R.string.ui_more)) {
            AlertDialog.Builder(this).setTitle(str(R.string.ui_browser_menu))
                .setItems(arrayOf(str(R.string.ui_back_2), str(R.string.ui_forward), str(R.string.ui_reload), str(R.string.ui_clear_sign_ins))) { _, i ->
                    if (!destroyed) when(i) {
                        0 -> if(web.canGoBack()) web.goBack()
                        1 -> if(web.canGoForward()) web.goForward()
                        2 -> { disconnect(); web.reload() }
                        3 -> clearLogin()
                    }
                }.show()
        }
        val voiceCard = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10))
            setBackgroundResource(R.drawable.card); controlsPanel.addView(this)
        }
        status = TextView(this).apply {
            textSize=12f; setTextColor(getColor(R.color.muted)); setLineSpacing(dp(2).toFloat(),1f)
            setPadding(0,0,0,dp(8)); voiceCard.addView(this)
        }
        connect = Button(this,null,0,R.style.SoftButton).apply {
            text=str(R.string.ui_connect_voice_to_this_site); textSize=14f; gravity=Gravity.CENTER; setPadding(dp(12),0,dp(12),0); setTypeface(typeface,Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE); setBackgroundResource(R.drawable.btn_primary)
            layoutParams=LinearLayout.LayoutParams(-1,dp(48)); setOnClickListener { toggleConnection() }
            voiceCard.addView(this)
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        setDesktop()
        web.webChromeClient = object : WebChromeClient() {
            // A JS hook alone is not a permission boundary. Never let a page or iframe capture the real mic.
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onProgressChanged(view: WebView, value: Int) {
                progress.progress = value; progress.visibility = if (value < 100) View.VISIBLE else View.GONE
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean {
                if (!gesture) return false
                val popup = WebView(this@WebCallActivity)
                popups.add(popup)
                fun closePopup() { if (popups.remove(popup)) popup.destroy() }
                ui.postDelayed({ closePopup() }, 10000)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        navigate(request.url.toString()); closePopup(); return true
                    }
                }
                (result.obj as WebView.WebViewTransport).webView = popup; result.sendToTarget(); return true
            }
            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileResult?.onReceiveValue(null); fileResult = callback
                return try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }, PICK_FILE); true } catch (_: Exception) { fileResult = null; false }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (WebAddress.parse(request.url.toString()) != null) return false
                if (request.isForMainFrame) { message = str(R.string.ui_use_an_https_web_address_app_only); showStatus() }
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                disconnect(); ready = false; reply = null; audioState = ""; senders = 0
                pageOrigin = WebAddress.origin(url); address.setText(url)
                message = str(R.string.ui_loading_page); showStatus()
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (WebAddress.parse(url) != null) prefs.edit().putString("web_url", url).apply()
                if (token == null) message = if (ready) str(R.string.ui_sign_in_to_the_site_then_connect) else str(R.string.ui_browsing_available_call_connection_is_not_ready)
                showStatus()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { disconnect(); message = str(R.string.ui_could_not_open_page, error.description); showStatus() }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                disconnect(); (view.parent as? ViewGroup)?.removeView(view); view.destroy(); destroyed = true
                message = str(R.string.ui_the_web_page_closed_return_to_conversion); showStatus()
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            supported = true
            WebViewCompat.addWebMessageListener(web, "VoiceStudioBridge", setOf("*")) { _, m, origin, main, proxy ->
                if (main && WebAddress.origin(origin.toString()) == pageOrigin && pageOrigin != null && !destroyed) {
                    if ((m.data?.length ?: 0) <= 8192) runCatching { receive(JSONObject(m.data ?: "{}"), proxy) }
                }
            }
            val config = "{mode:'rvc', desktop:!navigator.userAgent.includes('Mobile'), hosts:[location.hostname], strictTracks:false, prepareAudioLabel:" +
                JSONObject.quote(str(R.string.prepare_call_audio)) + "}"
            val script = assets.open("WebCallBridge.js").bufferedReader().use { it.readText() }
                .replace("__VS_WEB_CALL_CONFIG__", config)
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
        }
        navigate(savedInstanceState?.getString("url") ?: prefs.getString("web_url", "https://discord.com/app")!!)
    }

    private fun setDesktop() {
        val mobile = WebSettings.getDefaultUserAgent(this)
        web.settings.userAgentString = if (desktop) WebAddress.desktopAgent(mobile) else mobile
        desktopButton.text = if (desktop) str(R.string.ui_desktop_view_2) else str(R.string.ui_mobile_view)
    }

    private fun navigate(text: String) {
        val url = WebAddress.parse(text)
        if (url == null || destroyed) { message = str(R.string.ui_enter_a_web_address_starting_with_https); showStatus(); return }
        disconnect(); address.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
        web.loadUrl(url.toASCIIString())
    }

    private fun receive(m: JSONObject, proxy: JavaScriptReplyProxy) {
        when (m.optString("event")) {
            "ready" -> { reply = proxy; ready = true }
            "audio_state" -> { audioState = m.optString("state"); if (audioState != "running" && token != null) disconnect() }
            "stats" -> { senders = m.optInt("activeSenders"); queuedMs = m.optInt("pcmQueuedMs") }
            "pcm_started" -> if (m.optString("token") == token) { started = m.optBoolean("ok"); waiting = false }
            "pcm_ack" -> if (m.optString("token") == token && m.optInt("sequence") == sequence) {
                waiting = false; if (!m.optBoolean("ok")) { disconnect(); message = str(R.string.ui_web_audio_stopped_reconnect_to_continue) }
            }
            "bridge_error", "pcm_error", "stopped" -> { disconnect(); ready = false; message = str(R.string.ui_could_not_prepare_web_calling_reload_and) }
        }
        showStatus()
    }

    private fun toggleConnection() {
        if (token != null) { disconnect(); message = str(R.string.ui_voice_disconnected_the_site_receives_silence); showStatus(); return }
        val live = LiveSession.engine
        when {
            !supported -> message = str(R.string.ui_update_android_system_webview_to_enable_web)
            live?.canFeedWeb != true -> message = str(R.string.ui_start_live_conversion_in_conversion_settings_bypass)
            !ready || reply == null || pageOrigin == null -> message = str(R.string.ui_this_page_is_not_ready_for_a)
            audioState != "running" -> message = str(R.string.ui_tap_prepare_call_audio_on_the_web)
            foreground -> {
                engine = live; live.webAudio.enable(); live.webOutput = true; queuedMs = 0
                token = UUID.randomUUID().toString(); started = false; waiting = true; sentAt = SystemClock.elapsedRealtime()
                send(JSONObject().put("command", "begin").put("token", token))
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        showStatus()
    }

    private fun send(m: JSONObject) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) reply?.postMessage(m.toString())
        } catch (_: Exception) { disconnect(false); message = str(R.string.ui_web_connection_lost_reconnect_to_continue) }
    }

    private fun disconnect(notify: Boolean = true) {
        val old = token; token = null; started = false; waiting = false
        engine?.let { it.webAudio.disable(); it.webOutput = false }; engine = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (notify && old != null) runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                reply?.postMessage(JSONObject().put("command", "end").put("token", old).toString())
        }
    }

    private val pump = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            if (token != null) {
                val live = engine
                if (live?.canFeedWeb != true || live !== LiveSession.engine) {
                    disconnect(); message = str(R.string.ui_live_conversion_stopped_the_web_input_is)
                } else if (waiting && SystemClock.elapsedRealtime() - sentAt > 2000) {
                    disconnect(); message = str(R.string.ui_web_audio_did_not_respond_in_time)
                } else if (started && !waiting) {
                    val samples = live.webAudio.take()
                    if (samples.isNotEmpty()) {
                        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        samples.forEach { bytes.putFloat(it) }
                        sequence++; waiting = true; sentAt = SystemClock.elapsedRealtime()
                        send(JSONObject().put("command", "pcm").put("token", token).put("sequence", sequence)
                            .put("data", Base64.encodeToString(bytes.array(), Base64.NO_WRAP)))
                    }
                }
            }
            showStatus(); ui.postDelayed(this, 50)
        }
    }

    private fun showStatus() {
        connect.text = if (token != null) str(R.string.ui_disconnect_voice) else str(R.string.ui_connect_voice_to_this_site)
        connect.isEnabled = !destroyed
        stateChip.text = if (token != null && senders > 0) str(R.string.ui_sending_voice) else if (token != null) str(R.string.ui_voice_connected) else str(R.string.ui_web_calls)
        stateChip.setTextColor(getColor(if (token != null) R.color.green else R.color.accent_strong))
        status.text = when {
            !supported -> str(R.string.ui_browsing_available_update_android_system_webview_for)
            token != null -> str(R.string.ui_voice_connected_use_headphones_leaving_this_screen, pageOrigin?.removePrefix("https://"), if (senders > 0) str(R.string.ui_sending_call_audio) else str(R.string.ui_join_a_call_on_the_site))
            else -> message
        }
    }

    override fun onResume() { super.onResume(); foreground = true; if (!destroyed) web.onResume(); ui.post(pump) }
    override fun onPause() {
        foreground = false; ui.removeCallbacks(pump); disconnect()
        message = str(R.string.ui_voice_disconnected_tap_connect_to_resume)
        if (!destroyed) web.onPause()
        CookieManager.getInstance().flush(); super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) { if (!destroyed) outState.putString("url", web.url); super.onSaveInstanceState(outState) }
    @Deprecated("Activity back handling")
    override fun onBackPressed() { navigateBack() }
    private fun navigateBack() { if (!destroyed && web.canGoBack()) web.goBack() else finish() }
    @Deprecated("Activity result API")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == PICK_FILE) { fileResult?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); fileResult = null }
    }
    override fun onDestroy() {
        disconnect(); ui.removeCallbacksAndMessages(null); fileResult?.onReceiveValue(null); fileResult = null
        popups.forEach { it.destroy() }; popups.clear()
        if (!destroyed) { web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        destroyed = true; super.onDestroy()
    }
    companion object { private const val PICK_FILE = 301 }
}
