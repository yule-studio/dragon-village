package net.yulestudio.dvloop

import android.content.Context

/**
 * 마지막 루프 설정과 진행 상황을 기억한다.
 *
 * 설정은 5단계를 거쳐야 하는데, 중단될 때마다 처음부터 다시 밟는 것은 말이 안 된다.
 * 한 번 정한 대상은 저장해두고, 다음에는 '이어서 시작' 한 번으로 재개한다.
 *
 * 진행 레벨은 루프가 성공할 때마다 갱신한다. 다시 시작하면 그 레벨부터 이어간다.
 */
class LoopPrefs(ctx: Context) {

    private val p = ctx.getSharedPreferences("dv_loop", Context.MODE_PRIVATE)

    var dragonNo: Int
        get() = p.getInt(KEY_NO, 0)
        private set(v) = p.edit().putInt(KEY_NO, v).apply()

    var dragonName: String
        get() = p.getString(KEY_NAME, "").orEmpty()
        private set(v) = p.edit().putString(KEY_NAME, v).apply()

    var stageName: String
        get() = p.getString(KEY_STAGE, "").orEmpty()
        private set(v) = p.edit().putString(KEY_STAGE, v).apply()

    var currentLevel: Int
        get() = p.getInt(KEY_LEVEL, 1)
        set(v) = p.edit().putInt(KEY_LEVEL, v).apply()

    var currentGrade: Float
        get() = p.getFloat(KEY_GRADE, 7.0f)
        private set(v) = p.edit().putFloat(KEY_GRADE, v).apply()

    var targetLevel: Int
        get() = p.getInt(KEY_TARGET, 45)
        private set(v) = p.edit().putInt(KEY_TARGET, v).apply()

    var kindName: String
        get() = p.getString(KEY_KIND, LoopKind.LEVEL_UP.name).orEmpty()
        private set(v) = p.edit().putString(KEY_KIND, v).apply()

    /** 탐험 루프의 등급 하한. 레벨업 루프에서는 쓰지 않는다. */
    var minGrade: Float
        get() = p.getFloat(KEY_MIN_GRADE, 3.0f)
        private set(v) = p.edit().putFloat(KEY_MIN_GRADE, v).apply()

    var minusBudget: Int
        get() = p.getInt(KEY_MINUS, 5)
        private set(v) = p.edit().putInt(KEY_MINUS, v).apply()

    /** 목표에 닿았는지. 닿았으면 '이어서 시작' 대신 새로 설정하게 한다. */
    val isDone: Boolean get() = hasSetup && currentLevel >= targetLevel

    val hasSetup: Boolean get() = dragonNo != 0 && stageName.isNotBlank()

    fun save(setup: LoopSetup) {
        dragonNo = setup.dragon?.no ?: 0
        dragonName = setup.dragon?.name.orEmpty()
        stageName = setup.stage?.name.orEmpty()
        currentLevel = setup.currentLevel
        currentGrade = setup.currentGrade.toFloat()
        targetLevel = setup.targetLevel
        kindName = setup.kind.name
        minGrade = setup.minGrade.toFloat()
        minusBudget = setup.minusBudget
    }

    /** 저장된 설정을 되살린다. 도감에서 드래곤을 다시 찾아 붙인다. */
    fun restore(repo: DexRepository): LoopSetup? {
        if (!hasSetup) return null
        val dragon = repo.byNo(dragonNo) ?: return null
        val stage = runCatching { Stage.valueOf(stageName) }.getOrNull() ?: return null
        return LoopSetup(
            dragon = dragon,
            stage = stage,
            currentLevel = currentLevel,
            currentGrade = currentGrade.toDouble(),
            targetLevel = targetLevel,
            kind = runCatching { LoopKind.valueOf(kindName) }.getOrDefault(LoopKind.LEVEL_UP),
            minGrade = minGrade.toDouble(),
            minusBudget = minusBudget,
        )
    }

    /**
     * 대상 지정(동굴 검색 → 즐겨찾기)을 마친 드래곤 번호.
     *
     * 즐겨찾기는 서버에 남아 데삭 후에도 유지된다.
     * 한 번 지정했으면 목표를 끝낼 때까지 다시 할 이유가 없다.
     */
    var preparedNo: Int
        get() = p.getInt(KEY_PREPARED, 0)
        set(v) = p.edit().putInt(KEY_PREPARED, v).apply()

    /**
     * 다음에 이어서 키울 대상들.
     *
     * 한 마리를 끝내면 멈추던 것을, 줄을 세워두고 차례로 잇는다.
     * '고대신룡 끝나면 빙하고룡, 그다음 파워드래곤' 처럼 밤새 이어 돌리기 위해서다.
     *
     * 저장 형식은 `번호:단계:목표` 를 `;` 로 이은 것이다. 값이 셋뿐이라
     * 이 정도면 충분하고, 형식이 깨져도 그 항목만 버리면 된다.
     */
    var queue: List<QueueItem>
        get() = p.getString(KEY_QUEUE, "").orEmpty()
            .split(";").filter { it.isNotBlank() }
            .mapNotNull { row ->
                val f = row.split(":")
                if (f.size < 3) return@mapNotNull null
                val no = f[0].toIntOrNull() ?: return@mapNotNull null
                val target = f[2].toIntOrNull() ?: return@mapNotNull null
                // 예전에는 셋만 저장해서 등급과 하한을 잃었다. 줄을 서 있던
                // 용이 7.0 기준으로 돌아가 엉뚱하게 판정됐다. 값이 없으면
                // 예전처럼 7.0 으로 두되, 새로 담는 것은 다 싣는다.
                QueueItem(
                    no = no,
                    stage = f[1],
                    target = target,
                    grade = f.getOrNull(3)?.toDoubleOrNull() ?: 7.0,
                    floor = f.getOrNull(4)?.toDoubleOrNull() ?: 7.0,
                    level = f.getOrNull(5)?.toIntOrNull() ?: 1,
                )
            }
        set(v) = p.edit().putString(
            KEY_QUEUE,
            v.joinToString(";") {
                "${it.no}:${it.stage}:${it.target}:${it.grade}:${it.floor}:${it.level}"
            },
        ).apply()

    fun enqueue(setup: LoopSetup) {
        val no = setup.dragon?.no ?: return
        val stage = setup.stage?.name ?: return
        queue = queue + QueueItem(
            no = no,
            stage = stage,
            target = setup.targetLevel,
            grade = setup.currentGrade,
            floor = setup.minGrade,
            level = setup.currentLevel,
        )
    }

    /** 대기열을 통째로 비운다. 다중 설정을 다시 짤 때 쓴다. */
    fun clearQueue() {
        queue = emptyList()
    }

    /** 맨 앞을 꺼내 지금 설정으로 올린다. 비어 있으면 null. */
    fun popQueue(repo: DexRepository): LoopSetup? {
        val q = queue
        val head = q.firstOrNull() ?: return null
        queue = q.drop(1)
        val dragon = repo.byNo(head.no) ?: return null
        val stage = runCatching { Stage.valueOf(head.stage) }.getOrNull() ?: return null
        return LoopSetup(
            dragon = dragon,
            stage = stage,
            currentLevel = head.level,
            currentGrade = head.grade,
            minGrade = head.floor,
            targetLevel = head.target,
            kind = LoopKind.LEVEL_UP,
        )
    }

    fun clear() = p.edit().clear().apply()

    data class QueueItem(
        val no: Int,
        val stage: String,
        val target: Int,
        val grade: Double = 7.0,
        val floor: Double = 7.0,
        val level: Int = 1,
    )

    private companion object {
        const val KEY_NO = "dragon_no"
        const val KEY_NAME = "dragon_name"
        const val KEY_STAGE = "stage"
        const val KEY_LEVEL = "level"
        const val KEY_GRADE = "grade"
        const val KEY_TARGET = "target"
        const val KEY_PREPARED = "prepared_no"
        const val KEY_QUEUE = "queue"
        const val KEY_KIND = "kind"
        const val KEY_MIN_GRADE = "min_grade"
        const val KEY_MINUS = "minus_budget"
    }
}
