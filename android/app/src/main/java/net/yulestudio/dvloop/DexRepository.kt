package net.yulestudio.dvloop

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 도감 저장소.
 *
 * `dexcrawl.py` 가 만든 SQLite 파일을 assets 에서 통째로 쓴다.
 * 크롤링 로직을 Kotlin 으로 다시 구현하지 않는다 — 도감은 거의 변하지 않고,
 * 파일 하나만 교체하면 갱신이 끝난다.
 */
data class Dragon(
    val no: Int,
    val name: String,
    val attr: String?,
    val type: String?,
    val gen: String?,
)

class DexRepository(private val ctx: Context) {

    private val db: SQLiteDatabase by lazy { open() }

    private fun open(): SQLiteDatabase {
        val out = File(ctx.filesDir, ASSET)
        // assets 의 DB 가 더 새 것이면 덮어쓴다 (앱 업데이트로 도감이 갱신된 경우)
        if (!out.exists() || out.length() != assetSize()) {
            ctx.assets.open(ASSET).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun assetSize(): Long =
        ctx.assets.openFd(ASSET).use { it.length }

    /** 속성 코드 → 사람이 읽는 이름. DA = DAwn(여명), DU = DUsk(황혼). */
    fun attrLabel(code: String?): String = when (code) {
        "F" -> "불"
        "W" -> "바람"
        "A" -> "물"
        "E" -> "땅"
        "L" -> "빛"
        "D" -> "어둠"
        "DA" -> "여명"
        "DU" -> "황혼"
        "N" -> "무"
        null -> "-"
        else -> code
    }

    /**
     * 그 드래곤이 가진 진화 단계 번호.
     *
     * 도감 사이트의 정의를 그대로 따른다:
     * 0 알 · 1 해치 · 2 해츨링 · 3 성체 · 4 캡슐 · 5 각성 · 6 초월 · 7 강림
     *
     * 1215마리 중 930마리는 성체까지뿐이다. 없는 단계를 고르게 두면 안 된다.
     */
    fun availableSteps(dragonNo: Int): Set<Int> =
        db.rawQuery(
            "SELECT DISTINCT step FROM dragon_image WHERE dragon_no=? AND kind='thumb'",
            arrayOf("$dragonNo")
        ).use { c ->
            buildSet { while (c.moveToNext()) add(c.getInt(0)) }
        }

    /** 타입 코드 → 게임 화면 태그와 동일한 이름. */
    fun typeLabel(code: String?): String = when (code) {
        "H" -> "체력"
        "A" -> "공격"
        "D" -> "방어"
        "HA" -> "체공"
        "AD" -> "공방"
        "HD" -> "체방"
        "NONE" -> "중립"
        null -> "-"
        else -> code
    }

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM dragon", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /**
     * 이름 부분일치 검색. query 가 비면 도감 번호 순으로 앞에서부터.
     * offset 으로 이어 읽어 무한 스크롤에 쓴다.
     */
    fun search(
        query: String = "",
        limit: Int = 40,
        offset: Int = 0,
        attr: String? = null,
        type: String? = null,
    ): List<Dragon> {
        val where = buildList {
            if (query.isNotBlank()) add("name LIKE ?")
            if (attr != null) add("attr = ?")
            if (type != null) add("type = ?")
        }
        val args = buildList {
            if (query.isNotBlank()) add("%$query%")
            if (attr != null) add(attr)
            if (type != null) add(type)
            add("$limit")
            add("$offset")
        }.toTypedArray()

        val sql = buildString {
            append("SELECT dragon_no,name,attr,type,gen FROM dragon")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY dragon_no LIMIT ? OFFSET ?")
        }

        return db.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Dragon(
                            no = c.getInt(0),
                            name = c.getString(1),
                            attr = c.getString(2),
                            type = c.getString(3),
                            gen = c.getString(4),
                        )
                    )
                }
            }
        }
    }

    /** 대표 썸네일(step 0) BLOB → Bitmap. 없으면 null. */
    fun thumbnail(dragonNo: Int): Bitmap? =
        db.rawQuery(
            "SELECT data FROM dragon_image WHERE dragon_no=? AND kind='thumb' AND step=0",
            arrayOf("$dragonNo")
        ).use { c ->
            if (!c.moveToFirst()) return null
            val blob = c.getBlob(0) ?: return null
            BitmapFactory.decodeByteArray(blob, 0, blob.size)
        }

    companion object {
        const val ASSET = "dragondex.db"
    }
}
