package com.osrsbot.autotrainer.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.osrsbot.autotrainer.utils.Logger
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureManager(private val context: Context) {

    private val bitmapRef = AtomicReference<Bitmap?>(null)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var isCapturing: Boolean = false
        private set

    val latestBitmap: Bitmap? get() = bitmapRef.get()

    fun start(projection: MediaProjection) {
        if (isCapturing) return
        mediaProjection = projection
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val rowPadding = rowStride - pixelStride * w
                    val raw = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    raw.copyPixelsFromBuffer(buffer)
                    val cropped = if (raw.width > w) Bitmap.createBitmap(raw, 0, 0, w, h).also { raw.recycle() } else raw
                    bitmapRef.getAndSet(cropped)?.recycle()
                } catch (e: Exception) {
                    Logger.error("ScreenCapture frame error: ${e.message}")
                } finally {
                    image.close()
                }
            }, handler)
        }

        virtualDisplay = projection.createVirtualDisplay(
            "OsrsBotCapture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Logger.warn("MediaProjection stopped."); stop() }
        }, handler)

        isCapturing = true
        Logger.ok("ScreenCaptureManager started ($w x $h @ $dpi dpi)")
    }

    fun stop() {
        if (!isCapturing) return
        isCapturing = false
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close();      imageReader = null
        mediaProjection?.stop();   mediaProjection = null
        bitmapRef.getAndSet(null)?.recycle()
        Logger.warn("ScreenCaptureManager stopped.")
    }
}
