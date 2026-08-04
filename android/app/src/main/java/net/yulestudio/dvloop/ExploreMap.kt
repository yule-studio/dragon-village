package net.yulestudio.dvloop

/**
 * 탐험 지역표.
 *
 * 좌표는 2340x1080 가로 화면에서 실제로 눌러 확인한 값이다.
 * 지역 창은 어디서나 같은 자리에 뜬다 — 탐험 버튼 (1490,361).
 *
 * 레벨업을 할 수 있는 곳만 담았다. 같은 맵에 있어도 탐험이 안 되는 곳
 * (지하 성채 · 지하 던전 · 신전 에스텔라 · 왕궁 본채 등) 은 넣지 않는다.
 * 넣어두면 루프가 들어갔다가 아무것도 못 하고 멈춘다.
 */
enum class Area(val label: String, val at: Pair<Int, Int>) {
    /**
     * 월드맵 → 탐험.
     *
     * 글자 한가운데(1287,852)는 계정삭제 금지구역(x 1030~1300, y 845~935) 안이라
     * 접근성 서비스가 탭을 거부한다. 거부돼도 화면은 그대로라 지도가 바뀐 줄 알고
     * 다음 탭을 날렸고, 그게 월드맵에 떨어져 에스텔라로 들어갔다.
     * 금지구역은 그대로 두고 누를 자리를 글자 윗줄로 올렸다.
     */
    EXPLORE("탐험", 1287 to 836),

    /** 월드맵 → 던전 */
    DUNGEON("던전", 543 to 824),

    /** 월드맵 → 에스텔라 */
    ESTELLA("에스텔라", 1727 to 976),

    /**
     * 월드맵 → 하늘 왕국.
     * 글자를 누르면 안 먹는다. 위의 별 표식이 실제 누를 자리다.
     */
    SKY("하늘 왕국", 924 to 363),
}

/**
 * @param minLevel 이 곳이 열리는 레벨
 * @param party    몬스터를 만났을 때 편성해야 하는 드래곤 수.
 *                 에스텔라 · 하늘 왕국은 3마리를 고르는 화면이 먼저 뜬다.
 */
data class Zone(
    val name: String,
    val minLevel: Int,
    val area: Area,
    val party: Int,
    val at: Pair<Int, Int>,
)

object ExploreMap {

    /** 레벨이 낮은 곳부터. 대상 레벨에 맞는 곳을 고를 때 이 순서에 기댄다. */
    val zones = listOf(
        Zone("희망의 숲", 1, Area.EXPLORE, 1, 1615 to 872),
        Zone("난파선", 5, Area.EXPLORE, 1, 1059 to 764),
        Zone("불의 산", 10, Area.EXPLORE, 1, 1825 to 427),
        Zone("바람의 신전", 15, Area.EXPLORE, 1, 605 to 566),
        Zone("하늘의 신전", 20, Area.EXPLORE, 1, 1437 to 205),
        Zone("무지개 동산", 25, Area.EXPLORE, 1, 899 to 138),

        Zone("고룡의 무덤", 28, Area.DUNGEON, 1, 662 to 679),
        Zone("나무 던전", 30, Area.DUNGEON, 1, 587 to 225),
        Zone("어둠의 제단", 32, Area.DUNGEON, 1, 1839 to 363),

        Zone("별빛의 산호초", 32, Area.ESTELLA, 3, 709 to 459),
        Zone("청록의 해저화산", 34, Area.ESTELLA, 3, 1299 to 749),
        Zone("고요의 암석터", 35, Area.ESTELLA, 3, 1762 to 520),

        Zone("메마른 평원", 40, Area.SKY, 3, 1582 to 445),
        Zone("옛 성터", 40, Area.SKY, 3, 672 to 723),
    )

    /**
     * 이 레벨에서 갈 수 있는 가장 높은 곳.
     *
     * 경험치는 높은 곳일수록 많이 준다. 레벨이 오르면 저절로 위로 옮겨간다.
     * 같은 레벨에 여러 곳이 열리면 1마리짜리를 먼저 쓴다 —
     * 3마리 맵은 몬스터마다 편성 화면을 한 번 더 거쳐야 해서 실패할 자리가 많다.
     */
    fun zoneFor(level: Int): Zone {
        val open = zones.filter { it.minLevel <= level }
        if (open.isEmpty()) return zones.first()
        val top = open.maxOf { it.minLevel }
        return open.filter { it.minLevel == top }.minBy { it.party }
    }
}
