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

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM dragon", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** 이름 부분일치 검색. query 가 비면 도감 번호 순으로 앞에서부터. */
    fun search(query: String = "", limit: Int = 100): List<Dragon> {
        val sql = if (query.isBlank())
            "SELECT dragon_no,name,attr,type,gen FROM dragon ORDER BY dragon_no LIMIT ?"
        else
            "SELECT dragon_no,name,attr,type,gen FROM dragon WHERE name LIKE ? ORDER BY dragon_no LIMIT ?"
        val args = if (query.isBlank()) arrayOf("$limit")
        else arrayOf("%$query%", "$limit")

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
