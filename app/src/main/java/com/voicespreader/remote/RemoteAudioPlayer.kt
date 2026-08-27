package com.voicespreader.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class RemoteAudioPlayer {
    private val generation = AtomicInteger()
    private val frames = ArrayBlockingQueue<RemoteAudioFrame>(24)

    @Volatile
    private var enabled = false
    @Volatile
    private var activeTrack: AudioTrack? = null
    private var worker: Thread? = null

    fun start(
        onStarted: (sampleRate: Int, channels: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        stop()
        enabled = true
        val currentGeneration = generation.incrementAndGet()
        worker = Thread({ run(currentGeneration, onStarted, onError) }, "phone-audio-playback")
            .apply { start() }
    }

    fun enqueue(frame: RemoteAudioFrame) {
        if (!enabled) return
        if (!frames.offer(frame)) {
            frames.poll()
            frames.offer(frame)
        }
    }

    fun stop() {
        enabled = false
        generation.incrementAndGet()
        frames.clear()
        worker?.interrupt()
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
        runCatching { worker?.join(750) }
        worker = null
    }

    private fun run(
        currentGeneration: Int,
        onStarted: (sampleRate: Int, channels: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        var track: AudioTrack? = null
        var activeSampleRate = 0
        var activeChannels = 0
        var expectedFrameIndex: Long? = null
        var bufferedFrames = 0L
        var playbackStarted = false
        try {
            while (enabled && currentGeneration == generation.get()) {
                val frame = frames.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (track == null
                    || frame.sampleRate != activeSampleRate
                    || frame.channels != activeChannels
                ) {
                    track?.run {
                        pause()
                        flush()
                        release()
                    }
                    track = createAudioTrack(frame.sampleRate, frame.channels)
                    activeTrack = track
                    activeSampleRate = frame.sampleRate
                    activeChannels = frame.channels
                    expectedFrameIndex = null
                    bufferedFrames = 0
                    playbackStarted = false
                }

                val bytesPerFrame = activeChannels * Short.SIZE_BYTES
                val frameCount = frame.pcm16LittleEndian.size / bytesPerFrame
                var sourceOffsetFrames = 0L
                expectedFrameIndex?.let { expected ->
                    val difference = frame.firstFrameIndex - expected
                    val maximumGapFrames = activeSampleRate * MAXIMUM_GAP_MILLISECONDS / 1000L
                    when {
                        difference > maximumGapFrames -> {
                            track.pause()
                            track.flush()
                            expectedFrameIndex = frame.firstFrameIndex
                            bufferedFrames = 0
                            playbackStarted = false
                        }

                        difference > 0 -> {
                            writeFully(
                                track,
                                ByteArray((difference * bytesPerFrame).toInt()),
                                currentGeneration,
                            )
                            bufferedFrames += difference
                            expectedFrameIndex = expected + difference
                            val targetFrames =
                                activeSampleRate * TARGET_PREBUFFER_MILLISECONDS / 1000L
                            if (!playbackStarted && bufferedFrames >= targetFrames) {
                                track.play()
                                playbackStarted = true
                                onStarted(activeSampleRate, activeChannels)
                            }
                        }

                        difference < 0 -> {
                            sourceOffsetFrames = minOf(-difference, frameCount.toLong())
                        }
                    }
                }

                val byteOffset = (sourceOffsetFrames * bytesPerFrame).toInt()
                if (byteOffset < frame.pcm16LittleEndian.size) {
                    writeFully(
                        track,
                        frame.pcm16LittleEndian,
                        currentGeneration,
                        byteOffset,
                    )
                    val writtenFrames = frameCount - sourceOffsetFrames
                    bufferedFrames += writtenFrames
                    expectedFrameIndex = frame.firstFrameIndex + frameCount
                }

                val targetFrames = activeSampleRate * TARGET_PREBUFFER_MILLISECONDS / 1000L
                if (!playbackStarted && bufferedFrames >= targetFrames) {
                    track.play()
                    playbackStarted = true
                    onStarted(activeSampleRate, activeChannels)
                }
            }
        } catch (_: InterruptedException) {
            // 停止播放时中断等待属于正常生命周期。
        } catch (error: Throwable) {
            if (enabled && currentGeneration == generation.get()) {
                onError(error.message ?: "手机音频播放失败")
            }
        } finally {
            track?.let { currentTrack ->
                if (activeTrack === currentTrack) activeTrack = null
                currentTrack.runCatching {
                    pause()
                    flush()
                    release()
                }
            }
        }
    }

    private fun writeFully(
        track: AudioTrack,
        bytes: ByteArray,
        currentGeneration: Int,
        initialOffset: Int = 0,
    ) {
        var offset = initialOffset
        while (offset < bytes.size && enabled && currentGeneration == generation.get()) {
            val written = track.write(
                bytes,
                offset,
                bytes.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) error("AudioTrack 写入失败：$written")
            offset += written
        }
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int): AudioTrack {
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) error("手机不支持 $sampleRate Hz / $channels 声道播放")

        val bytesPerFrame = channels * Short.SIZE_BYTES
        val targetBuffer = max(
            minimumBuffer,
            sampleRate * bytesPerFrame * AUDIO_TRACK_BUFFER_MILLISECONDS / 1000,
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(targetBuffer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build().also {
            if (it.state != AudioTrack.STATE_INITIALIZED) {
                it.release()
                error("无法初始化手机音频输出")
            }
        }
    }

    private companion object {
        const val TARGET_PREBUFFER_MILLISECONDS = 80
        const val AUDIO_TRACK_BUFFER_MILLISECONDS = 240
        const val MAXIMUM_GAP_MILLISECONDS = 120
    }
}
