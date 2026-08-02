package net.yulestudio.dvloop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 게임 화면을 대신 눌러주는 서비스.
 *
 * 파이썬 쪽 `dvguard.py` 의 안전장치를 그대로 옮겨온다:
 * 계정삭제 금지구역 좌표는 어떤 경우에도 탭하지 않는다.
 */
class LoopAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "접근성 서비스 연결됨")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** @return 실제로 탭했으면 true, 금지구역이라 거부했으면 false */
    fun tap(x: Int, y: Int, durationMs: Long = 50): Boolean {
        if (inDangerZone(x, y)) {
            Log.w(TAG, "거부: ($x,$y) 는 계정삭제 금지구역")
            return false
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        if (inDangerZone(x2, y2)) {
            Log.w(TAG, "거부: 스와이프 도착점 ($x2,$y2) 이 금지구역")
            return false
        }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }

    companion object {
        private const val TAG = "DvLoopA11y"

        @Volatile
        var instance: LoopAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        /**
         * 계정삭제 금지구역 (2340x1080 가로 기준).
         * 설정창에서 [저장][계정삭제][로그아웃] 이 나란히 붙어 있어 오탭 시 계정이 소멸한다.
         * dvguard.py 의 DANGER 와 동일한 값이다.
         */
        private const val DX1 = 1030
        private const val DY1 = 845
        private const val DX2 = 1300
        private const val DY2 = 935

        fun inDangerZone(x: Int, y: Int): Boolean =
            x in DX1..DX2 && y in DY1..DY2
    }
}
