package com.example.devicemanager

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RemoteControlService : AccessibilityService() {

    private var server: WebServer? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestBitmapBytes: ByteArray? = null

    companion object {
        var instance: RemoteControlService? = null
        var mediaProjection: MediaProjection? = null
        var screenDensity: Int = 320
        var screenWidth: Int = 720
        var screenHeight: Int = 1280
        const val CHANNEL_ID = "remote_control_channel"
        const val NOTIFICATION_ID = 101
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            if (server == null) {
                server = WebServer(8080)
                server?.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startScreenCapture(projection: MediaProjection, width: Int, height: Int, density: Int) {
        mediaProjection = projection

        // 1. Create Notification Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Control Stream",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // 2. Start Foreground Service (Required for MediaProjection on Android 10-14)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Controller Active")
            .setContentText("Screen stream is running")
            .setSmallIcon(android.R.drawable.presence_online)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 3. Set display scaling (half-resolution for fluid network speed)
        screenWidth = width / 2
        screenHeight = height / 2
        screenDensity = density

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenStream",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                val baos = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                latestBitmapBytes = baos.toByteArray()

                bitmap.recycle()
                croppedBitmap.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun injectTap(x: Float, y: Float) {
        val targetX = x * 2f
        val targetY = y * 2f
        val path = Path().apply { moveTo(targetX, targetY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    inner class WebServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val params = session.parameters

            if (uri == "/tap") {
                val x = params["x"]?.firstOrNull()?.toFloatOrNull() ?: 0f
                val y = params["y"]?.firstOrNull()?.toFloatOrNull() ?: 0f
                injectTap(x, y)
                return newFixedLengthResponse("OK")
            }

            if (uri == "/key") {
                when (params["action"]?.firstOrNull()) {
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                return newFixedLengthResponse("OK")
            }

            if (uri == "/launch") {
                val pkg = params["pkg"]?.firstOrNull()
                if (!pkg.isNullOrEmpty()) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) startActivity(intent)
                }
                return newFixedLengthResponse("OK")
            }

            if (uri == "/frame") {
                val bytes = latestBitmapBytes ?: ByteArray(0)
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }

            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Live Screen Control</title>
                    <style>
                        body { margin: 0; background: #000; color: #fff; font-family: sans-serif; display: flex; flex-direction: column; align-items: center; }
                        #controls { display: flex; gap: 8px; padding: 10px; width: 100%; justify-content: center; background: #1a1a1a; }
                        button { padding: 10px 18px; border: none; border-radius: 6px; font-weight: bold; background: #2563eb; color: #fff; cursor: pointer; }
                        #screen { width: 90vw; max-width: 420px; border: 2px solid #333; touch-action: none; cursor: crosshair; }
                    </style>
                </head>
                <body>
                    <div id="controls">
                        <button onclick="fetch('/key?action=back')">Back</button>
                        <button onclick="fetch('/key?action=home')">Home</button>
                        <button onclick="fetch('/key?action=recents')">Recents</button>
                        <button onclick="fetch('/key?action=lock')" style="background:#dc2626;">Lock</button>
                    </div>
                    <img id="screen" src="/frame" alt="Waiting for screen stream...">
                    <script>
                        const img = document.getElementById('screen');
                        setInterval(() => {
                            img.src = '/frame?t=' + Date.now();
                        }, 120);

                        img.addEventListener('pointerdown', (e) => {
                            const rect = img.getBoundingClientRect();
                            const scaleX = img.naturalWidth / rect.width;
                            const scaleY = img.naturalHeight / rect.height;
                            const x = Math.round((e.clientX - rect.left) * scaleX);
                            const y = Math.round((e.clientY - rect.top) * scaleY);
                            fetch('/tap?x=' + x + '&y=' + y);
                        });
                    </script>
                </body>
                </html>
            """.trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
    }
}
