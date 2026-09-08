package com.voicespreader.remote

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** 从相册选择图片并在手机本地识别二维码，不把原图发送给电脑。 */
class GalleryScanActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PAYLOAD = "gallery_scan_payload"
    }

    private lateinit var scanner: BarcodeScanner

    private val picker = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
        } else {
            scan(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        picker.launch("image/*")
    }

    private fun scan(uri: Uri) {
        runCatching {
            InputImage.fromFilePath(this, uri)
        }.onSuccess { image ->
            scanner.process(image)
                .addOnSuccessListener { values ->
                    val result = values.firstNotNullOfOrNull { it.rawValue }
                    if (result.isNullOrBlank()) {
                        setResult(Activity.RESULT_CANCELED)
                    } else {
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_PAYLOAD, result),
                        )
                    }
                    finish()
                }
                .addOnFailureListener {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
        }.onFailure {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        if (::scanner.isInitialized) scanner.close()
        super.onDestroy()
    }
}
