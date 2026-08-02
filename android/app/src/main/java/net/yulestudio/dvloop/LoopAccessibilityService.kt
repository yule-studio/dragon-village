package net.yulestudio.dvloop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 게임과 설정 앱을 대신 조작하는 서비스.
 *
 * 게임은 그림이라 좌표로 누른다.
 * 설정 앱은 기종·OS 마다 배치가 달라 **버튼 글자로 찾아** 누른다.
 *
 * dvguard.py 의 안전장치를 그대로 옮겨왔다:
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

    // ---------- 좌표 조작 (게임) ----------

    /** @return 실제로 탭했으면 true, 금지구역이라 거부했으면 false */
    suspend fun tap(x: Int, y: Int): Boolean {
        if (inDangerZone(x, y)) {
            Log.w(TAG, "거부: ($x,$y) 는 계정삭제 금지구역")
            return false
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        )
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 300): Boolean {
        if (inDangerZone(x2, y2)) {
            Log.w(TAG, "거부: 스와이프 도착점 ($x2,$y2) 이 금지구역")
            return false
        }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        )
    }

    private suspend fun dispatch(g: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(d: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }

    // ---------- 글자로 찾아 누르기 (설정 앱) ----------

    /**
     * 화면에서 글자가 들어간 누를 수 있는 항목을 찾아 누른다.
     * 설정 앱은 기종마다 배치가 달라 좌표로는 못 맞춘다.
     */
    fun clickByText(vararg candidates: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            for (text in candidates) {
                val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
                for (n in nodes) {
                    if (clickable(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        Log.i(TAG, "눌렀다: $text")
                        return true
                    }
                }
            }
        } finally {
            runCatching { root.recycle() }
        }
        return false
    }

    /** 그 노드를 못 누르면 위로 올라가며 누를 수 있는 조상을 찾는다. */
    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.isEnabled) return n
            n = n.parent
            depth++
        }
        return null
    }

    fun hasText(vararg candidates: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            return candidates.any { !root.findAccessibilityNodeInfosByText(it).isNullOrEmpty() }
        } finally {
            runCatching { root.recycle() }
        }
    }

    /** 글자가 나타날 때까지 기다린다. */
    suspend fun waitForText(vararg candidates: String, timeoutMs: Long = 6000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (hasText(*candidates)) return true
            delay(250)
        }
        return false
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * 글자를 찾을 때까지 아래로 스크롤한다.
     * 설정 앱의 '저장공간' 은 한 화면에 안 들어와 스크롤해야 보인다.
     */
    suspend fun scrollUntilText(vararg candidates: String, maxScrolls: Int = 6): Boolean {
        repeat(maxScrolls) {
            if (hasText(*candidates)) return true
            val root = rootInActiveWindow ?: return false
            val scrolled = try {
                findScrollable(root)?.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ) ?: false
            } finally {
                runCatching { root.recycle() }
            }
            if (!scrolled) return hasText(*candidates)
            delay(900)
        }
        return hasText(*candidates)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** 지금 앞에 있는 창의 패키지. 게임이 아닌 것이 올라오면 방해물이다. */
    fun foregroundPackage(): String? =
        rootInActiveWindow?.let { r ->
            try { r.packageName?.toString() } finally { runCatching { r.recycle() } }
        }

    /**
     * 게임 밖 시스템 대화상자를 닫는다.
     * 재난문자 · 전화 수신 · 앱 알림 등 루프 도중 언제든 끼어든다.
     *
     * 확인/닫기류 버튼을 글자로 찾아 누르고, 못 찾으면 뒤로가기로 물러난다.
     * 전화는 받지도 끊지도 않는다 — 사람이 판단할 일이다.
     */
    fun dismissSystemDialog(): Boolean {
        if (clickByText("확인", "닫기", "OK", "Close", "취소", "나중에", "무시")) return true
        return performGlobalAction(GLOBAL_ACTION_BACK)
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

        fun inDangerZone(x: Int, y: Int): Boolean = x in DX1..DX2 && y in DY1..DY2
    }
}
