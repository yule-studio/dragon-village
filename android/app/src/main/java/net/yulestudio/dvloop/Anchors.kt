package net.yulestudio.dvloop

/**
 * 누를 것들의 정의.
 *
 * 좌표를 코드 곳곳에 박아두면 게임이 업데이트될 때마다 어디를 고쳐야 하는지
 * 알 수 없다. 한곳에 모으고, 각자 '빗나갔을 때 어디를 훑을지' 까지 들고 있게 한다.
 *
 * `hint` 는 지금까지 실측으로 확인된 자리다. 대부분 그대로 맞는다.
 * 어긋나면 `search` 범위만 훑어 같은 색 덩이를 찾는다. 그래도 없으면 누르지 않는다.
 */
object Anchors {

    // ---- 색 조건 ----

    /** 창 닫기 X 의 빨간 육각형 */
    private val red = { r: Int, g: Int, b: Int -> r > 150 && g < 95 && b < 95 }

    /** 게임의 파란 버튼. 지역 창의 '탐험', 레벨업 창의 '확인' 이 같은 색이다 */
    private val blue = { r: Int, g: Int, b: Int -> r < 45 && g in 70..125 && b in 145..205 }

    /** 상단 아이콘 줄의 흰 글자·그림 */
    private val white = { r: Int, g: Int, b: Int ->
        minOf(r, g, b) > 200 && maxOf(r, g, b) - minOf(r, g, b) < 20
    }

    /** 레벨업 다이얼로그의 노란 '레벨 업' 버튼 */
    private val gold = { r: Int, g: Int, b: Int -> r > 230 && g in 151..209 && b < 60 }

    // ---- 상단 아이콘 줄 ----
    // 지도류 화면이면 어디서나 같은 자리에 있다. 우편 · 동굴 · 월드 · 메뉴 순.

    val cave = Locate.Anchor(
        "동굴", 1714 to 88, intArrayOf(1650, 40, 1780, 140), minCells = 8, match = white,
    )
    val world = Locate.Anchor(
        "월드", 1839 to 88, intArrayOf(1786, 40, 1902, 140), minCells = 8, match = white,
    )
    val menu = Locate.Anchor(
        "메뉴", 1977 to 88, intArrayOf(1920, 40, 2050, 140), minCells = 8, match = white,
    )

    // ---- 닫기 ----

    /**
     * 전체 화면 창의 표준 닫기 X. 동굴 · 친구 목록 등이 같은 자리를 쓴다.
     * 실측 상자 x 1976~2048, y 14~74.
     */
    val windowX = Locate.Anchor(
        "창 닫기", 2012 to 53, intArrayOf(1900, 5, 2110, 130),
        minCells = 10, size = 30..140, match = red,
    )

    /** 탐험 지역 창의 닫기 X. 표준 자리와 다르다 (실측 1720,83). */
    val zoneX = Locate.Anchor(
        "지역 창 닫기", 1720 to 83, intArrayOf(1652, 20, 1800, 150),
        minCells = 10, size = 30..140, match = red,
    )

    // ---- 파란 버튼 ----

    /** 지역 창의 '탐험'. 지역이 달라도 창은 같은 자리에 뜬다. */
    val exploreBtn = Locate.Anchor(
        "탐험", 1490 to 361, intArrayOf(1370, 315, 1630, 410),
        minCells = 20, match = blue,
    )

    /** 레벨업 창의 '확인'. 여기만 누르면 넘어간다. */
    val levelUpOk = Locate.Anchor(
        "레벨업 확인", 1168 to 824, intArrayOf(1000, 775, 1340, 875),
        minCells = 20, match = blue,
    )

    /** 배고픔 창의 '확인'. 누르면 음식을 먹인다. */
    val hungerOk = Locate.Anchor(
        "배고픔 확인", 999 to 661, intArrayOf(850, 615, 1160, 710),
        minCells = 20, match = blue,
    )

    // ---- 레벨업 다이얼로그 ----

    val levelUpButton = Locate.Anchor(
        "레벨 업", 1416 to 930, intArrayOf(1300, 890, 1540, 975),
        minCells = 15, match = gold,
    )
}
