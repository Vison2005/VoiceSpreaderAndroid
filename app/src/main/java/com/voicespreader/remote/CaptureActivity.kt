package com.voicespreader.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

/** 拍照后直接以内存数据发起文件传输，不写入手机相册。 */
class CaptureActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REQUEST_ID = "capture_request_id"
        const val EXTRA_MODE = "capture_mode"
        private const val CAMERA_REQUEST_CODE = 1241
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var captureController: CaptureController
    private var imageCapture: ImageCapture? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureController = CaptureController(this)
        setContentView(createContent())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE
            && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            toast("需要相机权限才能拍照")
            finish()
        }
    }

    private fun createContent(): FrameLayout {
        previewView = PreviewView(this)
        captureButton = Button(this).apply {
            text = "拍照并发送"
            isAllCaps = false
            setOnClickListener { capture() }
            isEnabled = false
        }
        val buttonLayout = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            58,
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(20, 0, 20, 24)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(TextView(context).apply {
                text = "拍摄结果只发送到当前电脑，不保存到相册"
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(16, 18, 16, 18)
            }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(captureButton, buttonLayout)
        }
        return root
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
            captureButton.isEnabled = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        if (completed) return
        val capture = imageCapture ?: return
        captureButton.isEnabled = false
        captureController.capturePhoto(
            capture,
            onSuccess = { photo -> sendPhoto(photo) },
            onError = {
                captureButton.isEnabled = true
                toast(it)
            },
        )
    }

    private fun sendPhoto(photo: CapturedPhoto) {
        val session = FeatureSessionRegistry.control
        val pairing = FeatureSessionRegistry.pairing
        val deviceId = FeatureSessionRegistry.deviceId
        if (session == null || pairing == null || deviceId.isNullOrBlank()) {
            toast("当前没有 protocol 3 文件连接")
            captureButton.isEnabled = true
            return
        }
        val itemId = UUID.randomUUID()
        val source = FileTransferSource(
            ProtocolV3.FileItem(
                itemId,
                "VoiceSpreader-${System.currentTimeMillis()}.jpg",
                photo.mime,
                photo.bytes.size.toLong(),
                0L,
            ),
        ) { photo.bytes.inputStream() }
        val transfer = FileTransferClient(pairing, deviceId, session)
        runCatching {
            val transferId = transfer.offer(listOf(source))
            FileTransferCoordinator.register(transferId, transfer, listOf(source))
            session.sendJson(
                ProtocolV3.TYPE_CAPTURE_RESULT,
                JSONObject()
                    .put("requestId", intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty())
                    .put("mode", intent.getStringExtra(EXTRA_MODE).orEmpty().ifBlank { "photo" })
                    .put("status", "ready")
                    .put("transferId", transferId)
                    .put("itemId", itemId.toString())
                    .put("mime", photo.mime)
                    .put("size", photo.bytes.size),
            )
            completed = true
            toast("照片已提交，等待电脑接收")
            finish()
        }.onFailure {
            transfer.close()
            FeatureSessionRegistry.invalidate(session)
            FeatureSessionRegistry.ensureConnected(this)
            captureButton.isEnabled = true
            toast("功能连接已断开，请重新连接电脑")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
