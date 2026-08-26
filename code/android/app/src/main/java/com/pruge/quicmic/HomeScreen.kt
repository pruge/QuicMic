package com.pruge.quicmic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Home tab (V2 default screen): connection status card, the prominent
 * "QR로 연결" button, a native audio-level meter fed from the embedded web
 * UI's VU bar, and — once a server is reachable — the WebView that takes over
 * the rest of this tab (the web UI remains the actual control surface).
 *
 * Pure view construction; all behaviour is wired by [MainActivity].
 */
class HomeScreen(context: Context) {

    /** Rounded status card at the top of the tab. */
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.rgb(0x14, 0x14, 0x1e))
            cornerRadius = dp(context, 16).toFloat()
        }
        setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
    }

    /** Connection state line ("연결됨", "오프라인", …). */
    val statusChip = TextView(context).apply {
        textSize = 17f
        setTextColor(context.getColor(R.color.text_primary))
    }

    /** Current server host (or a hint when none). */
    val hostLine = TextView(context).apply {
        textSize = 13f
        setTextColor(context.getColor(R.color.text_secondary))
        setPadding(0, dp(context, 2), 0, 0)
    }

    /** Input level, 0..100; driven from the web UI's VU bar via JS probing. */
    val meter = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent))
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0x22, 0x22, 0x30))
    }

    /** Primary call to action while unconnected. */
    val qrButton = Button(context).apply {
        text = "📷  QR 코드로 연결"
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.accent))
            cornerRadius = dp(context, 12).toFloat()
        }
        setPadding(0, dp(context, 14), 0, dp(context, 14))
    }

    /** Hosts the WebView once a server session starts; fills remaining space. */
    val webContainer = FrameLayout(context)

    /** Hint shown where the WebView will appear, before any connection. */
    private val placeholder = TextView(context).apply {
        text = "PC 의 QuicMic 터미널에 표시된 QR 코드를 스캔하면\n연결됩니다."
        textSize = 13f
        setTextColor(context.getColor(R.color.text_secondary))
        gravity = Gravity.CENTER
    }

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(0x0a, 0x0a, 0x0f))
        val pad = dp(context, 16)
        setPadding(pad, pad, pad, pad)
        addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = "입력 레벨"
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(context, 16), 0, dp(context, 4))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(meter, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(qrButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 20)
        })
        addView(webContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(context, 16) })
        addView(placeholder, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(context, 16) })
    }

    /** Connected look: hide the CTA, reveal the embedded control surface. */
    fun showEmbedded(showWeb: Boolean) {
        qrButton.visibility = if (showWeb) View.GONE else View.VISIBLE
        webContainer.visibility = if (showWeb) View.VISIBLE else View.GONE
        placeholder.visibility = if (showWeb) View.GONE else View.VISIBLE
    }

    companion object {
        fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    }
}
