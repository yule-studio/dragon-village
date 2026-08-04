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
        val (google, facebook, highbrow) = loginBands(bmp)
        return google >= 0 && facebook > google && highbrow > facebook
    }

    /** 하이브로 버튼의 실제 y 좌표. 못 찾으면 null. */
    fun highbrowButtonY(bmp: Bitmap): Int? {
        val (_, facebook, highbrow) = loginBands(bmp)
        return if (facebook < 0 || highbrow < 0) null else highbrow + 45   // 블록 가운데쯤
    }

    /** 로그인 버튼 세 개(구글=빨강 · 페북=파랑 · 하이브로=회색)의 첫 y. 없으면 -1. */
    private fun loginBands(bmp: Bitmap): Triple<Int, Int, Int> {
        var google = -1
        var facebook = -1
        var highbrow = -1
        var y = 280
        while (y <= 900) {
            when {
                band(bmp, y, Hue.RED) -> if (google < 0) google = y
                band(bmp, y, Hue.BLUE) -> if (facebook < 0 && google >= 0) facebook = y
                band(bmp, y, Hue.GREY) -> if (highbrow < 0 && facebook >= 0) highbrow = y
            }
            y += 15
        }
        return Triple(google, facebook, highbrow)
    }

    private enum class Hue { RED, BLUE, GREY }

    /**
     * 그 줄이 넓고 균일한 단색 띠인가.
     *
     * 예전에는 x=470 한 점만 봤다. 그러면 색만 맞으면 무엇이든 걸린다.
     * 실제로 동굴 그리드의 드래곤 썸네일 · 전투 화면 · 인벤토리를
     * 로그인 선택 화면으로 읽어 멀쩡히 돌던 루프를 멈췄다.
     * 로그인 버튼은 화면을 가로지르는 넓은 단색 띠다. 그 성질을 검사한다.
     */
    private fun band(bmp: Bitmap, y: Int, hue: Hue): Boolean {
        val pts = intArrayOf(420, 560, 700, 840, 980).map { px(bmp, it, y) }
        val sums = pts.map { r(it) + g(it) + b(it) }
        if (sums.max() - sums.min() > 70) return false
        return pts.all { c ->
            when (hue) {
                Hue.RED -> r(c) > 150 && r(c) > g(c) + 30 && r(c) > b(c) + 30
                Hue.BLUE -> b(c) > 110 && b(c) > r(c) + 40 && b(c) > g(c) + 20
                Hue.GREY -> kotlin.math.abs(r(c) - g(c)) < 30 &&
                    kotlin.math.abs(g(c) - b(c)) < 30 && r(c) in 71..209
            }
        }
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
     * 동굴 그리드의 검색창.
     *
     * 그리드 위쪽 도구줄에만 있는 넓고 밝은 입력칸이다.
     * 동굴에 들어왔는지를 이걸로 확인한다.
     *
     * 예전에는 좌측 'EVAL' 금색 버튼을 봤는데 틀렸다.
     * EVAL 머리글은 있을 때와 없을 때가 있어 정작 동굴에서 놓쳤고,
     * 탐험 숲 화면의 갈색이 금색 조건에 걸려 엉뚱한 곳을 동굴로 읽었다.
     * 저장된 캡처 21장으로 확인한 결과 그 판정은 쓸 수 없었다.
     */
    fun isCaveGrid(bmp: Bitmap): Boolean {
        // 검색칸 안쪽은 균일하게 어둡고, 오른쪽 끝(x 1741)에 밝은 테두리가 있다.
        // 글자를 피해 오른쪽 절반만 본다.
        for (x in intArrayOf(1500, 1540, 1580, 1620, 1660)) {
            for (y in intArrayOf(95, 103, 111)) {
                val c = px(bmp, x, y)
                if (maxOf(r(c), g(c), b(c)) >= 60) return false
            }
        }
        return (1730..1749).any { minOf(r(px(bmp, it, 103)), g(px(bmp, it, 103)), b(px(bmp, it, 103))) > 190 }
    }

    /**
     * 탐험 중 레벨이 오르면 뜨는 'LEVEL UP!' 창인가.
     *
     * 이 창은 화면을 아무 데나 눌러서는 안 넘어간다. 아래 '확인' 을 눌러야 한다.
     * 그걸 몰라 대사 넘기듯 60번 누르다 '탐험이 끝나지 않는다' 로 멈췄다.
     *
     * 확인 버튼은 지역 창의 탐험 버튼과 같은 파랑(0,97,173) 이고, 글자가 없는
     * 위아래 두 줄이 그 색으로 꽉 찬다. 위쪽 리본의 빨강까지 함께 본다.
     * 저장된 캡처 28장에서 오판이 없었다.
     */
    fun isLevelUpScreen(bmp: Bitmap): Boolean {
        for (y in intArrayOf(803, 851)) {
            for (x in 1080..1280 step 40) {
                val c = px(bmp, x, y)
                if (!(r(c) < 45 && g(c) in 70..125 && b(c) in 145..205)) return false
            }
        }
        var red = 0
        for (x in 900..1440 step 30) {
            val c = px(bmp, x, 138)
            if (r(c) > 150 && g(c) < 90 && b(c) < 90) red++
        }
        return red >= 12
    }

    /** 레벨업 창의 '확인'. 여기만 누르면 넘어간다. */
    val levelUpOk = 1168 to 824

    /**
     * '당신의 드래곤이 배고파 합니다' 창인가.
     *
     * 탐험을 돌리다 보면 중간에 뜬다. 확인을 누르면 음식을 먹여 회복한다.
     * 이 창을 모르고 대사 넘기듯 60번 누르다 멈춘 적이 있다.
     *
     * 확인/취소가 있는 창은 여럿이라 모양만 보고 확인을 누르면 위험하다.
     * 그래서 이 창의 생김새를 좁게 잡는다 — 넓고 균일한 짙은 패널,
     * 왼쪽의 파란 확인(0,97,173), 가운데 패널 색, 오른쪽의 따뜻한 회색 취소.
     * 저장된 캡처 27장에서 오판이 없었다.
     */
    fun isHungerPopup(bmp: Bitmap): Boolean {
        for (x in 700..1700 step 150) {
            val c = px(bmp, x, 400)
            if (maxOf(r(c), g(c), b(c)) > 60) return false
            if (maxOf(r(c), g(c), b(c)) - minOf(r(c), g(c), b(c)) > 20) return false
        }
        for (x in intArrayOf(880, 940, 1060, 1120)) {
            val c = px(bmp, x, 692)
            if (!(r(c) < 50 && g(c) in 80..130 && b(c) in 150..200)) return false
        }
        val mid = px(bmp, 1180, 692)
        if (maxOf(r(mid), g(mid), b(mid)) > 60) return false
        for (x in intArrayOf(1240, 1300)) {
            val c = px(bmp, x, 692)
            if (!(r(c) in 111..179 && r(c) - b(c) in 0..45 && b(c) <= g(c) && g(c) <= r(c)))
                return false
        }
        return true
    }

    /** 배고픔 창의 '확인'. 누르면 음식을 먹인다. */
    val hungerOk = 999 to 661

    /**
     * 레벨업 창에 찍힌 실제 레벨 ('Lv4 카멜레용' 의 4).
     *
     * 루프가 세는 값은 믿을 수 없다. 설정에 넣은 시작 레벨이 틀렸거나 중단됐다
     * 다시 시작하면 그만큼 어긋난다. 실제로 두 레벨 뒤처진 채 돌아
     * 난파선에 갈 때가 되었는데도 희망의 숲만 반복했다.
     *
     * 글자는 가운데 정렬이라 자릿수가 늘면 자리가 밀린다. 칸을 박아두지 않고
     * 흰 획이 있는 세로줄을 묶어 글자를 나눈다. 앞의 둘은 'L' 과 'v' 다.
     *
     * @return 읽은 레벨. 모르는 모양이면 null 과 그 모양(배우려고 로그에 남긴다).
     */
    fun levelUpLevel(bmp: Bitmap): Pair<Int?, String?> {
        val runs = glyphRuns(bmp)
        if (runs.size < 3) return null to null      // L · v · 숫자 최소 셋
        var value = 0
        for (i in 2 until runs.size) {
            val (a, b) = runs[i]
            val pat = glyphBits(bmp, a, b)
            val d = LEVEL_DIGITS[pat] ?: return null to pat
            value = value * 10 + d
        }
        return value to null
    }

    private const val LV_Y0 = 298
    private const val LV_Y1 = 348

    /** 'Lv4' 의 글자별 가로 구간. 흰 획이 이어지는 세로줄을 묶는다. */
    private fun glyphRuns(bmp: Bitmap): List<Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (x in 1120..1420) {
            val on = (LV_Y0..LV_Y1).any { y ->
                val c = px(bmp, x, y); minOf(r(c), g(c), b(c)) > 215
            }
            if (on && start < 0) start = x
            else if (!on && start >= 0) {
                if (x - start >= 5) runs.add(start to x - 1)
                start = -1
            }
        }
        if (start >= 0 && 1420 - start >= 5) runs.add(start to 1420)
        return runs
    }

    /** 글자 하나를 7x9 격자의 흰/검 무늬로 바꾼다. */
    private fun glyphBits(bmp: Bitmap, x0: Int, x1: Int): String {
        val w = x1 - x0 + 1
        val h = LV_Y1 - LV_Y0 + 1
        val sb = StringBuilder()
        for (gy in 0 until 9) {
            for (gx in 0 until 7) {
                val sx = x0 + w * gx / 7
                val ex = maxOf(x0 + w * (gx + 1) / 7, sx + 1)
                val sy = LV_Y0 + h * gy / 9
                val ey = maxOf(LV_Y0 + h * (gy + 1) / 9, sy + 1)
                var n = 0
                for (yy in sy until ey) for (xx in sx until ex) {
                    val c = px(bmp, xx, yy)
                    if (minOf(r(c), g(c), b(c)) > 215) n++
                }
                sb.append(if (n * 2 > (ey - sy) * (ex - sx)) '1' else '0')
            }
        }
        return sb.toString()
    }

    /**
     * 숫자 무늬표. 실제 화면에서 뽑아 넣는다.
     * 모르는 모양이 나오면 루프가 그 모양을 로그에 남기므로, 확인하고 여기에 더한다.
     */
    private val LEVEL_DIGITS = mapOf(
        "000000001111100100011000001100011100000110000001111000110011110" to 3,
        "000000000011100011110001011001001101100110111111100001100000110" to 4,
        "000000001111101100011110001001111100111111110001111000110111110" to 8,
    )

    /**
     * 레벨업 창에 찍힌 등급 육각형의 지문.
     *
     * 숫자를 아직 못 읽는다. 육각형 안 흰 획의 모양을 그대로 남겨 두고,
     * 실제 등급을 아는 캡처가 쌓이면 판독표를 만든다.
     * 위쪽 y 618 아래만 본다 — 그 위는 육각형의 흰 반사광이라 글자가 아니다.
     */
    fun gradeMark(bmp: Bitmap): Pair<Int, Int> {
        var h = 17
        var white = 0
        for (y in 618..652 step 2) {
            for (x in 650..740 step 2) {
                val c = px(bmp, x, y)
                val on = minOf(r(c), g(c), b(c)) > 200
                if (on) white++
                h = h * 31 + if (on) 1 else 0
            }
        }
        return h to white
    }

    /**
     * 지도 왼쪽 아래 슬롯에 있는 레벨 뱃지의 지문.
     *
     * 숫자를 읽지는 않는다. 바뀌었는지만 보면 레벨업을 알 수 있다.
     * 뱃지 원 안쪽만 보므로 드래곤 그림은 들어오지 않는다 —
     * 실제로 지도 네 곳(월드·탐험·던전·에스텔라) 과 드래곤 두 마리에서
     * 같은 레벨이면 같은 값이 나왔다.
     *
     * 지도 화면에서만 뜻이 있다. 창이 덮고 있으면 슬롯이 가려져 값이 무너진다.
     * 부르기 전에 isGameTopBarVisible 로 지도인지 확인해야 한다.
     *
     * @return 지문과 흰 픽셀 수. 흰 픽셀 수는 로그로 남겨 나중에
     *         숫자 판독표를 만들 때 쓴다.
     */
    fun levelBadge(bmp: Bitmap): Pair<Int, Int> {
        var h = 17
        var white = 0
        for (y in 878..925 step 2) {
            for (x in 270..335 step 2) {
                val c = px(bmp, x, y)
                val on = minOf(r(c), g(c), b(c)) > 200
                if (on) white++
                h = h * 31 + if (on) 1 else 0
            }
        }
        return h to white
    }

    /**
     * 월드맵인가.
     *
     * 동굴은 지금 있는 지도 위에 겹쳐 열린다. 닫으면 원래 지도로 돌아가지
     * 월드맵으로 가지 않는다. 그걸 모르고 월드맵이라 여긴 채 탐험 좌표를 눌러
     * 에스텔라 지역으로 들어간 적이 있다. 그래서 지도를 눈으로 확인한다.
     *
     * 위쪽 하늘 띠의 옅은 하늘색을 본다. 탐험맵 · 던전 · 에스텔라 · 하늘 왕국은
     * 그 자리가 각각 바위 · 보라 · 물속 · 구름이라 겹치지 않는다.
     * 저장된 캡처로 월드맵 4장은 7~8점, 나머지 21장은 0점이었다.
     */
    fun isWorldMap(bmp: Bitmap): Boolean {
        var n = 0
        for ((x, y) in SKY) {
            val c = px(bmp, x, y)
            if (b(c) > 230 && g(c) > 200 && r(c) in 110..199 &&
                b(c) >= g(c) && g(c) >= r(c)
            ) n++
        }
        return n >= 6
    }

    private val SKY = listOf(
        560 to 90, 600 to 90, 650 to 90, 1140 to 90,
        1180 to 90, 1240 to 90, 1300 to 110, 700 to 150,
    )

    /**
     * 탐험 지역 창이 열려 있는가 (등장 몬스터 · 탐험 / 자동 탐험 / 토벌권 사용).
     *
     * 지역마다 배경이 다르지만 창 자체는 어디서나 같은 자리에 같은 모양으로 뜬다.
     * '탐험' 버튼의 파랑(0,97,173)과 그 아래 크림색 패널을 함께 본다.
     * 파랑만 보면 에스텔라 지도의 물빛에 걸린다.
     *
     * 지역 창이 떴다는 것은 지도에서 옳은 곳을 눌렀다는 뜻이다.
     * 이게 확인돼야 탐험 버튼을 누른다.
     */
    fun isZoneDialog(bmp: Bitmap): Boolean {
        for (x in intArrayOf(1400, 1440, 1540, 1580)) {
            val c = px(bmp, x, 361)
            if (!(r(c) < 45 && g(c) in 70..125 && b(c) in 145..205)) return false
        }
        for (x in intArrayOf(1400, 1560)) {
            val c = px(bmp, x, 595)
            if (minOf(r(c), g(c), b(c)) < 200) return false
        }
        return true
    }

    /**
     * 몬스터를 만난 화면인가. 맞으면 FIGHT 육각형의 세로 중심을 돌려준다.
     *
     * 육각형은 x 1700~1800 에 고정인데 세로 위치는 화면 구성에 따라 다르다
     * (1마리 지역 830, 3마리 지역 736). 좌표를 박아두면 빗나간다.
     *
     * 가운데를 가로지르는 'FIGHT!' 리본이 노랑이라 빨강이 위아래로 끊긴다.
     * 그 두 덩이를 찾아 사이를 중심으로 삼는다.
     * 아래쪽 편성 바의 빨간 체력 막대에 걸리지 않도록 두 덩이의 간격도 본다.
     */
    fun fightButtonY(bmp: Bitmap): Int? {
        val rows = (560..1036 step 4).filter { y ->
            (1700..1800 step 10).count { x ->
                val c = px(bmp, x, y)
                r(c) > 150 && g(c) < 90 && b(c) < 90
            } >= 6
        }
        if (rows.isEmpty()) return null

        val groups = mutableListOf<MutableList<Int>>()
        rows.forEach { y ->
            val last = groups.lastOrNull()
            if (last != null && y - last.last() <= 8) last.add(y) else groups.add(mutableListOf(y))
        }
        for (i in 0 until groups.size - 1) {
            val top = groups[i].first()
            val bottom = groups[i + 1].last()
            if (bottom - top in 150..230) return (top + bottom) / 2
        }
        return null
    }

    /**
     * 동굴 필터창이 열려 있는가.
     *
     * 버튼 사이 여백만 본다. 창이 크고 배경이 균일한 짙은 회색이라
     * 뒤에 있던 그리드 · 동굴 · 월드맵과 확실히 갈린다.
     * (실측 — 필터창 25~33, 그리드/동굴/월드맵은 색이 제각각)
     */
    fun isFilterOpen(bmp: Bitmap): Boolean =
        FILTER_GAPS.all { (x, y) ->
            val c = px(bmp, x, y)
            val hi = maxOf(r(c), g(c), b(c))
            hi < 50 && hi - minOf(r(c), g(c), b(c)) < 20
        }

    private val FILTER_GAPS = listOf(
        1303 to 187, 1529 to 187, 1303 to 105, 1529 to 105,
        1303 to 270, 1529 to 270, 1100 to 187, 1750 to 146, 1750 to 228,
    )

    /**
     * 필터 버튼이 켜져 있는가. 켜지면 금색, 꺼지면 짙은 회색이다.
     * 글자 획에 걸리지 않도록 버튼의 좌우 여백만 본다.
     */
    fun isFilterButtonOn(bmp: Bitmap, cx: Int, cy: Int): Boolean {
        var gold = 0
        for (dx in intArrayOf(-72, -60, 60, 72)) {
            for (dy in intArrayOf(-14, 0, 14)) {
                val c = px(bmp, cx + dx, cy + dy)
                if (r(c) > 200 && g(c) > 150 && b(c) < 170 && r(c) - b(c) > 60) gold++
            }
        }
        return gold >= 10
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
     * 게임 상단 아이콘 줄(우편·동굴·월드·메뉴)이 보이는가.
     *
     * 보이면 월드맵이거나 용 패널까지만 열린 상태 = 좌표 탭이 통한다.
     * 안 보이면 무언가 큰 창이 덮고 있다는 뜻이다.
     *
     * 이걸 안 보고 탭하다 친구 목록 창이 열린 채 같은 자리를 계속 두드렸다.
     * 창의 닫기 X 를 찾는 방법은 쓸 수 없다 — 월드맵의 빨간 N 배지가
     * 위치도 크기도 거의 같아서(2000,55 대 2022,52) 구분되지 않는다.
     *
     * 실측 — 메뉴 아이콘의 흰 부분. 월드맵 · 용 패널 · 메뉴 4/9 이상,
     * 친구창 · 설정 · 팝업류는 0/9.
     */
    fun isGameTopBarVisible(bmp: Bitmap): Boolean {
        var hits = 0
        for (dy in intArrayOf(-8, 0, 8)) {
            for (dx in intArrayOf(-10, 0, 10)) {
                val c = px(bmp, 1940 + dx, 60 + dy)
                val lo = minOf(r(c), g(c), b(c))
                val hi = maxOf(r(c), g(c), b(c))
                if (lo > 200 && hi - lo < 20) hits++
            }
        }
        return hits >= 3
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
            if (!cream) continue
            // 찾은 줄은 버튼의 윗변이다. 거기를 누르면 테두리라 안 먹는다 —
            // 실제로 같은 자리를 무한히 두드리다 멈췄다.
            // 글자를 피한 x=1060 에서 아래로 훑어 버튼의 세로 중심을 구한다.
            var bottom = y
            while (bottom < 1040) {
                val c = px(bmp, 1060, bottom + 1)
                if (!(b(c) > 130 && b(c) - r(c) > 90 && g(c) < b(c))) break
                bottom++
            }
            return (y + bottom) / 2
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
