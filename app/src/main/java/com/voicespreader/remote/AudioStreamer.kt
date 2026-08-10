package com.voicespreader.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class AudioStreamer(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 48_000
        private const val CHUNK_FRAMES = 960
    }

    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    fun start(
        output: DataOutputStream,
        onStarted: () -> Unit,
        onLevel: (Double) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (running.getAndSet(true)) return
        worker = thread(name = "VoiceSpreaderAudioCapture") {
            runCatching {
                val recorder = createAudioRecord()
                audioRecord = recorder
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    error("手机麦克风无法按 48 kHz 初始化")
                }
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    error("手机系统没有提供麦克风数据")
                }
                onStarted()

                val samples = ShortArray(CHUNK_FRAMES)
                var frameIndex = 0L
                var levelCounter = 0
                while (running.get()) {
                    val count = recorder.read(
                        samples,
                        0,
                        samples.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (count <= 0) error("读取麦克风失败：$count")
                    synchronized(output) {
                        output.writeInt(13 + count * 2)
                        output.writeByte(1)
                        output.writeLong(frameIndex)
                        output.writeInt(SAMPLE_RATE)
                        repeat(count) { index ->
                            val value = samples[index].toInt()
                            output.writeByte(value and 0xFF)
                            output.writeByte((value ushr 8) and 0xFF)
                        }
                        output.flush()
                    }
                    frameIndex += count

                    if (++levelCounter >= 5) {
                        var energy = 0.0
                        repeat(count) { index ->
                            val value = samples[index] / 32768.0
                            energy += value * value
                        }
                        val rms = sqrt(energy / count)
                        onLevel(if (rms > 0.000001) 20.0 * log10(rms) else -120.0)
                        levelCounter = 0
                    }
                }
            }.onFailure {
                if (running.get()) onError(it.message ?: "麦克风回传已停止")
            }
            stopRecorder()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        worker?.join(1200)
        worker = null
        stopRecorder()
    }

    private fun createAudioRecord(): AudioRecord {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("麦克风权限已被撤销")
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rawSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            && audioManager.getProperty(
                AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED,
            ) == "true"
        val source = if (rawSupported) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minimumBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumBytes, CHUNK_FRAMES * 8))
            .build()
    }

    private fun stopRecorder() {
        runCatching { audioRecord?.release() }
        audioRecord = null
        running.set(false)
    }
}
