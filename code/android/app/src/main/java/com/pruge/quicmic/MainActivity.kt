package com.pruge.quicmic

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
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
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Thin WebView wrapper around the QuicMic server's web UI.
 *
 * Responsibilities (the web page itself provides everything else — pairing,
 * streaming, reconnects — in real time from the server):
 *  1. Resolve the server address: stored value first, then a LAN /24 probe,
 *     then manual entry as the fallback.
 *  2. TOFU certificate trust: on the first TLS error show the SHA-256
 *     fingerprint, save it on acceptance, block on a later mismatch.
 *  3. Offline guidance: when the LAN is unreachable, show a dedicated notice
 *     screen with retry instead of a bare WebView error.
 *
 * The app deliberately holds no secrets of its own: WebView localStorage keeps
 * the session token, so reconnection logic lives entirely in the web UI.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var tofu: TofuStore
    private lateinit var addressPrefs: android.content.SharedPreferences

    private var webView: WebView? = null
    private var serverHost: String? = null

    /** True while a TOFU dialog is up — suppresses the offline screen that the
     *  cancelled load would otherwise trigger underneath. */
    private var pendingTofu = false

    /** True while the mismatch/blocking screen is shown. */
    private var showingMismatch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tofu = TofuStore(this)
        addressPrefs = getSharedPreferences("server", MODE_PRIVATE)
        root = FrameLayout(this)
        setContentView(root)
        startConnect()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------------
    // Connection flow
    // ---------------------------------------------------------------------

    private fun startConnect() {
        val saved = addressPrefs.getString(PREF_HOST, null)
        if (saved != null) {
            loadServer(saved)
            return
        }
        showLoading("LAN에서 QuicMic 서버를 찾는 중…")
        Thread {
            val found = runCatching { ServerDiscovery.discover() }.getOrDefault(emptyList())
            runOnUiThread {
                when {
                    found.size == 1 -> useHost(found[0])
                    found.isEmpty() -> promptForAddress(null)
                    else -> pickFromCandidates(found)
                }
            }
        }.start()
    }

    private fun pickFromCandidates(candidates: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("QuicMic 서버 선택")
            .setItems(candidates.toTypedArray()) { _, which -> useHost(candidates[which]) }
            .setOnCancelListener { promptForAddress(null) }
            .show()
    }

    private fun promptForAddress(preset: String?) {
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
                if (host.isNotEmpty()) useHost(host)
            }
            .setNegativeButton("취소") { _, _ -> showOfflineScreen() }
            .setOnCancelListener { showOfflineScreen() }
            .show()
    }

    private fun useHost(host: String) {
        // Strip any scheme/port/hash a user may have pasted from the QR URL.
        // Bracketed IPv6 literals ([fe80::1] style) lose their brackets here.
        val clean = host.removePrefix("https://").removePrefix("http://")
            .substringBefore("/").trim()
            .let { if (it.startsWith("[")) it.substringAfter("[").substringBefore("]") else it.substringBefore(":") }
        if (clean.isEmpty()) {
            promptForAddress(host)
            return
        }
        serverHost = clean
        addressPrefs.edit().putString(PREF_HOST, clean).apply()
        loadServer(clean)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadServer(host: String) {
        showLoading("QuicMic 서버에 연결하는 중…")
        val view = webView ?: createWebView().also { webView = it }
        if (view.parent == null) root.addView(view)
        view.loadUrl("https://$host:${ServerDiscovery.PORT}/")
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
    // Screens & dialogs
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

    private fun clearScreens(vararg keep: View) {
        children().forEach { if (it !== webView && it !in keep) root.removeView(it) }
    }

    private fun children(): List<View> {
        val out = mutableListOf<View>()
        for (i in 0 until root.childCount) out += root.getChildAt(i)
        return out
    }

    private fun statusScreen(message: String): LinearLayout {
        clearScreens()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BACKGROUND)
        }
        fun text(size: Float, colorRes: Int, content: String) = TextView(this).apply {
            this.text = content
            textSize = size
            setTextColor(getColor(colorRes))
            gravity = Gravity.CENTER
            setPadding(48, 16, 48, 16)
        }
        box.addView(text(22f, R.color.text_primary, "QuicMic"))
        box.addView(text(15f, R.color.text_secondary, message))
        root.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return box
    }

    private fun showLoading(message: String) {
        val box = statusScreen(message)
        box.addView(ProgressBar(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 32
        })
    }

    private fun showOfflineScreen() {
        val box = statusScreen("집 네트워크가 아닙니다\n\nQuicMic 서버에 연결할 수 없어요.\n폰과 PC가 같은 Wi-Fi에 있는지 확인해 주세요.")
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        box.addView(button("다시 시도") { startConnect() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 24
        })
        box.addView(button("서버 주소 변경") { promptForAddress(serverHost) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 8
        })
    }

    private fun formatFingerprint(fp: String): String =
        fp.replace(":", "").chunked(4).joinToString(" ")

    /**
     * TOFU consent dialog. [onDone] receives whether the user accepted; the
     * caller persists the fingerprint and reloads.
     */
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
        // Insert a monospace fingerprint block into the standard dialog.
        val fpView = TextView(this).apply {
            text = formatFingerprint(fingerprint)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(232, 232, 240))
            setPadding(64, 8, 64, 24)
        }
        val messageView = dialog.findViewById<View>(android.R.id.message)
        (messageView.parent as? ViewGroup)?.addView(fpView)
    }

    /** Full-screen block shown when the presented certificate mismatches the pin. */
    private fun showMismatchScreen(storedFp: String, newFp: String, reconfirm: (String) -> Unit) {
        val box = statusScreen("⚠️ 서버 인증서가 바뀌었습니다\n\n처음 신뢰했던 인증서와 다릅니다.\n네트워크가 바뀌었거나(IP 변경) 다른 기기일 수 있어요.")
        box.addView(TextView(this).apply {
            text = "저장된 지문:\n${formatFingerprint(storedFp)}\n\n새 지문:\n${formatFingerprint(newFp)}"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
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
                    .setPositiveButton("신뢰") { _, _ -> reconfirm(newFp) }
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
            setOnClickListener { showOfflineScreen() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 8
        })
    }

    // ---------------------------------------------------------------------
    // Back navigation
    // ---------------------------------------------------------------------

    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val PREF_HOST = "host"
        private val BACKGROUND = Color.rgb(0x0a, 0x0a, 0x0f)
    }
}
