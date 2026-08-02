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

    /**
     * 로그인 후 공지창의 하단 '닫기' 바.
     *
     * 어둡기만 보면 안 된다 — 월드맵 하단도 (33,16,16) 처럼 어두워서
     * 오탐이 났고, 같은 자리를 계속 누르다 다른 앱 설정까지 들어갔다.
     *
     * 공지 바는 폭 전체에 걸친 **균일한 회색 띠**다.
     * 회색(채널 차이가 작음) + 균일함(가로로 값이 거의 같음)을 함께 본다.
     */
    fun isNotice(bmp: Bitmap): Boolean {
        val pts = intArrayOf(400, 800, 1200, 1600, 1900).map { px(bmp, it, 1013) }
        for (c in pts) {
            val sum = r(c) + g(c) + b(c)
            if (sum >= 260) return false                       // 어두워야 한다
            if (maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c)) > 12) return false  // 회색이어야
        }
        // 가로로 값이 고른가 (그림이면 들쭉날쭉하다)
        val sums = pts.map { r(it) + g(it) + b(it) }
        return (sums.max() - sums.min()) <= 24
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
    /**
     * 구글/페이스북/하이브로 선택 화면.
     *
     * 버튼 y 위치가 화면마다 조금씩 달라진다(실측: 구글 390 · 페북 575 · 하이브로 760,
     * 다른 회차에는 386/573/759). 고정 좌표로 보면 놓친다.
     * x=470 세로줄을 훑어 주황 → 파랑 → 회색이 이 순서로 나타나는지 본다.
     */
    fun isLoginSelect(bmp: Bitmap): Boolean {
        var google = -1
        var facebook = -1
        var highbrow = -1
        var y = 280
        while (y <= 900) {
            val c = px(bmp, 470, y)
            when {
                r(c) > 150 && r(c) > g(c) + 30 && r(c) > b(c) + 30 ->
                    if (google < 0) google = y
                b(c) > 110 && b(c) > r(c) + 40 && b(c) > g(c) + 20 ->
                    if (facebook < 0 && google >= 0) facebook = y
                kotlin.math.abs(r(c) - g(c)) < 30 && kotlin.math.abs(g(c) - b(c)) < 30 &&
                    r(c) in 71..209 ->
                    if (highbrow < 0 && facebook >= 0) highbrow = y
            }
            y += 15
        }
        return google >= 0 && facebook > google && highbrow > facebook
    }

    /** 하이브로 버튼의 실제 y 좌표. 못 찾으면 null. */
    fun highbrowButtonY(bmp: Bitmap): Int? {
        var facebookSeen = false
        var y = 280
        var first = -1
        while (y <= 900) {
            val c = px(bmp, 470, y)
            if (b(c) > 110 && b(c) > r(c) + 40 && b(c) > g(c) + 20) facebookSeen = true
            else if (facebookSeen && kotlin.math.abs(r(c) - g(c)) < 30 &&
                kotlin.math.abs(g(c) - b(c)) < 30 && r(c) in 71..209
            ) {
                if (first < 0) first = y
            }
            y += 15
        }
        return if (first < 0) null else first + 45   // 블록 가운데쯤
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

    /**
     * 동굴 상세 화면인지. 좌측 목록 위의 'EVAL' 금색 버튼으로 판정한다.
     * 루프는 월드맵에서 시작한다고 가정하는데, 동굴에 남아 있으면
     * 용 패널 탭이 먹지 않아 다이얼로그가 안 열린다.
     */
    fun isCaveScreen(bmp: Bitmap): Boolean {
        var gold = 0
        for (y in 95..125 step 10) {
            for (x in 330..420 step 15) {
                val c = px(bmp, x, y)
                if (r(c) > 170 && g(c) > 120 && b(c) < 110 && r(c) - b(c) > 80) gold++
            }
        }
        return gold >= 5
    }

    /**
     * 팝업의 빨간 X 닫기 버튼 (이벤트 · 공지 등).
     * 게임을 새로 실행하면 이런 팝업이 뜬다. 월드맵에서는 그 자리가
     * 보라/파랑 계열이라 빨강으로 구분된다.
     */
    fun hasPopupCloseX(bmp: Bitmap): Boolean {
        // 실측: 빨간 육각형이 x 1840~1906, y 82~154 에 걸쳐 있고 중심은 (1873,117).
        // 가운데는 흰 X 획이라 한 점만 보면 놓친다. 격자로 훑어 몇 점 이상이면 인정한다.
        var hits = 0
        for (y in 90..150 step 12) {
            for (x in 1845..1900 step 12) {
                val c = px(bmp, x, y)
                if (r(c) > 150 && g(c) < 110 && b(c) < 110) hits++
            }
        }
        return hits >= 4
    }

    /**
     * X 없이 `확인` 버튼만 있는 모달 팝업의 버튼 y 좌표. 없으면 null.
     *
     * 하루 한 번 뜨는 보물상자 · 보상 · 출석체크가 이 모양이고 연달아 뜬다.
     * 닫기 X 가 없어 hasPopupCloseX 로는 못 잡았고,
     * 모든 탭이 이 모달에 막혀 루프가 멈췄다.
     * 더 나쁘게는, 맹목 탭이 출석체크판을 눌러 보상 팝업을 또 띄웠다.
     *
     * 생김새: 가운데에 파란 버튼, 그 양옆은 크림색 패널.
     * 실측 — 버튼 (0,101,173) · 패널 (206,182,164).
     * 버튼 하나만 보면 게임 곳곳의 파란 UI 에 걸리므로
     * **양옆이 크림색 패널인지**를 함께 본다.
     */
    fun confirmPopupY(bmp: Bitmap): Int? {
        for (y in 760..980 step 10) {
            // 버튼은 셋 다 x 1014~1326 에 걸쳐 있다. 가운데만 본다.
            // 이 폭 덕분에 '아이템 사용 확인'(확인 x=997 / 취소 x=1341) 은 걸리지 않는다 —
            // 그 창은 루프가 판단해서 눌러야 하므로 여기서 대신 누르면 안 된다.
            val blue = (1080..1250 step 42).all { x ->
                val c = px(bmp, x, y)
                b(c) > 130 && b(c) - r(c) > 90 && g(c) < b(c)
            }
            if (!blue) continue
            val cream = intArrayOf(900, 1400).all { x ->
                val c = px(bmp, x, y)
                r(c) > 180 && r(c) - b(c) > 25 && g(c) in b(c)..r(c)
            }
            if (cream) return y
        }
        return null
    }

    /**
     * 동굴 상세의 즐겨찾기 별.
     * 켜짐 = 주황, 꺼짐 = 회색. 어느 쪽도 확실하지 않으면 null 이고 그때는 건드리지 않는다.
     */
    fun isFavorited(bmp: Bitmap): Boolean? {
        // 한 점만 보면 별 모양의 빈틈에 걸린다. 격자로 훑는다.
        // 실측: 꺼짐은 어두운 회색 (16~99, 채널 차이 거의 없음),
        //       켜짐은 주황 (255,150,0 계열).
        var orange = 0
        var grey = 0
        for (y in 920..950 step 10) {
            for (x in 1715..1750 step 10) {
                val c = px(bmp, x, y)
                val spread = maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c))
                when {
                    r(c) > 180 && b(c) < 120 && r(c) - b(c) > 90 -> orange++
                    spread <= 24 && r(c) < 170 -> grey++
                }
            }
        }
        return when {
            orange >= 3 -> true
            grey >= 6 -> false
            else -> null
        }
    }

    /** 하이브로 아이디/비밀번호 입력 폼 (흰 입력칸 2개) */
    fun isIdForm(bmp: Bitmap): Boolean {
        // 한두 점만 보면 로딩 화면의 흰 섬광을 입력폼으로 오판한다.
        // 입력칸은 폭 전체가 균일한 흰 띠다. 가로로 여러 점을 보고 균일도까지 확인한다.
        for (y in intArrayOf(461, 580)) {
            val pts = intArrayOf(700, 950, 1200, 1450, 1650).map { px(bmp, it, y) }
            if (pts.any { minOf(r(it), g(it), b(it)) < 215 }) return false
            val sums = pts.map { r(it) + g(it) + b(it) }
            if (sums.max() - sums.min() > 18) return false
        }
        return true
    }
}
