package com.pruge.quicmic

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings tab (V2 default screen): server address display/edit, QR pairing,
 * manual PIN entry, and management of the TOFU-pinned certificate fingerprints
 * (view + delete, backed by [TofuStore]).
 *
 * Pure view construction; behaviour is wired by [MainActivity] through the
 * callback properties below.
 */
class SettingsScreen(context: Context) {

    /** Current host, rendered into the address row. */
    var serverHostProvider: () -> String? = { null }

    /** Launch the QR scanner. */
    var onScan: () -> Unit = {}

    /** Replace the saved server address via an edit dialog. */
    var onEditAddress: () -> Unit = {}

    /**
     * Pair with a hand-entered six-digit PIN against the current (or newly
     * prompted) server. Receives exactly what the user typed.
     */
    var onManualPair: (String) -> Unit = {}

    /** Delete one pinned fingerprint ("host:port" key). */
    var onDeleteFingerprint: (String) -> Unit = {}

    private val addressValue = TextView(context).apply {
        textSize = 15f
        setTextColor(context.getColor(R.color.text_primary))
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(context, 6), 0, dp(context, 10))
    }

    private val pinInput = EditText(context).apply {
        hint = "6자리 PIN"
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        setSingleLine()
    }

    /** Container for the per-endpoint fingerprint rows; rebuilt on refresh(). */
    private val fingerprintList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val root: View = ScrollView(context).apply {
        setBackgroundColor(Color.rgb(0x0a, 0x0a, 0x0f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 16)
            setPadding(pad, pad, pad, pad)

            // ---- 서버 -----------------------------------------------------
            addView(sectionTitle(context, "서버"))
            addView(TextView(context).apply {
                text = "현재 주소"
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
            })
            addView(addressValue)
            addView(button(context, "주소 직접 입력") { onEditAddress() })
            addView(spacer(context))

            // ---- 연결 -----------------------------------------------------
            addView(sectionTitle(context, "연결"))
            addView(button(context, "📷  QR 코드로 연결") { onScan() })
            addView(TextView(context).apply {
                text = "수동 PIN 연결 (QR 을 읽을 수 없을 때)"
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, dp(context, 16), 0, dp(context, 4))
            })
            addView(pinInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(button(context, "PIN 으로 연결") {
                onManualPair(pinInput.text.toString())
            }.also { it.background = GradientDrawable().apply {
                setColor(Color.rgb(0x1c, 0x1c, 0x2a))
                cornerRadius = dp(context, 12).toFloat()
            } }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 8)
            })
            addView(spacer(context))

            // ---- 보안 -----------------------------------------------------
            addView(sectionTitle(context, "보안 — 신뢰한 인증서"))
            addView(TextView(context).apply {
                text = "첫 연결 때 수락한 서버 인증서 지문(TOFU)입니다. " +
                    "삭제하면 다음 연결 때 지문을 다시 확인합니다."
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, 0, 0, dp(context, 8))
            })
            addView(fingerprintList)
            addView(spacer(context))

            // ---- 정보 -----------------------------------------------------
            addView(sectionTitle(context, "정보"))
            addView(TextView(context).apply {
                text = "QuicMic ${BuildConfig.VERSION_NAME}"
                textSize = 13f
                setTextColor(context.getColor(R.color.text_secondary))
            })
        })
    }

    /** Re-render address + fingerprint list from the current state. */
    fun refresh(tofuEntries: List<Pair<String, String>>) {
        val host = serverHostProvider()
        addressValue.text = when {
            host != null -> "$host:${ServerDiscovery.PORT}"
            else -> "저장된 서버 없음"
        }
        fingerprintList.removeAllViews()
        val ctx = fingerprintList.context
        if (tofuEntries.isEmpty()) {
            fingerprintList.addView(TextView(ctx).apply {
                text = "아직 신뢰한 인증서가 없습니다."
                textSize = 13f
                setTextColor(ctx.getColor(R.color.text_secondary))
            })
            return
        }
        for ((key, fp) in tofuEntries) {
            fingerprintList.addView(makeFingerprintRow(ctx, key, fp))
        }
    }

    private fun makeFingerprintRow(ctx: Context, key: String, fingerprint: String): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x14, 0x14, 0x1e))
                cornerRadius = dp(ctx, 10).toFloat()
            }
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        }
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = key
                textSize = 13f
                setTextColor(ctx.getColor(R.color.text_primary))
                typeface = Typeface.MONOSPACE
            })
            // First two bytes are enough to recognise an endpoint at a glance;
            // the full value is what the consent dialog showed.
            addView(TextView(ctx).apply {
                text = TofuStore.normalize(fingerprint).replace(":", "").take(16) + "…"
                textSize = 11f
                setTextColor(ctx.getColor(R.color.text_secondary))
                typeface = Typeface.MONOSPACE
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(ctx).apply {
            text = "삭제"
            textSize = 12f
            setTextColor(Color.rgb(0xff, 0x6b, 0x6b))
            background = null
            setOnClickListener { onDeleteFingerprint(key) }
        })
        (row.layoutParams as? LinearLayout.LayoutParams)?.let { /* margins handled by caller */ }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(ctx, 6)
        row.layoutParams = lp
        return row
    }

    // ---- small builders ----------------------------------------------------

    private fun sectionTitle(context: Context, title: String): TextView =
        TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(context.getColor(R.color.accent))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(context, 8))
        }

    private fun button(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            transformationMethod = null
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x22, 0x22, 0x30))
                cornerRadius = dp(context, 12).toFloat()
            }
            setOnClickListener { onClick() }
        }.let { b ->
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 4)
            }.let { lp ->
                b.layoutParams = lp
                b
            }
        }

    private fun spacer(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(context, 20))
    }

    companion object {
        fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    }
}
