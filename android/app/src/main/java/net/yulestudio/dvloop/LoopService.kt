package net.yulestudio.dvloop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 루프를 실제로 돌리는 포그라운드 서비스.
 *
 * MediaProjection 은 포그라운드 서비스가 떠 있어야만 시작할 수 있어서,
 * 화면 캡처도 이 서비스가 연다.
 *
 * 로그는 메모리에 쌓아 앱에서 볼 수 있게 한다.
 */
class LoopService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * 루프가 도는 동안 화면을 켜 둔다.
     *
     * 접근성 서비스가 보내는 탭은 시스템이 '사용자 조작' 으로 치지 않아서
     * 절전 타이머가 계속 돌아간다. 미니창의 FLAG_KEEP_SCREEN_ON 만으로는
     * 미니창이 잠깐이라도 내려간 사이에 화면이 꺼진다 — 콜로세움처럼
     * 몇 분씩 기다리는 구간에서 실제로 그렇게 잠겼다.
     * 화면이 꺼지면 잠금 화면이 오버레이를 가리고 캡처도 무의미해진다.
     */
    private var wake: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    private fun holdScreen() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "dvloop:screen",
        ).apply { setReferenceCounted(false); acquire() }
        log("화면 켜 둠 (루프가 도는 동안)")
    }

    private fun releaseScreen() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log("정지 요청 — 루프와 화면 공유를 멈춘다")
            stopEverything()
            return START_NOT_STICKY
        }

        startForeground(NOTI_ID, notification())

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val setup = pendingSetup

        if (data == null || setup == null) {
            log("시작 정보가 없다. 중단한다.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ScreenCapture.start(this, code, data)) {
            log("화면 캡처를 열지 못했다.")
            stopSelf()
            return START_NOT_STICKY
        }

        val firstSetup: LoopSetup = setup
        holdScreen()
        job?.cancel()
        job = scope.launch {
            // 한 마리를 끝내면 대기열의 다음 드래곤으로 잇는다.
            // '고대신룡 끝나면 빙하고룡, 그다음 파워드래곤' 처럼 밤새 이어 돌리기 위해서다.
            val prefs = LoopPrefs(applicationContext)
            val repo = DexRepository(applicationContext)
            var current: LoopSetup = firstSetup

            while (true) {
                val engine = LoopEngine(
                    applicationContext, current, Credentials(applicationContext),
                ) { log(it) }
                runCatching { engine.run() }
                    .onFailure { log("!! 예외: ${it.message}") }

                if (!engine.reachedTarget || LoopStatus.stopRequested) break

                val next = prefs.popQueue(repo)
                if (next == null) { log("대기열이 비었다 — 모두 끝났다"); break }

                log("다음 대상으로 넘어간다: ${next.dragon?.name} ${next.stage?.label} → Lv.${next.targetLevel}")
                prefs.save(next)
                prefs.preparedNo = 0
                current = next
                LoopStatus.clearStop()
                LoopStatus.update(
                    LoopStatus.Snapshot(
                        running = true,
                        target = next.dragon?.name ?: "대상 없음",
                        stage = next.stage?.label ?: "-",
                        level = next.currentLevel,
                        targetLevel = next.targetLevel,
                    )
                )
                FloatingService.refresh(applicationContext)
            }
            releaseScreen()
            ScreenCapture.stop()
            // phase 를 남겨두면 멈춘 뒤에도 '데삭 중' 처럼 보인다.
            LoopStatus.update(LoopStatus.current.copy(running = false, phase = "idle"))
            FloatingService.refresh(applicationContext)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val ch = "dvloop"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(ch) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(ch, "루프 진행", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 알림에서도 멈출 수 있어야 한다. 미니창이 안 보이거나 게임이 화면을
        // 다 덮은 상황에서 멈출 길이 없으면 손을 못 쓴다.
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LoopService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, ch)
            .setContentTitle("드빌 매크로")
            .setContentText("루프가 돌고 있어요")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "정지", stop)
                    .build()
            )
            .setOngoing(true)
            .build()
    }

    /**
     * 태스크가 정리됐을 때.
     *
     * 사용자가 앱을 밀어 닫은 것이면 멈춰야 한다. 여기서 멈추지 않으면 서비스가
     * 그대로 살아 화면 공유가 켜진 채로 남고 루프가 드빌을 계속 다시 띄운다.
     *
     * 그런데 이게 불리는 상황이 그것만이 아니다. 데삭은 설정 앱을 CLEAR_TASK 로
     * 띄워 게임 데이터를 지우는데, 그때도 여기가 불려 루프가 통째로 멈췄다.
     * 사용자가 닫은 것과 루프가 제 일을 하느라 나간 것은 완전히 다르다.
     *
     * 두 가지로 가른다 — 정리된 태스크가 우리 앱의 것인지, 그리고 지금 루프가
     * 일부러 앱 밖에 나가 있는 중인지.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val pkg = rootIntent?.component?.packageName ?: rootIntent?.`package`
        if (pkg != null && pkg != packageName) {
            log("다른 앱의 태스크가 정리됐다 ($pkg) — 루프는 계속한다")
            super.onTaskRemoved(rootIntent); return
        }
        if (LoopStatus.leavingOnPurpose) {
            log("루프가 앱 밖에 나가 있는 중이다 (데삭 등) — 계속한다")
            super.onTaskRemoved(rootIntent); return
        }
        log("앱이 닫혔다 — 루프와 화면 공유를 멈춘다")
        stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    /** 루프 · 화면 공유 · 미니창을 한꺼번에 내린다. 어디서 멈추든 같은 길을 쓴다. */
    private fun stopEverything() {
        releaseScreen()
        LoopStatus.requestStop()
        job?.cancel()
        ScreenCapture.stop()
        FloatingService.stop(applicationContext)
        stopSelf()
    }

    companion object {
        private const val NOTI_ID = 1001
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"

        /** 서비스는 Intent 로 넘기기 어려운 값이 있어 여기에 둔다. */
        @Volatile
        var pendingSetup: LoopSetup? = null

        private val logs = ArrayDeque<String>()

        fun log(line: String) {
            Log.i("DvLoop", line)
            synchronized(logs) {
                logs.addLast(line)
                while (logs.size > 200) logs.removeFirst()
            }
        }

        fun recentLogs(): List<String> = synchronized(logs) { logs.toList() }

        fun start(ctx: Context, setup: LoopSetup, resultCode: Int, data: Intent) {
            pendingSetup = setup
            val i = Intent(ctx, LoopService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LoopService::class.java).setAction(ACTION_STOP))
        }
    }
}
