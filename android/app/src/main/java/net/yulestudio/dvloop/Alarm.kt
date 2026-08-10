package net.yulestudio.dvloop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 루프가 끝났을 때 알린다.
 *
 * 밤새 돌려두고 자는 동안, 또는 폰을 두고 다른 일을 하는 동안 끝나면
 * 알 길이 없다. 목표를 이뤘든 중간에 막혔든 사람이 알아야 한다.
 *
 * 모든 루프가 이 하나를 쓴다. 레벨업·탐험·미션이 각자 알림을 만들면
 * 소리도 문구도 제각각이 되고, 고칠 때 한 곳을 빠뜨린다.
 */
object Alarm {

    private const val CHANNEL = "dvloop_done"
    private const val NOTI_ID = 2001

    /** 끝난 이유. 소리와 문구가 갈린다. */
    enum class Reason { DONE, STOPPED, FAILED }

    /**
     * @param title 무엇이 끝났는지 (예: "레벨업 루프")
     * @param detail 한 줄 요약 (예: "고대신룡 Lv.40 달성")
     */
    fun finished(ctx: Context, reason: Reason, title: String, detail: String) {
        notify(ctx, reason, title, detail)
        vibrate(ctx, reason)
    }

    private fun notify(ctx: Context, reason: Reason, title: String, detail: String) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr.getNotificationChannel(CHANNEL) == null
        ) {
            // 진행 알림과 다른 채널을 쓴다. 진행 알림은 조용해야 하고
            // 끝났다는 알림은 들려야 한다.
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "루프 종료", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        enableVibration(true)
                        setSound(
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                            null,
                        )
                    }
            )
        }

        val open = PendingIntent.getActivity(
            ctx, 0, android.content.Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mark = when (reason) {
            Reason.DONE -> "완료"
            Reason.STOPPED -> "정지"
            Reason.FAILED -> "멈춤"
        }
        mgr.notify(
            NOTI_ID,
            Notification.Builder(ctx, CHANNEL)
                .setContentTitle("$title — $mark")
                .setContentText(detail)
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * 진동. 이유마다 다르게 울려 화면을 안 봐도 구분된다.
     *   완료  짧게 두 번
     *   정지  짧게 한 번
     *   멈춤  길게 세 번 (문제가 생겼다는 뜻이라 확실히 알아야 한다)
     */
    private fun vibrate(ctx: Context, reason: Reason) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        } ?: return

        val pattern = when (reason) {
            Reason.DONE -> longArrayOf(0, 180, 120, 180)
            Reason.STOPPED -> longArrayOf(0, 150)
            Reason.FAILED -> longArrayOf(0, 400, 200, 400, 200, 400)
        }
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
