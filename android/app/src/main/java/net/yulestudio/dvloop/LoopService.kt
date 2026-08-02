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
            LoopStatus.requestStop()
            stopSelf()
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
            LoopStatus.update(LoopStatus.current.copy(running = false))
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
        return Notification.Builder(this, ch)
            .setContentTitle("드빌 매크로")
            .setContentText("루프가 돌고 있어요")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        ScreenCapture.stop()
        super.onDestroy()
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
