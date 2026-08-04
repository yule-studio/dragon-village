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

    fun clear() = p.edit().clear().apply()

    private companion object {
        const val KEY_NO = "dragon_no"
        const val KEY_NAME = "dragon_name"
        const val KEY_STAGE = "stage"
        const val KEY_LEVEL = "level"
        const val KEY_GRADE = "grade"
        const val KEY_TARGET = "target"
        const val KEY_PREPARED = "prepared_no"
        const val KEY_KIND = "kind"
        const val KEY_MIN_GRADE = "min_grade"
        const val KEY_MINUS = "minus_budget"
    }
}
