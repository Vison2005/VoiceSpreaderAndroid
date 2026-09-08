package com.voicespreader.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class AudioStreamer(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 48_000
        private const val CHUNK_FRAMES = 960
        private const val CLOCK_SAMPLE_INTERVAL_CHUNKS = 50
    }

    private val generation = AtomicInteger()
    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var worker: Thread? = null

    fun start(
        output: DataOutputStream,
        onStarted: () -> Unit,
        onLevel: (Double) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (worker?.isAlive == true) return
        val currentGeneration = generation.incrementAndGet()
        worker = thread(name = "VoiceSpreaderAudioCapture") {
            var recorder: AudioRecord? = null
            runCatching {
                val activeRecorder = createAudioRecord()
                recorder = activeRecorder
                if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
                    error("手机麦克风无法按 48 kHz 初始化")
                }
                if (currentGeneration != generation.get()) return@runCatching
                audioRecord = activeRecorder
                activeRecorder.startRecording()
                if (activeRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    error("手机系统没有提供麦克风数据")
                }
                if (currentGeneration != generation.get()) return@runCatching
                onStarted()

                val samples = ShortArray(CHUNK_FRAMES)
                var frameIndex = 0L
                var levelCounter = 0
                var clockSampleCounter = 0
                val timestamp = AudioTimestamp()
                while (currentGeneration == generation.get()) {
                    val count = activeRecorder.read(
                        samples,
                        0,
                        samples.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (count <= 0) error("读取麦克风失败：$count")
                    if (currentGeneration != generation.get()) break
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

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && ++clockSampleCounter >= CLOCK_SAMPLE_INTERVAL_CHUNKS
                        && activeRecorder.getTimestamp(
                            timestamp,
                            AudioTimestamp.TIMEBASE_MONOTONIC,
                        ) == AudioRecord.SUCCESS) {
                        synchronized(output) {
                            output.writeInt(17)
                            output.writeByte(4)
                            // AudioTimestamp 的时间必须与它自己的硬件帧位置配对；固定原点偏移不影响斜率。
                            output.writeLong(timestamp.framePosition)
                            output.writeLong(timestamp.nanoTime)
                            output.flush()
                        }
                        clockSampleCounter = 0
                    }

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
                if (currentGeneration == generation.get()) {
                    onError(it.message ?: "麦克风回传已停止")
                }
            }
            recorder?.let { currentRecorder ->
                runCatching { currentRecorder.stop() }
                runCatching { currentRecorder.release() }
                if (audioRecord === currentRecorder) {
                    audioRecord = null
                }
            }
            if (worker === Thread.currentThread()) {
                worker = null
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        val currentRecorder = audioRecord
        val currentWorker = worker
        runCatching { currentRecorder?.stop() }
        if (currentWorker !== Thread.currentThread()) {
            runCatching { currentWorker?.join(1200) }
        }
        if (worker === currentWorker) worker = null
        if (audioRecord === currentRecorder) audioRecord = null
        runCatching { currentRecorder?.release() }
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

}
