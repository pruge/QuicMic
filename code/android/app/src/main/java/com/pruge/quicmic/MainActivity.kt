package com.pruge.quicmic

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Native default screen (T04, D7/V2): a bottom-tab shell with a **Home** tab
 * (connection status, QR pairing button, audio-level meter) and a **Settings**
 * tab (server address, manual PIN, TOFU fingerprint management).
 *
 * Once connected, the existing WebView takes over the Home tab's remaining
 * space — the web UI is still the control surface, the app only composes the
 * URL (including `#<pin>` after a scan, which the web page's own pairing flow
 * consumes — no pairing logic is duplicated here).
 *
 * Kept from T02 (regression guards):
 *  - TOFU trust: first-use consent dialog, mismatch block + re-confirm.
 *  - Offline guidance screen when the server is unreachable ("집 밖" case).
 *  - WebView localStorage keeps the session token; the app never touches it.
 */
class MainActivity : Activity() {

    private enum class Conn { DISCOVERING, CONNECTING, CONNECTED, UNPAIRED, OFFLINE, NO_SERVER }

    private lateinit var root: FrameLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var home: HomeScreen
    private lateinit var settings: SettingsScreen
    private lateinit var tabHome: TextView
    private lateinit var tabSettings: TextView
    private lateinit var tofu: TofuStore
    private lateinit var addressPrefs: android.content.SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var serverHost: String? = null
    private var conn = Conn.NO_SERVER
    private var activeTab = 0 // 0 = home, 1 = settings
    private var pageLoaded = false

    /** True while a TOFU dialog is up — suppresses the offline screen that the
     *  cancelled load would otherwise trigger underneath. */
    private var pendingTofu = false

    /** True while the mismatch/blocking screen is shown. */
    private var showingMismatch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tofu = TofuStore(this)
        addressPrefs = getSharedPreferences("server", MODE_PRIVATE)
        buildUi()
        startConnect()
        handler.postDelayed(meterPoll, POLL_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------------
    // Tab shell
    // ---------------------------------------------------------------------

    private fun buildUi() {
        root = FrameLayout(this)
        setContentView(root)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x0a, 0x0a, 0x0f))
        }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        home = HomeScreen(this)
        settings = SettingsScreen(this).apply {
            serverHostProvider = { serverHost }
            onScan = { launchScanner() }
            onEditAddress = { promptForAddress(serverHost) }
            onManualPair = { pin -> pairWithPin(pin) }
            onDeleteFingerprint = { key -> deleteFingerprint(key) }
        }

        contentFrame = FrameLayout(this)
        column.addView(contentFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        contentFrame.addView(home.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(settings.root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(0x11, 0x11, 0x19))
        }
        tabHome = tabLabel("홈")
        tabSettings = tabLabel("설정")
        tabHome.setOnClickListener { selectTab(0) }
        tabSettings.setOnClickListener { selectTab(1) }
        bar.addView(tabHome, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(tabSettings, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        column.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, HomeScreen.dp(this, 56)))

        home.qrButton.setOnClickListener { launchScanner() }
        selectTab(0)
        refreshSettings()
    }

    private fun tabLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14f
    }

    private fun selectTab(index: Int) {
        activeTab = index
        home.root.visibility = if (index == 0) View.VISIBLE else View.GONE
        settings.root.visibility = if (index == 1) View.VISIBLE else View.GONE
        fun style(t: TextView, selected: Boolean) {
            t.setTextColor(getColor(if (selected) R.color.accent else R.color.text_secondary))
            t.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        style(tabHome, index == 0)
        style(tabSettings, index == 1)
        if (index == 1) refreshSettings()
    }

    // ---------------------------------------------------------------------
    // Connection flow
    // ---------------------------------------------------------------------

    private fun startConnect() {
        val saved = addressPrefs.getString(PREF_HOST, null)
        if (saved != null) {
            connectTo(saved)
            return
        }
        setConn(Conn.DISCOVERING)
        Thread {
            val found = runCatching { ServerDiscovery.discover() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    found.size == 1 -> useHost(found[0])
                    found.isEmpty() -> setConn(Conn.NO_SERVER)
                    else -> pickFromCandidates(found)
                }
            }
        }.start()
    }

    private fun pickFromCandidates(candidates: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("QuicMic 서버 선택")
            .setItems(candidates.toTypedArray()) { _, which -> useHost(candidates[which]) }
            .setOnCancelListener { setConn(Conn.NO_SERVER) }
            .show()
    }

    /** Strip any scheme/port/hash a user may have pasted from the QR URL. */
    private fun cleanHost(host: String): String =
        host.removePrefix("https://").removePrefix("http://")
            .substringBefore("/").trim()
            .let { if (it.startsWith("[")) it.substringAfter("[").substringBefore("]") else it.substringBefore(":") }

    private fun useHost(host: String) {
        val clean = cleanHost(host)
        if (clean.isEmpty()) {
            promptForAddress(host)
            return
        }
        serverHost = clean
        addressPrefs.edit().putString(PREF_HOST, clean).apply()
        connectTo(clean)
        refreshSettings()
    }

    /**
     * Load the web UI for [host]. An optional [pin] is appended as the URL
     * hash so the page's own auto-pairing runs (the same path a scanned QR
     * takes in a browser); without it the stored token / PIN screen applies.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun connectTo(host: String, pin: String? = null) {
        setConn(Conn.CONNECTING)
        pageLoaded = false
        val url = "https://$host:${ServerDiscovery.PORT}/" + (pin?.let { "#$it" } ?: "")
        val view = webView ?: createWebView().also { webView = it }
        if (view.parent == null) home.webContainer.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // session token + settings persistence live here
            mediaPlaybackRequiresUserGesture = false // audio must start right after pairing
            allowFileAccess = false
            allowContentAccess = false
        }
        view.isFocusableInTouchMode = true
        view.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                runProbe()
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                val host = serverHost
                val cert = error.certificate?.x509Certificate
                if (host == null || cert == null) {
                    handler.cancel()
                    return
                }
                val fingerprint = tofu.fingerprintOf(cert)
                val stored = tofu.stored(host, ServerDiscovery.PORT)
                when {
                    stored == null -> {
                        // First use: never proceed silently — ask, then reload.
                        // The flag goes up before cancel(): a synchronous
                        // main-frame error callback must not flash the offline
                        // screen underneath the consent dialog.
                        pendingTofu = true
                        handler.cancel()
                        showTofuDialog(fingerprint, isFirstUse = true) { accepted ->
                            if (accepted) {
                                tofu.save(host, ServerDiscovery.PORT, fingerprint)
                                pendingTofu = false
                                view.reload()
                            } else {
                                pendingTofu = false
                                finish()
                            }
                        }
                    }
                    stored == fingerprint -> handler.proceed()
                    else -> {
                        // Certificate changed since first trust: block and require
                        // an explicit re-confirmation of the new fingerprint.
                        showingMismatch = true
                        handler.cancel()
                        showMismatchScreen(stored, fingerprint) { newFp ->
                            tofu.save(host, ServerDiscovery.PORT, newFp)
                            showingMismatch = false
                            view.reload()
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                if (pendingTofu || showingMismatch) return
                showOfflineScreen()
            }
        }
        return view
    }

    // ---------------------------------------------------------------------
    // QR scanning & manual pairing
    // ---------------------------------------------------------------------

    private fun launchScanner() {
        startActivityForResult(Intent(this, ScanActivity::class.java), REQ_SCAN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK) return
        val raw = data?.getStringExtra(ScanActivity.EXTRA_URL) ?: return
        val payload = QrPayload.parse(raw) ?: run {
            toast("QR 에 QuicMic 주소가 없습니다")
            return
        }
        useHost(payload.host + ":" + payload.port)
        // Hand the PIN to the web UI as the hash fragment: its own pairing
        // flow clears stale tokens and pairs automatically (rationale: zero
        // duplicated pairing logic; token stays in WebView localStorage only).
        connectTo(payload.host, payload.pin)
    }

    /** Manual PIN entry from the Settings tab. */
    private fun pairWithPin(pin: String) {
        if (!pin.matches(Regex("\\d{6}"))) {
            toast("PIN 은 6자리 숫자입니다")
            return
        }
        val host = serverHost
        if (host == null) {
            toast("먼저 서버 주소를 입력하세요")
            promptForAddress(null) { pairWithPin(pin) }
            return
        }
        selectTab(0)
        connectTo(host, pin)
    }

    // ---------------------------------------------------------------------
    // State display & meter polling
    // ---------------------------------------------------------------------

    private fun setConn(state: Conn) {
        conn = state
        home.statusChip.text = when (state) {
            Conn.DISCOVERING -> "네트워크에서 QuicMic 서버를 찾는 중…"
            Conn.CONNECTING -> "연결하는 중…"
            Conn.UNPAIRED -> "서버에 연결됨 — 웹 화면에서 페어링하세요"
            Conn.OFFLINE -> "오프라인 — 서버에 연결할 수 없어요"
            Conn.NO_SERVER -> "서버 없음 — QR 로 연결하세요"
            Conn.CONNECTED -> "연결됨"
        }
        home.hostLine.text = serverHost?.let { "$it:${ServerDiscovery.PORT}" }
            ?: "같은 Wi-Fi 의 PC 가 QuicMic 서버를 실행해야 합니다"
        home.showEmbedded(webView != null && state != Conn.OFFLINE)
    }

    /**
     * Reads the embedded page's own truth (active screen + VU bar width) over
     * the JS bridge. Chosen over a native `/api/stats` poll because the stats
     * endpoint requires the session token, which deliberately lives only in
     * WebView localStorage — probing the DOM needs no secret handling in the
     * app at all.
     */
    private val meterPoll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) runProbe()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun runProbe() {
        val v = webView ?: return
        if (!pageLoaded || pendingTofu || showingMismatch) return
        // "active|level%|status text" — the web's own status line, verbatim.
        v.evaluateJavascript(
            "(function(){var m=document.getElementById('main-screen');" +
            "var b=document.getElementById('vu-bar');" +
            "var s=document.getElementById('status-text');" +
            "return ((m&&m.classList.contains('active'))?'1':'0')+'|'+((b&&b.style.width)||'')+" +
            "'|'+((s&&s.textContent)||'');})()"
        ) { result -> handleProbe(result) }
    }

    private fun handleProbe(result: String?) {
        val raw = result?.trim()?.removeSurrounding("\"") ?: return
        if (raw == "null") return
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return
        val (activeStr, levelStr, statusText) = parts
        val level = levelStr.removeSuffix("%").toFloatOrNull()?.toInt()?.coerceIn(0, 100) ?: 0
        home.meter.progress = level
        if (conn != Conn.OFFLINE) {
            if (activeStr == "1") {
                if (conn != Conn.CONNECTED) setConn(Conn.CONNECTED)
                home.statusChip.text = mapWebStatus(statusText)
            } else if (pageLoaded && conn != Conn.DISCOVERING && conn != Conn.CONNECTING) {
                setConn(Conn.UNPAIRED)
            }
        }
    }

    /** The web UI's English badge texts, mapped for the native chip. */
    private fun mapWebStatus(statusText: String): String = when {
        statusText.contains("Stream", ignoreCase = true) -> "연결됨 · 스트리밍 중"
        statusText.contains("Mute", ignoreCase = true) -> "연결됨 · 음소거"
        else -> "연결됨 · 대기 중"
    }

    // ---------------------------------------------------------------------
    // Settings actions
    // ---------------------------------------------------------------------

    private fun refreshSettings() {
        settings.refresh(tofu.entries())
    }

    private fun deleteFingerprint(key: String) {
        val idx = key.lastIndexOf(':')
        if (idx <= 0) return
        val host = key.substring(0, idx)
        val port = key.substring(idx + 1).toIntOrNull() ?: return
        AlertDialog.Builder(this)
            .setTitle("지문 삭제")
            .setMessage("$key 의 인증서 지문을 삭제할까요?\n다음 연결 때 지문을 다시 확인합니다.")
            .setPositiveButton("삭제") { _, _ ->
                tofu.forget(host, port)
                refreshSettings()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun promptForAddress(preset: String?, onDone: (String) -> Unit = {}) {
        val container = LinearLayout(this).apply {
            setPadding(64, 32, 64, 0)
            orientation = LinearLayout.HORIZONTAL
        }
        val input = EditText(this).apply {
            hint = "예: 192.168.1.42"
            setText(preset ?: "")
            setSingleLine()
            container.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("서버 주소 입력")
            .setMessage("QuicMic 서버(PC)가 표시한 IP 주소를 입력하세요.\n(터미널 QR 코드 위 주소)")
            .setView(container)
            .setPositiveButton("연결") { _, _ ->
                val host = input.text.toString().trim()
                if (host.isNotEmpty()) {
                    useHost(host)
                    onDone(host)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Blocking overlays (offline guidance, TOFU mismatch)
    // ---------------------------------------------------------------------

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun formatFingerprint(fp: String): String =
        fp.replace(":", "").chunked(4).joinToString(" ")

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    /**
     * LAN-unreachable notice, shown as an overlay above the tabs (the user can
     * still reach Settings via the tab bar). Same copy as the T02 screen.
     */
    private fun showOfflineScreen() {
        setConn(Conn.OFFLINE)
        root.findViewWithTag<View>(OFFLINE_TAG)?.let { root.removeView(it) }
        val box = LinearLayout(this).apply {
            tag = OFFLINE_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0x0a, 0x0a, 0x0f))
        }
        fun text(content: String) = TextView(this).apply {
            this.text = content
            textSize = 15f
            setTextColor(getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(48, 8, 48, 8)
        }
        box.addView(TextView(this).apply {
            text = "QuicMic"
            textSize = 22f
            setTextColor(getColor(R.color.text_primary))
            gravity = Gravity.CENTER
            setPadding(48, 16, 48, 16)
        })
        box.addView(text("집 네트워크가 아닙니다\n\nQuicMic 서버에 연결할 수 없어요.\n폰과 PC 가 같은 Wi-Fi 에 있는지 확인해 주세요."))
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        box.addView(button("다시 시도") {
            box.parent?.let { (it as ViewGroup).removeView(box) }
            startConnect()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 24
        })
        box.addView(button("설정 탭에서 주소 변경") {
            box.parent?.let { (it as ViewGroup).removeView(box) }
            selectTab(1)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 8
        })
        root.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** TOFU consent dialog; [onDone] receives whether the user accepted. */
    private fun showTofuDialog(fingerprint: String, isFirstUse: Boolean, onDone: (Boolean) -> Unit) {
        val message = buildString {
            append(if (isFirstUse) "이 서버의 인증서를 처음 만납니다.\n" else "인증서가 바뀌었습니다.\n")
            append("아래 지문이 PC 터미널의 것과 같은지 확인하고 수락하세요.\n\n")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("인증서 지문 확인")
            .setMessage(message)
            .setPositiveButton("수락") { _, _ -> onDone(true) }
            .setNegativeButton("거부") { _, _ -> onDone(false) }
            .setCancelable(false)
            .show()
        val fpView = TextView(this).apply {
            text = formatFingerprint(fingerprint)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(232, 232, 240))
            setPadding(64, 8, 64, 24)
        }
        val messageView = dialog.findViewById<View>(android.R.id.message)
        (messageView.parent as? ViewGroup)?.addView(fpView)
    }

    /** Full-screen block shown when the presented certificate mismatches the pin. */
    private fun showMismatchScreen(storedFp: String, newFp: String, reconfirm: (String) -> Unit) {
        root.findViewWithTag<View>(MISMATCH_TAG)?.let { root.removeView(it) }
        val box = LinearLayout(this).apply {
            tag = MISMATCH_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0x0a, 0x0a, 0x0f))
        }
        box.addView(TextView(this).apply {
            text = "⚠️ 서버 인증서가 바뀌었습니다\n\n처음 신뢰했던 인증서와 다릅니다.\n네트워크가 바뀌었거나(IP 변경) 다른 기기일 수 있어요."
            textSize = 15f
            setTextColor(getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(48, 16, 48, 16)
        })
        box.addView(TextView(this).apply {
            text = "저장된 지문:\n${formatFingerprint(storedFp)}\n\n새 지문:\n${formatFingerprint(newFp)}"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(getColor(R.color.text_secondary))
            setPadding(48, 24, 48, 24)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(Button(this).apply {
            text = "새 인증서 재확인"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("정말 새 인증서를 신뢰하시겠습니까?")
                    .setMessage("PC 에서 QuicMic 을 재설치했거나 LAN IP 가 바뀐 경우라면 안전합니다. 확실하지 않으면 거부하세요.")
                    .setPositiveButton("신뢰") { _, _ ->
                        box.parent?.let { (it as ViewGroup).removeView(box) }
                        reconfirm(newFp)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 8
        })
        box.addView(Button(this).apply {
            text = "연결 유지 (차단)"
            setOnClickListener {
                box.parent?.let { (it as ViewGroup).removeView(box) }
                setConn(Conn.OFFLINE)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 8
        })
        root.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------------------------------------------------------------------
    // Navigation & lifecycle
    // ---------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A blocking overlay sits above everything: back closes it first.
        root.findViewWithTag<View>(OFFLINE_TAG)?.let {
            (it.parent as ViewGroup).removeView(it); return
        }
        root.findViewWithTag<View>(MISMATCH_TAG)?.let {
            (it.parent as ViewGroup).removeView(it); return
        }
        val view = webView
        if (view != null && activeTab == 0 && view.canGoBack()) view.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(meterPoll)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val PREF_HOST = "host"
        private const val REQ_SCAN = 4202
        private const val OFFLINE_TAG = "quicmic_offline"
        private const val MISMATCH_TAG = "quicmic_mismatch"

        /** DOM probe cadence — fast enough for a lively meter, slow enough to
         *  be invisible next to the WebView's own workload. */
        private const val POLL_MS = 300L
    }
}
