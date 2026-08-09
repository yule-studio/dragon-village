package net.yulestudio.dvloop

import android.graphics.Bitmap

/**
 * 눌러야 할 것을 화면에서 찾는다.
 *
 * 좌표를 박아두면 게임이 업데이트될 때마다 조금씩 어긋나고, 그때부터는
 * 엉뚱한 데를 누르기 시작한다. 오늘만 그런 사고를 여러 번 냈다.
 *
 * 그렇다고 순수 탐색만 쓰는 것도 위험하다. 게임 배경 그림이 같은 색을 자주 낸다 —
 * 숲의 갈색을 동굴 금색으로, 드래곤 썸네일을 로그인 버튼으로 읽은 적이 있다.
 *
 * 그래서 **기준점 + 탐색창** 으로 간다.
 *   1. 예상 자리에서 색을 확인한다. 맞으면 그대로 쓴다 (대부분 여기서 끝난다)
 *   2. 틀리면 그 언저리만 훑어 색 덩이를 찾는다
 *   3. 그래도 없으면 null. 부르는 쪽은 아무것도 누르지 않고 멈춘다
 *
 * 탐색 범위를 좁게 두는 것이 핵심이다. 화면 전체를 훑으면 배경에 걸린다.
 */
object Locate {

    /** 찾은 덩이의 테두리 상자. 좌표는 2340x1080 기준이다. */
    data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val cells: Int) {
        val cx get() = (x0 + x1) / 2
        val cy get() = (y0 + y1) / 2
        val w get() = x1 - x0
        val h get() = y1 - y0
    }

    /**
     * 누를 것 하나의 정의.
     *
     * @param hint    지금까지 알려진 자리. 대부분 그대로 맞는다
     * @param search  빗나갔을 때 훑을 범위 (x0,y0,x1,y1). 좁게 잡을수록 안전하다
     * @param minCells 이만큼은 모여 있어야 그 물건으로 인정한다. 배경 얼룩을 거른다
     * @param size    찾은 덩이가 이 크기 범위여야 한다 (폭, 높이). 없으면 검사하지 않는다
     */
    data class Anchor(
        val name: String,
        val hint: Pair<Int, Int>,
        val search: IntArray,
        val minCells: Int = 12,
        val size: IntRange? = null,
        val match: (Int, Int, Int) -> Boolean,
    )

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    /** 해상도가 달라도 같은 자리를 보도록 비율로 옮긴다. */
    private fun px(bmp: Bitmap, x: Int, y: Int): Int {
        val sx = (x.toLong() * bmp.width / Judge.BASE_W).toInt().coerceIn(0, bmp.width - 1)
        val sy = (y.toLong() * bmp.height / Judge.BASE_H).toInt().coerceIn(0, bmp.height - 1)
        return bmp.getPixel(sx, sy)
    }

    private fun hit(bmp: Bitmap, x: Int, y: Int, m: (Int, Int, Int) -> Boolean): Boolean {
        val c = px(bmp, x, y)
        return m(r(c), g(c), b(c))
    }

    /**
     * 예상 자리가 아직 맞는가.
     * 가운데에 글자나 무늬가 들어가는 경우가 많아 몇 점만 맞아도 인정한다.
     */
    private fun stillThere(bmp: Bitmap, a: Anchor): Boolean {
        var n = 0
        var seen = 0
        for (dy in -12..12 step 6) {
            for (dx in -12..12 step 6) {
                seen++
                if (hit(bmp, a.hint.first + dx, a.hint.second + dy, a.match)) n++
            }
        }
        return n * 2 >= seen      // 절반 이상
    }

    /**
     * 탐색창 안에서 가장 큰 색 덩이를 찾는다.
     *
     * 성긴 격자 위에서 이어진 덩어리를 훑는다. 화면 전체를 한 점씩 보면 느리고,
     * 흩어진 배경 얼룩은 덩어리 크기로 걸러진다.
     */
    fun blob(
        bmp: Bitmap,
        x0: Int, y0: Int, x1: Int, y1: Int,
        step: Int = 4,
        minCells: Int = 12,
        match: (Int, Int, Int) -> Boolean,
    ): Box? {
        val cols = (x1 - x0) / step + 1
        val rows = (y1 - y0) / step + 1
        if (cols <= 0 || rows <= 0) return null

        val on = BooleanArray(cols * rows)
        for (j in 0 until rows) {
            for (i in 0 until cols) {
                on[j * cols + i] = hit(bmp, x0 + i * step, y0 + j * step, match)
            }
        }

        val seen = BooleanArray(cols * rows)
        var best: Box? = null
        val stack = ArrayDeque<Int>()

        for (start in on.indices) {
            if (!on[start] || seen[start]) continue
            stack.clear(); stack.addLast(start); seen[start] = true
            var minI = cols; var maxI = -1; var minJ = rows; var maxJ = -1; var count = 0

            while (stack.isNotEmpty()) {
                val k = stack.removeLast()
                val i = k % cols; val j = k / cols
                count++
                if (i < minI) minI = i; if (i > maxI) maxI = i
                if (j < minJ) minJ = j; if (j > maxJ) maxJ = j

                if (i > 0) neighbour(k - 1, on, seen, stack)
                if (i < cols - 1) neighbour(k + 1, on, seen, stack)
                if (j > 0) neighbour(k - cols, on, seen, stack)
                if (j < rows - 1) neighbour(k + cols, on, seen, stack)
            }

            if (count >= minCells && (best == null || count > best!!.cells)) {
                best = Box(
                    x0 + minI * step, y0 + minJ * step,
                    x0 + maxI * step, y0 + maxJ * step, count,
                )
            }
        }
        return best
    }

    private fun neighbour(k: Int, on: BooleanArray, seen: BooleanArray, stack: ArrayDeque<Int>) {
        if (on[k] && !seen[k]) { seen[k] = true; stack.addLast(k) }
    }

    /**
     * 누를 자리를 찾는다. 못 찾으면 null — 부르는 쪽은 아무것도 누르지 않는다.
     *
     * @return 누를 좌표와, 예상 자리를 그대로 썼는지 여부
     */
    fun find(bmp: Bitmap, a: Anchor): Pair<Pair<Int, Int>, Boolean>? {
        if (stillThere(bmp, a)) return a.hint to true

        val box = blob(
            bmp, a.search[0], a.search[1], a.search[2], a.search[3],
            minCells = a.minCells, match = a.match,
        ) ?: return null

        a.size?.let { if (box.w !in it || box.h !in it) return null }
        return (box.cx to box.cy) to false
    }
}
