package net.yulestudio.dvloop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * 화면 캡처. 파이썬의 `adb exec-out screencap` 자리를 대신한다.
 *
 * MediaProjection 은 사용자가 매번 허용해야 한다. 허용받은 Intent 를 들고 있다가
 * 루프가 도는 동안 계속 쓴다.
 *
 * 캡처한 화면은 판정에만 쓰이고 밖으로 나가지 않는다.
 */
object ScreenCapture {

    private const val TAG = "DvCapture"

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    private var width = 0
    private var height = 0
    private var density = 0

    val isReady: Boolean get() = reader != null

    /** 사용자에게 화면 캡처를 허용받기 위한 Intent */
    fun permissionIntent(ctx: Context): Intent =
        (ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .createScreenCaptureIntent()

    /** 허용 결과로 캡처를 연다. 포그라운드 서비스가 떠 있는 상태에서 불러야 한다. */
    fun start(ctx: Context, resultCode: Int, data: Intent): Boolean {
        stop()
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val p = mgr.getMediaProjection(resultCode, data) ?: run {
            Log.e(TAG, "MediaProjection 을 얻지 못했다")
            return false
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        // 게임은 항상 가로다. 캡처면도 가로로 만든다.
        // 앱이 세로 고정이라 이 시점 화면은 세로인데, 그대로 만들면
        // 게임이 세로 캔버스에 레터박스로 들어가 판정 좌표가 전부 어긋난다.
        width = maxOf(metrics.widthPixels, metrics.heightPixels)
        height = minOf(metrics.widthPixels, metrics.heightPixels)
        density = metrics.densityDpi

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection 종료됨")
                stop()
            }
        }, Handler(Looper.getMainLooper()))

        val r = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        display = p.createVirtualDisplay(
            "dvloop-capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null,
        )
        projection = p
        reader = r
        Log.i(TAG, "캡처 시작 ${width}x$height")
        return true
    }

    fun stop() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
    }

    /**
     * 최신 화면 한 장. 아직 준비되지 않았으면 null.
     *
     * 게임은 가로이므로 캡처도 가로로 나온다. Judge 는 2340x1080 기준이라
     * 다른 해상도면 그쪽에서 비율로 환산한다.
     */
    fun frame(): Bitmap? {
        val r = reader ?: return null
        val image = r.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val padding = rowStride - pixelStride * image.width

            val bmp = Bitmap.createBitmap(
                image.width + padding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (padding == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            Log.e(TAG, "프레임 변환 실패", e)
            null
        } finally {
            image.close()
        }
    }
}
