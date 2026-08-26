package com.pruge.quicmic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Full-screen QR scanner for pairing.
 *
 * CameraX drives the preview and frame analysis; ML Kit's bundled barcode
 * model decodes QR frames entirely on-device (no Play Services round-trip).
 * On the first recognized `https://<host>:<port>#<pin>` payload the activity
 * finishes and hands the raw URL back to [MainActivity] via [resultUrl].
 *
 * Runs as its own [LifecycleOwner] so CameraX binds/relases cleanly with this
 * activity's lifetime without dragging in AppCompat or androidx.activity.
 */
class ScanActivity : Activity(), LifecycleOwner {

    override fun getLifecycle(): Lifecycle = lifecycleRegistry

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null

    /** Set once a decodable QuicMic QR was seen — further frames are ignored. */
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Simple aiming hint — a translucent frame outline plus caption. The
        // scanner itself reads the whole image, the box is only guidance.
        val hint = TextView(this).apply {
            text = "PC 화면의 QR 코드를 프레임 안에 비춰주세요"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            setPadding(32, 16, 32, 16)
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, 96) })

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "QR 스캔에는 카메라 권한이 필요합니다", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val options = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val scanner = BarcodeScanning.getClient(options)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    processFrame(scanner, proxy)
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 시작할 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, mainExecutor)
    }

    /** Decode one camera frame; called on the analysis executor thread. */
    private fun processFrame(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null || delivered) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(
            mediaImage,
            proxy.imageInfo.rotationDegrees,
        )
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                if (delivered) return@addOnSuccessListener
                for (barcode in barcodes) {
                    val url = barcode.rawValue ?: continue
                    if (QrPayload.parse(url) != null) {
                        delivered = true
                        val data = android.content.Intent().putExtra(EXTRA_URL, url)
                        runOnUiThread {
                            setResult(RESULT_OK, data)
                            finish()
                        }
                        break
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    companion object {
        private const val REQ_CAMERA = 4101
        const val EXTRA_URL = "url"
    }
}
