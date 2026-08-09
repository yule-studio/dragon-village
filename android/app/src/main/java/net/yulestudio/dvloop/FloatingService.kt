package net.yulestudio.dvloop

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 화면 위에 떠 있는 미니 제어창. 카카오톡 미니창과 같은 조작을 따른다.
 *
 *   접힘    동그란 버블. 끌어서 옮긴다.
 *   끄는 중  화면 아래에 '✕ 닫기' 가 나타나고, 그 위에 놓으면 사라진다.
 *   펼침    카드 창. 머리줄에 [—] 접기 · [⤢] 앱으로 · [✕] 닫기
 *
 * 루프가 도는 동안 게임이 화면을 차지하므로,
 * 앱으로 돌아가지 않고도 진행 상황을 보고 멈출 수 있어야 한다.
 */
class FloatingService : Service() {

    private lateinit var wm: WindowManager

    private lateinit var bubble: LinearLayout
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var card: LinearLayout
    private lateinit var cardParams: WindowManager.LayoutParams
    private lateinit var closeZone: TextView
    private lateinit var closeZoneParams: WindowManager.LayoutParams

    private lateinit var bubbleLabel: TextView
    private lateinit var titleText: TextView
    private lateinit var statText: TextView
    private lateinit var primaryButton: TextView

    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildBubble()
        buildCard()
        buildCloseZone()
        render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        render()
        return START_STICKY
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /**
     * 미니창의 창 속성.
     *
     * FLAG_KEEP_SCREEN_ON 이 핵심이다. 접근성 서비스가 보내는 탭은 시스템이
     * '사용자 조작' 으로 치지 않아서, 루프가 아무리 화면을 눌러도 절전 타이머는
     * 계속 돌아간다. 시간이 되면 화면이 꺼지고 잠금 화면이 올라오는데,
     * 그러면 미니창은 가려지고 화면 캡처도 무의미해진다.
     * 미니창이 떠 있는 동안은 화면을 켜둔다 — 루프가 도는 동안이 곧 그 시간이다.
     */
    private fun params(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---------- 버블 (접힘) ----------

    /**
     * 접힌 버블.
     *
     * 예전에는 원 안에 작은 네모 아이콘을 넣고 그 아래 레벨을 적었다.
     * 원 안에 네모가 떠 있는 꼴이라 어중간하고, 아이콘도 작아 무엇인지 잘 안 보였다.
     * 그림이 원을 꽉 채우게 하고 글자는 뺐다 — 레벨은 펼친 카드에서 보면 된다.
     */
    private fun buildBubble() {
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            // 원 밖으로 삐져나온 모서리를 잘라낸다
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setOval(0, 0, v.width, v.height)
                }
            }
        }
        // 카드 쪽에서 쓰는 이름이라 남겨둔다. 버블에는 붙이지 않는다.
        bubbleLabel = TextView(this)
        bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F2191F28"))
            }
            addView(icon)
            setOnClickListener { expand() }
            setOnTouchListener(BubbleDrag())
        }
        bubbleParams = params(dp(58), dp(58)).apply { x = dp(16); y = dp(160) }
        wm.addView(bubble, bubbleParams)
    }

    // ---------- 카드 (펼침) ----------

    private fun buildCard() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(2))
            addView(headerButton("—") { collapse() })
            addView(headerButton("⤢") { openApp() })
            addView(headerButton("✕") { stopSelf() })
            setOnTouchListener(CardDrag())
        }

        titleText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(14), 0, dp(14), 0)
        }
        statText = TextView(this).apply {
            setTextColor(Color.parseColor("#8B95A1")); textSize = 12f
            setPadding(dp(14), dp(4), dp(14), dp(12))
        }

        // 왼쪽 버튼은 상태에 따라 바뀐다: 돌고 있으면 [정지], 멈췄으면 [재시작].
        // 멈춘 뒤에 [정지] 만 남아 있으면 미니창에서 할 수 있는 게 없다.
        primaryButton = actionButton("정지", "#F04452") {
            if (LoopStatus.current.running) LoopStatus.requestStop() else LoopStatus.requestResume()
            if (!LoopStatus.current.running && LoopStatus.resumeRequested) openApp() else render()
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(4), dp(14))
            addView(primaryButton)
            addView(actionButton("앱으로", "#3182F6") { openApp() })
        }

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F5191F28"))
            }
            visibility = View.GONE
            addView(header)
            addView(titleText)
            addView(statText)
            addView(actions)
        }
        cardParams = params(dp(250), WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { x = dp(16); y = dp(160) }
        wm.addView(card, cardParams)
    }

    private fun headerButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#C9CFD6"))
        textSize = 15f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(30))
        setOnClickListener { onClick() }
    }

    private fun actionButton(label: String, color: String, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(color))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                .apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    // ---------- 닫기 영역 ----------

    private fun buildCloseZone() {
        closeZone = TextView(this).apply {
            text = "✕   닫기"
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#E6191F28"))
            }
            visibility = View.GONE
        }
        closeZoneParams = params(dp(172), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }
        wm.addView(closeZone, closeZoneParams)
    }

    private fun overCloseZone(): Boolean {
        val loc = IntArray(2)
        closeZone.getLocationOnScreen(loc)
        val cx = loc[0] + closeZone.width / 2f
        val cy = loc[1] + closeZone.height / 2f
        val bx = bubbleParams.x + bubble.width / 2f
        val by = bubbleParams.y + bubble.height / 2f
        return hypot(bx - cx, by - cy) < dp(120)
    }

    // ---------- 상태 전환 ----------

    private fun expand() {
        expanded = true
        cardParams.x = bubbleParams.x
        cardParams.y = bubbleParams.y
        wm.updateViewLayout(card, cardParams)
        bubble.visibility = View.GONE
        card.visibility = View.VISIBLE
        render()
    }

    private fun collapse() {
        expanded = false
        bubbleParams.x = cardParams.x
        bubbleParams.y = cardParams.y
        wm.updateViewLayout(bubble, bubbleParams)
        card.visibility = View.GONE
        bubble.visibility = View.VISIBLE
        render()
    }

    /**
     * 루프 앱으로 돌아간다.
     * 앱 자체가 메인 화면이므로 미니창은 사라진다.
     * 앱을 벗어나고 루프가 돌고 있으면 MainActivity 가 다시 띄운다.
     */
    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        )
        stopSelf()
    }

    private fun render() {
        val s = LoopStatus.current
        primaryButton.text = if (s.running) "정지" else "재시작"
        (primaryButton.background as GradientDrawable).setColor(
            Color.parseColor(if (s.running) "#F04452" else "#00A661")
        )
        bubbleLabel.text = if (s.running) "Lv.${s.level}" else "정지"
        titleText.text = "${s.target} · ${s.stage}"
        val phase = when (s.phase) {
            "levelup" -> "레벨업 중"
            "save" -> "저장 중"
            "desak" -> "데삭 중"
            "login" -> "로그인 중"
            "done" -> "목표 달성"
            else -> if (s.running) "진행 중" else "정지됨"
        }
        statText.text = "Lv.${s.level} → ${s.targetLevel}  ·  ${s.cycle}회 시도 · 성공 ${s.wins}회\n$phase"
    }

    // ---------- 드래그 ----------

    /** 버블: 끌면 닫기 영역이 나타나고, 그 위에서 놓으면 종료한다. */
    private inner class BubbleDrag : View.OnTouchListener {
        private var sx = 0; private var sy = 0
        private var tx = 0f; private var ty = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = bubbleParams.x; sy = bubbleParams.y
                    tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                        moved = true
                        closeZone.visibility = View.VISIBLE
                    }
                    bubbleParams.x = sx + dx
                    bubbleParams.y = sy + dy
                    wm.updateViewLayout(bubble, bubbleParams)
                    if (moved) closeZone.alpha = if (overCloseZone()) 1f else 0.7f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    closeZone.visibility = View.GONE
                    if (!moved) v.performClick()
                    else if (overCloseZone()) stopSelf()
                }
            }
            return true
        }
    }

    /** 카드: 머리줄을 잡고 옮긴다. */
    private inner class CardDrag : View.OnTouchListener {
        private var sx = 0; private var sy = 0
        private var tx = 0f; private var ty = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = cardParams.x; sy = cardParams.y
                    tx = e.rawX; ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    cardParams.x = sx + (e.rawX - tx).toInt()
                    cardParams.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(card, cardParams)
                }
            }
            return false   // 머리줄 버튼 탭은 그대로 살린다
        }
    }

    override fun onDestroy() {
        runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(card) }
        runCatching { wm.removeView(closeZone) }
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) = ctx.startService(Intent(ctx, FloatingService::class.java))
        fun refresh(ctx: Context) = ctx.startService(Intent(ctx, FloatingService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, FloatingService::class.java))
    }
}

/** 루프 상태를 미니창과 앱이 함께 본다. 실제 루프 엔진이 붙으면 여기를 갱신한다. */
object LoopStatus {
    data class Snapshot(
        val running: Boolean = false,
        val target: String = "대상 없음",
        val stage: String = "-",
        val level: Int = 0,
        val targetLevel: Int = 45,
        val cycle: Int = 0,
        val wins: Int = 0,
        val phase: String = "idle",
    )

    @Volatile
    var current = Snapshot()
        private set

    @Volatile
    var stopRequested = false
        private set

    /**
     * 미니창에서 누른 '재시작'.
     *
     * 화면 캡처 허용은 액티비티만 받을 수 있어서 여기서 바로 다시 돌릴 수 없다.
     * 깃발만 세워두고, 앱이 앞으로 나오면 그때 이어서 시작한다.
     */
    @Volatile
    var resumeRequested = false
        private set

    /**
     * 루프가 일부러 앱 밖으로 나가 있는 중인가.
     *
     * 데삭은 설정 앱을 거쳐 게임 데이터를 지우고 게임을 다시 띄운다. 그 사이
     * 태스크가 정리되면서 '앱이 닫혔다' 로 잘못 읽혀 루프가 통째로 멈췄다.
     * 사용자가 앱을 닫은 것과 루프가 제 일을 하느라 나간 것은 완전히 다르다.
     */
    @Volatile
    var leavingOnPurpose = false
        private set

    fun beginLeaving() { leavingOnPurpose = true }
    fun endLeaving() { leavingOnPurpose = false }

    fun update(s: Snapshot) {
        current = s
    }

    fun requestStop() {
        stopRequested = true
        // 멈췄는데 '데삭 중' 이 남아 있으면 아직 도는 것처럼 보인다.
        current = current.copy(running = false, phase = "idle")
    }

    fun requestResume() {
        resumeRequested = true
    }

    /** 한 번만 쓰고 내린다. */
    fun consumeResume(): Boolean {
        if (!resumeRequested) return false
        resumeRequested = false
        return true
    }

    fun clearStop() {
        stopRequested = false
    }
}
