package com.example.cameralink.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class H265Encoder {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var listener: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    fun initEncoder(width: Int, height: Int, bitrate: Int, frameRate: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec?.createInputSurface()
        codec?.start()
    }

    fun encode(listener: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        this.listener = listener
        val bufferInfo = MediaCodec.BufferInfo()
        val outputBufferIndex = codec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
        if (outputBufferIndex >= 0) {
            codec?.getOutputBuffer(outputBufferIndex)?.let {
                listener(it, bufferInfo)
            }
            codec?.releaseOutputBuffer(outputBufferIndex, false)
        }
    }

    fun getInputSurface(): Surface? = inputSurface

    fun release() {
        codec?.stop()
        codec?.release()
    }
}
