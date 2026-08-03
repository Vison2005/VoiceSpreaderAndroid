package com.voicespreader.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

class ScannerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PAYLOAD = "pairing_payload"
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private lateinit var scanner: BarcodeScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        scanner = BarcodeScanning.getClient(options)
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
            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null || !processing.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees,
                )
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val payload = barcodes.firstNotNullOfOrNull { barcode ->
                            barcode.rawValue?.takeIf {
                                PairingInfo.fromQrPayload(it) != null
                            }
                        }
                        if (payload != null) {
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(EXTRA_PAYLOAD, payload),
                            )
                            finish()
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                        processing.set(false)
                    }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        if (::scanner.isInitialized) scanner.close()
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }
}
