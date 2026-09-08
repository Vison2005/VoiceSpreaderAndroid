package com.voicespreader.remote

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

data class CapturedPhoto(
    val bytes: ByteArray,
    val mime: String = "image/jpeg",
)

/** CameraX 内存拍照适配器，结果由调用方通过 file channel 发送。 */
class CaptureController(private val context: Context) {
    fun capturePhoto(
        imageCapture: ImageCapture,
        onSuccess: (CapturedPhoto) -> Unit,
        onError: (String) -> Unit,
    ) {
        imageCapture.takePicture(
            ContextCompatExecutor.main(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    runCatching { imageToJpeg(image) }
                        .onSuccess { onSuccess(CapturedPhoto(it)) }
                        .onFailure { onError(it.message ?: "照片读取失败") }
                    image.close()
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception.message ?: "拍照失败")
                }
            },
        )
    }

    private fun imageToJpeg(image: ImageProxy): ByteArray {
        if (image.format == ImageFormat.JPEG) {
            val plane = image.planes.firstOrNull() ?: error("照片没有有效数据")
            return plane.buffer.duplicate().let { buffer ->
                ByteArray(buffer.remaining()).also(buffer::get)
            }
        }
        require(image.format == ImageFormat.YUV_420_888) { "不支持的照片格式" }
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        copyPlane(yPlane, width, height, nv21, 0, 1)
        // NV21 要求交错的 VU 排列；兼容像素步长为 1 或 2 的设备实现。
        val chromaHeight = height / 2
        var target = width * height
        for (row in 0 until chromaHeight) {
            for (column in 0 until width / 2) {
                val vIndex = row * vPlane.rowStride + column * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + column * uPlane.pixelStride
                nv21[target++] = vPlane.buffer.get(vIndex)
                nv21[target++] = uPlane.buffer.get(uIndex)
            }
        }
        val output = ByteArrayOutputStream(width * height / 2)
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 92, output)
        return output.toByteArray()
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        target: ByteArray,
        targetOffset: Int,
        targetPixelStride: Int,
    ) {
        val source = plane.buffer.duplicate()
        var targetIndex = targetOffset
        for (row in 0 until height) {
            val rowOffset = row * plane.rowStride
            for (column in 0 until width) {
                val sourceIndex = rowOffset + column * plane.pixelStride
                if (sourceIndex >= source.limit()) error("照片平面数据不完整")
                target[targetIndex] = source.get(sourceIndex)
                targetIndex += targetPixelStride
            }
        }
    }

    private object ContextCompatExecutor {
        fun main(context: Context) = androidx.core.content.ContextCompat.getMainExecutor(context)
    }
}
