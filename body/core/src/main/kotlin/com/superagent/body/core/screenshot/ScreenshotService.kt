package com.superagent.body.core.screenshot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视觉感知 L1 截图生产（BD-02.2 第一步）：
 * MediaProjection（用户在主界面点"屏幕捕获"授权一次）→ VirtualDisplay → ImageReader
 * → capture() 产出 JPEG 到 blobs/，perceive.screen(mode=vision) 以 screenshotRef 引用，
 * brain 侧经 GET /blob/{ref} 取图送 GLM-4.6V。
 * 未授权时 isReady()=false，perceive 自动回退 a11y（typed note，不硬失败）。
 */
class ScreenshotService(private val context: Context) {
    private val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val capturing = AtomicBoolean(false)
    private var width = 1152
    private var height = 2256

    /** 主界面按钮发起系统授权弹窗（user-in-loop，一次授权全程有效） */
    fun consentIntent(): Intent = mpm.createScreenCaptureIntent()

    /** 授权回调（onActivityResult）接入；失败/取消返回 false */
    @SuppressLint("WrongConstant")
    fun attach(resultCode: Int, data: Intent): Boolean {
        stop()
        val mp = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull() ?: return false
        projection = mp
        val dm = context.resources.displayMetrics
        // 长边限 1600：GLM 视觉够用，JPEG 体积可控
        val scale = 1600f / maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        width = (dm.widthPixels * scale).toInt().coerceAtLeast(1)
        height = (dm.heightPixels * scale).toInt().coerceAtLeast(1)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = mp.createVirtualDisplay(
            "superagent-vision", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null,
        )
        Log.i(TAG, "projection attached ${width}x${height}")
        return true
    }

    fun isReady(): Boolean = projection != null && display != null && reader != null

    /**
     * 抓一帧存 JPEG，返回文件名（blob id）。前台投屏期间镜像持续刷新，取 latest 即可。
     * 未授权返回 null（调用方回退 a11y）。
     */
    fun capture(outDir: File): String? {
        if (!isReady() || !capturing.compareAndSet(false, true)) return null
        try {
            val r = reader!!
            var image: Image? = null
            // 等待最新帧到位（虚拟屏镜像有 1-2 帧延迟）
            for (i in 0 until 10) {
                image?.close()
                image = runCatching { r.acquireLatestImage() }.getOrNull()
                if (image != null) break
                Thread.sleep(100)
            }
            image ?: return null
            // P2-01（审计）：Image 句柄异常路径也必须释放——漏一次耗尽 ImageReader 两帧缓冲后截图永久失效
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride.toLong()
                val pixelStride = plane.pixelStride.toLong()
                val buffer = plane.buffer.duplicate()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val px = IntArray(width * height)
                var dst = 0
                for (row in 0 until height) {
                    var srcRow = rowStride * row
                    if (buffer.remaining() < rowStride * height - srcRow - pixelStride * width) break
                    for (col in 0 until width) {
                        val pos = (srcRow + pixelStride * col).toInt()
                        if (pos + 3 >= buffer.capacity()) break
                        val r8 = buffer.get(pos).toInt() and 0xFF
                        val g8 = buffer.get(pos + 1).toInt() and 0xFF
                        val b8 = buffer.get(pos + 2).toInt() and 0xFF
                        val a8 = buffer.get(pos + 3).toInt() and 0xFF
                        px[dst++] = (a8 shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                    }
                }
                if (dst < px.size) {
                    bitmap.eraseColor(0xFF000000.toInt())
                    bitmap.setPixels(px, 0, width, 0, 0, width, dst / width.coerceAtLeast(1))
                } else {
                    bitmap.setPixels(px, 0, width, 0, 0, width, height)
                }
                outDir.mkdirs()
                // 滚动保留最近 8 张，防 blobs 目录膨胀
                outDir.listFiles()?.sortedBy { it.name }?.dropLast(7)?.forEach { it.delete() }
                val name = "shot-${System.currentTimeMillis()}.jpg"
                File(outDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                bitmap.recycle()
                return name
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            return null
        } finally {
            capturing.set(false)
        }
    }

    fun stop() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
    }

    companion object {
        private const val TAG = "ScreenshotService"

        /** 主界面授权回调需要访问同一实例（BodyCore 创建时挂上） */
        @Volatile
        var shared: ScreenshotService? = null
    }
}
