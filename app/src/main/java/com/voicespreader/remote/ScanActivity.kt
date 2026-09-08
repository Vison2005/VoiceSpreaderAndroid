package com.voicespreader.remote

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 通用二维码入口，与配对二维码扫描分开，结果只返回给当前功能页面。 */
class ScanActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PAYLOAD = "scan_payload"
        private const val CAMERA_REQUEST_CODE = 1240
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private lateinit var scanner: BarcodeScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_scanner)
        findViewById<View>(R.id.closeScannerButton).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
            return
        }
        startScanner()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST_CODE
            || grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        startScanner()
    }

    private fun startScanner() {
        findViewById<TextView>(R.id.scannerBottomHint).text = "将二维码放入框内"
        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128)
                .build(),
        )
        startCamera(findViewById(R.id.previewView))
    }

    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun startCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null || !processing.compareAndSet(false, true)) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { values ->
                        values.firstNotNullOfOrNull { it.rawValue }?.let { payload ->
                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, payload))
                            finish()
                        }
                    }
                    .addOnCompleteListener {
                        proxy.close()
                        processing.set(false)
                    }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        if (::scanner.isInitialized) scanner.close()
        executor.shutdownNow()
        super.onDestroy()
    }

}
