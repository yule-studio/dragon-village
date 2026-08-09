package net.yulestudio.dvloop

import android.content.Context
import java.util.Calendar

/**
 * 미션 루프가 오늘 무엇을 했는지 기록한다.
 *
 * 같은 날 두 번 돌려도 이미 한 것은 건너뛰고, 무엇을 보냈는지 남는다.
 * 정기는 요일마다 다른 속성을 보낸다 — 같은 것만 계속 보내면 받는 쪽이 쓸모없다.
 */
class MissionPrefs(ctx: Context) {

    private val p = ctx.getSharedPreferences("dv_mission", Context.MODE_PRIVATE)

    /** 오늘 날짜 도장 (yyyyMMdd). 날이 바뀌면 기록을 새로 센다. */
    private fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 +
            (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * 오늘 보낼 정기의 자리 (0~5).
     *
     * 월 0 · 화 1 · 수 2 · 목 3 · 금 4 · 토 5 · 일 0.
     * 요일로 정하므로 기기가 바뀌어도 같은 날이면 같은 것을 보낸다.
     */
    fun giftSlotForToday(): Int {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)   // 일=1 … 토=7
        return ((dow + 5) % 7) % 6
    }

    fun done(task: String): Boolean = p.getInt(key(task), 0) == today()

    fun markDone(task: String) = p.edit().putInt(key(task), today()).apply()

    /** 오늘 보낸 정기 자리. 기록해 두고 로그와 화면에 보여준다. */
    var sentSlot: Int
        get() = if (p.getInt("gift_date", 0) == today()) p.getInt("gift_slot", -1) else -1
        set(v) = p.edit().putInt("gift_date", today()).putInt("gift_slot", v).apply()

    private fun key(task: String) = "done_$task"

    companion object {
        const val GIFT_SEND = "gift_send"
        const val GIFT_RECV = "gift_recv"
        const val ATTENDANCE = "attendance"
        const val TREASURE = "treasure"
        const val QUEST_CLAIM = "quest_claim"

        /** 정기 속성 이름. 화면의 육각형 순서와 같다. */
        val GIFT_NAMES = arrayOf("땅", "물", "불", "풀", "빛", "어둠")
    }
}
