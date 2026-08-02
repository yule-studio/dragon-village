package net.yulestudio.dvloop

import android.graphics.Bitmap

/**
 * 화면 판정. dvloop.py 의 판정 로직을 그대로 옮긴 것이다.
 *
 * 좌표는 모두 **가로 2340 x 1080** 기준이다. 다른 해상도는 비율로 환산한다.
 *
 * 밝기로 보면 안 된다 — 뱃지가 없을 때 그 자리에 무엇이 오는지가 드래곤마다 다르다.
 * 스탯 숫자가 크면 어두운 박스가 덮고, 작으면 밝은 베이지 배경이 온다.
 * 실제로 그 때문에 루아셰아 초반 판정이 전부 오탐이었다.
 */
object Judge {

    const val BASE_W = 2340
    const val BASE_H = 1080

    // --- 판정 지점 (가로 기준) ---
    private const val BADGE_X = 1681
    val BADGE_Y = mapOf("HP" to 441, "ATK" to 532, "DEF" to 624)
    private val GRADE = 696 to 632

    private val LEVELUP_DIALOG = 1350 to 915     // 노란 '레벨 업' 버튼
    private val RESULT_OK = 1170 to 827          // 결과창 파란 '확인'
    private val NOTICE_L = 600 to 1013           // 공지 하단 바 (좌)
    private val NOTICE_R = 1800 to 1013          // 공지 하단 바 (우)
    private val LOGIN_GOOGLE = 470 to 386
    private val LOGIN_HIGHBROW = 470 to 759
    private val ID_FIELD = 1170 to 461
    private val PW_FIELD = 1170 to 580
    private val TITLE_ROW_Y = 985                // '이전 드빌아이디' 버튼
    private val TITLE_XS = intArrayOf(1750, 1790, 1900, 1940)

    /** 캡처 해상도가 기준과 다르면 비율로 옮긴다. */
    private fun px(bmp: Bitmap, x: Int, y: Int): Int {
        val sx = (x.toLong() * bmp.width / BASE_W).toInt().coerceIn(0, bmp.width - 1)
        val sy = (y.toLong() * bmp.height / BASE_H).toInt().coerceIn(0, bmp.height - 1)
        return bmp.getPixel(sx, sy)
    }

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    // ---------- MAX 뱃지 ----------

    /**
     * 뱃지는 주황 폭죽이고, 위아래 끝(dy=±24)이 가장 진한 주황이다.
     * 가운데는 흰 'MAX' 글자라 색으로 판정할 수 없다.
     * 실측: 뱃지 없음 0/6, 있음 6/6 으로 완전히 갈린다.
     */
    fun badgeMax(bmp: Bitmap, y: Int): Boolean {
        var hits = 0
        for (dy in intArrayOf(-24, 24)) {
            for (dx in intArrayOf(-15, 0, 15)) {
                val c = px(bmp, BADGE_X + dx, y + dy)
                if (r(c) > 200 && b(c) < 130 && (r(c) - b(c)) > 110) hits++
            }
        }
        return hits >= 4
    }

    /** 세 스탯의 MAX 여부 */
    fun badges(bmp: Bitmap): Map<String, Boolean> =
        BADGE_Y.mapValues { (_, y) -> badgeMax(bmp, y) }

    // ---------- 등급 ----------

    /**
     * 등급 육각형이 7.0 인지.
     * 실측: 7.0 = 시안 (8,219,255) / 7.0 미만 = 금색 (255,202,49).
     * 가운데는 흰 숫자라 여러 점을 보고 다수결로 판정한다.
     *
     * 스탯 뱃지와 달리 이 값은 '실제 조건' 그 자체다. 최종 판단 기준으로 쓴다.
     * null = 판정 불가.
     */
    fun gradeIs70(bmp: Bitmap): Boolean? {
        var blue = 0
        var gold = 0
        for ((dx, dy) in listOf(-14 to 0, 14 to 0, 0 to -14, 0 to 14, 0 to 0)) {
            val c = px(bmp, GRADE.first + dx, GRADE.second + dy)
            if (b(c) > r(c) + 40 && b(c) > 120) blue++
            else if (r(c) > 180 && r(c) > b(c) + 60) gold++
        }
        return when {
            blue >= 2 && gold == 0 -> true
            gold >= 2 && blue == 0 -> false
            else -> null
        }
    }

    // ---------- 화면 식별 ----------

    /** 레벨업 다이얼로그 (노란 '레벨 업' 버튼) */
    fun isLevelUpDialog(bmp: Bitmap): Boolean {
        val c = px(bmp, LEVELUP_DIALOG.first, LEVELUP_DIALOG.second)
        return r(c) > 230 && g(c) in 151..209 && b(c) < 60
    }

    /** LEVEL UP! 결과창 (파란 확인 버튼) */
    fun isResultPopup(bmp: Bitmap): Boolean {
        val c = px(bmp, RESULT_OK.first, RESULT_OK.second)
        return b(c) > r(c) + 40 && b(c) > 90
    }

    /** 로그인 후 공지창 (하단 어두운 닫기 바) */
    fun isNotice(bmp: Bitmap): Boolean {
        fun dark(p: Pair<Int, Int>): Boolean {
            val c = px(bmp, p.first, p.second)
            return r(c) + g(c) + b(c) < 260
        }
        return dark(NOTICE_L) && dark(NOTICE_R)
    }

    /**
     * 타이틀 화면(TOUCH TO START).
     * '이전 드빌아이디' 버튼은 넓고 균일한 진한 파랑이다.
     * 월드맵의 에스텔라 지역도 그 자리가 파랗지만 그림이라 색이 고르지 않다.
     * → 색상 조건 + 균일도를 함께 본다.
     */
    fun isTitle(bmp: Bitmap): Boolean {
        val pts = TITLE_XS.map { px(bmp, it, TITLE_ROW_Y) }
        if (pts.any { !(b(it) > 100 && b(it) > r(it) + 50 && r(it) < 100) }) return false
        listOf(::r, ::g, ::b).forEach { ch ->
            val vals = pts.map { ch(it) }
            if ((vals.max() - vals.min()) > 14) return false
        }
        return true
    }

    /** 구글/페이스북/하이브로 선택 화면 */
    fun isLoginSelect(bmp: Bitmap): Boolean {
        val gc = px(bmp, LOGIN_GOOGLE.first, LOGIN_GOOGLE.second)
        val hc = px(bmp, LOGIN_HIGHBROW.first, LOGIN_HIGHBROW.second)
        val google = r(gc) > 150 && r(gc) > g(gc) + 15
        val highbrow = kotlin.math.abs(r(hc) - g(hc)) < 30 &&
            kotlin.math.abs(g(hc) - b(hc)) < 30 && r(hc) in 71..209
        return google && highbrow
    }

    /**
     * 게임 설정창인지. 저장=초록 / 계정삭제=빨강 이 둘 다 맞아야 인정한다.
     * dvguard.py 의 is_settings_open 과 같은 검사다.
     * 저장은 이 검사를 통과했을 때만 누른다.
     */
    fun isSettingsOpen(bmp: Bitmap): Boolean {
        val s = px(bmp, 896, 890)   // 저장 (초록)
        val d = px(bmp, 1166, 890)  // 계정삭제 (빨강)
        val greenOk = g(s) > 100 && g(s) > r(s) + 30 && g(s) > b(s) + 30
        val redOk = r(d) > 140 && r(d) > g(d) + 60 && r(d) > b(d) + 60
        return greenOk && redOk
    }

    /** 하이브로 아이디/비밀번호 입력 폼 (흰 입력칸 2개) */
    fun isIdForm(bmp: Bitmap): Boolean =
        listOf(ID_FIELD, PW_FIELD).all { p ->
            val c = px(bmp, p.first, p.second)
            minOf(r(c), g(c), b(c)) > 215
        }
}
