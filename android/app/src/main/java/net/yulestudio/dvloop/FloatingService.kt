package net.yulestudio.dvloop

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 화면 위에 떠 있는 미니 제어창 (카카오톡 미니창과 같은 방식).
 *
 * 루프가 도는 동안 게임이 화면을 차지하므로, 앱으로 돌아가지 않고도
 * 진행 상황을 보고 멈출 수 있어야 한다.
 *
 * 접힌 상태: 현재 레벨만 보여주는 작은 버블. 드래그로 옮길 수 있다.
 * 펼친 상태: 대상 · 레벨 · 사이클 · 성공률 + [정지] [닫기]
 *
 * 오버레이 권한(다른 앱 위에 표시)이 필요하다.
 */
class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var bubbleText: TextView
    private lateinit var panel: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var statText: TextView

    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildView()
        addToWindow()
        render(LoopStatus.current)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.hasExtra(EXTRA_STATUS)) render(LoopStatus.current)
        }
        return START_STICKY
    }

    // ---------- 뷰 구성 ----------

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun buildView() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#EE191F28"))
            }
        }

        bubbleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = "루프"
        }
        root.addView(bubbleText)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        titleText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
        }
        statText = TextView(this).apply {
            setTextColor(Color.parseColor("#8B95A1")); textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        }
        panel.addView(titleText)
        panel.addView(statText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("정지", "#F04452") {
            LoopStatus.requestStop()
            render(LoopStatus.current)
        })
        row.addView(actionButton("닫기", "#333D4B") { stopSelf() })
        panel.addView(row)

        root.addView(panel)
        root.setOnClickListener { toggle() }
        root.setOnTouchListener(DragHandler())
    }

    private fun actionButton(label: String, color: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(color))
            }
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(38))
                .apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun addToWindow() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        wm.addView(root, params)
    }

    private fun toggle() {
        expanded = !expanded
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        render(LoopStatus.current)
    }

    private fun render(s: LoopStatus.Snapshot) {
        bubbleText.text = if (s.running) "Lv.${s.level}" else "정지됨"
        titleText.text = "${s.target} · Lv.${s.level} → ${s.targetLevel}"
        statText.text = "${s.cycle}회 시도 · 성공 ${s.wins}회" +
            if (s.cycle > 0) " · ${s.wins * 100 / s.cycle}%" else ""
    }

    /** 버블을 손가락으로 끌어 옮긴다. 살짝 눌렀다 뗀 것은 탭으로 본다. */
    private inner class DragHandler : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> if (!moved) v.performClick()
            }
            return true
        }
    }

    override fun onDestroy() {
        if (::root.isInitialized) runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STATUS = "status"

        fun start(ctx: Context) =
            ctx.startService(Intent(ctx, FloatingService::class.java))

        fun refresh(ctx: Context) = ctx.startService(
            Intent(ctx, FloatingService::class.java).putExtra(EXTRA_STATUS, true)
        )

        fun stop(ctx: Context) =
            ctx.stopService(Intent(ctx, FloatingService::class.java))
    }
}

/** 루프 상태를 미니창과 앱이 함께 본다. 실제 루프 엔진이 붙으면 여기를 갱신한다. */
object LoopStatus {
    data class Snapshot(
        val running: Boolean = false,
        val target: String = "대상 없음",
        val level: Int = 0,
        val targetLevel: Int = 45,
        val cycle: Int = 0,
        val wins: Int = 0,
    )

    @Volatile
    var current = Snapshot()
        private set

    @Volatile
    var stopRequested = false
        private set

    fun update(s: Snapshot) {
        current = s
    }

    fun requestStop() {
        stopRequested = true
        current = current.copy(running = false)
    }

    fun clearStop() {
        stopRequested = false
    }
}
