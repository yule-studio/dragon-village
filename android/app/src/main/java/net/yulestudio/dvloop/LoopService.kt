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

        job?.cancel()
        job = scope.launch {
            val engine = LoopEngine(applicationContext, setup, Credentials(applicationContext)) {
                log(it)
            }
            runCatching { engine.run() }
                .onFailure { log("!! 예외: ${it.message}") }
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
     * 최근 앱 목록에서 앱을 밀어 닫았을 때.
     *
     * 여기서 멈추지 않으면 서비스가 그대로 살아 화면 공유가 켜진 채로 남고,
     * 루프가 드빌을 계속 다시 띄운다. 실제로 앱을 닫았는데도 게임이 자꾸
     * 올라와서 손을 못 쓰는 일이 있었다. 닫으면 닫힌 것이다.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
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
