package com.hafidz.jamcounter

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Satu counter = satu tanggal ([day] = epoch-day).
 * [name] tetap ada di tabel (dulu nama), sekarang diisi teks tanggal. Jangan dihapus.
 */
data class Counter(
    val id: Long,
    val name: String,
    val color: Int,
    val value: Int,
    val day: Long,
    val note: String,
    val startValue: Int,
    val readOnly: Boolean
)

/**
 * Penyimpanan counter + log jam tiap tambahan.
 * Aturan data:
 *  - Tabel events hanya berisi +1 (delta selalu 1). Tombol minus MENGHAPUS catatan terakhir.
 *  - Tambah di counter bukan hari ini dicatat jam 23:00 pada tanggal counter itu.
 *  - Nilai (value) boleh lebih besar dari jumlah event karena ada Start value (tanpa jam).
 * Semua pemanggilan dilakukan dari main thread.
 */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "counter.db", null, 2) {

    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val cols = "id, name, color, value, day, note, start_value, read_only"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE counters(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "color INTEGER NOT NULL, " +
                "value INTEGER NOT NULL DEFAULT 0, " +
                "day INTEGER NOT NULL DEFAULT 0, " +
                "note TEXT NOT NULL DEFAULT '', " +
                "start_value INTEGER NOT NULL DEFAULT 0, " +
                "read_only INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE events(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "counter_id INTEGER NOT NULL, " +
                "ts INTEGER NOT NULL, " +
                "delta INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_events_counter_ts ON events(counter_id, ts)")
        db.execSQL("CREATE INDEX idx_counters_day ON counters(day)")
        val today = LocalDate.now()
        val cv = ContentValues().apply {
            put("name", Fmt.cardDate(today))
            put("color", Palette.colors[0])
            put("value", 0)
            put("day", today.toEpochDay())
        }
        db.insert("counters", null, cv)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateTo2(db)
    }

    /** v1 (nama bebas) -> v2 (satu counter = satu tanggal). Data lama dipertahankan. */
    private fun migrateTo2(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE counters ADD COLUMN day INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE counters ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE counters ADD COLUMN start_value INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE counters ADD COLUMN read_only INTEGER NOT NULL DEFAULT 0")

        val ids = ArrayList<Long>()
        val oldNames = ArrayList<String>()
        val firstTs = ArrayList<Long>()
        db.rawQuery(
            "SELECT c.id, c.name, (SELECT MIN(ts) FROM events e WHERE e.counter_id=c.id) " +
                "FROM counters c ORDER BY c.id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                ids.add(c.getLong(0))
                oldNames.add(c.getString(1))
                firstTs.add(if (c.isNull(2)) -1L else c.getLong(2))
            }
        }

        // Tanggal counter lama = tanggal catatan pertamanya (atau hari ini). Kalau bentrok, mundur sehari.
        val used = HashSet<Long>()
        val today = LocalDate.now().toEpochDay()
        for (i in ids.indices) {
            var d = if (firstTs[i] >= 0L) Days.dayOf(firstTs[i]) else today
            while (!used.add(d)) d--
            val cv = ContentValues().apply {
                put("day", d)
                put("name", Fmt.cardDate(LocalDate.ofEpochDay(d)))
                put("note", oldNames[i]) // nama lama jadi catatan
            }
            db.update("counters", cv, "id=?", arrayOf(ids[i].toString()))
        }

        // Model baru: event hanya +1. Sisa nilai tanpa event dianggap Start value.
        db.execSQL("DELETE FROM events WHERE delta<=0")
        db.execSQL(
            "UPDATE counters SET start_value = MAX(0, value - " +
                "(SELECT COUNT(*) FROM events e WHERE e.counter_id=counters.id))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_counters_day ON counters(day)")
    }

    fun notifyChanged() {
        for (l in listeners) l()
    }

    private fun read(c: Cursor): Counter = Counter(
        c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3),
        c.getLong(4), c.getString(5), c.getInt(6), c.getInt(7) != 0
    )

    /** Urut berdasarkan tanggal (lama ke baru). */
    fun counters(): List<Counter> {
        val out = ArrayList<Counter>()
        readableDatabase.rawQuery(
            "SELECT $cols FROM counters ORDER BY day, id", null
        ).use { c ->
            while (c.moveToNext()) out.add(read(c))
        }
        return out
    }

    fun counter(id: Long): Counter? {
        var result: Counter? = null
        readableDatabase.rawQuery(
            "SELECT $cols FROM counters WHERE id=?", arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) result = read(c)
        }
        return result
    }

    fun counterByDay(day: Long): Counter? {
        var result: Counter? = null
        readableDatabase.rawQuery(
            "SELECT $cols FROM counters WHERE day=? ORDER BY id LIMIT 1", arrayOf(day.toString())
        ).use { c ->
            if (c.moveToFirst()) result = read(c)
        }
        return result
    }

    /** Apakah [day] sudah dipakai counter lain (selain [exceptId]). */
    fun dayTaken(day: Long, exceptId: Long): Boolean {
        return readableDatabase.rawQuery(
            "SELECT 1 FROM counters WHERE day=? AND id<>? LIMIT 1",
            arrayOf(day.toString(), exceptId.toString())
        ).use { c -> c.moveToFirst() }
    }

    /** Buat counter baru. Mengembalikan -1 kalau tanggal sudah ada. */
    fun addCounter(day: Long, note: String, color: Int, start: Int, readOnly: Boolean): Long {
        if (dayTaken(day, -1L)) return -1L
        val cv = ContentValues().apply {
            put("name", Fmt.cardDate(LocalDate.ofEpochDay(day)))
            put("color", color)
            put("value", start)
            put("day", day)
            put("note", note)
            put("start_value", start)
            put("read_only", if (readOnly) 1 else 0)
        }
        val id = writableDatabase.insert("counters", null, cv)
        notifyChanged()
        return id
    }

    /** Ubah counter. Kalau Start value berubah, nilai sekarang ikut bergeser sebesar selisihnya. */
    fun updateCounter(id: Long, day: Long, note: String, color: Int, start: Int, readOnly: Boolean): Boolean {
        if (dayTaken(day, id)) return false
        val old = counter(id) ?: return false
        val newValue = maxOf(0, old.value + (start - old.startValue))
        val cv = ContentValues().apply {
            put("name", Fmt.cardDate(LocalDate.ofEpochDay(day)))
            put("color", color)
            put("value", newValue)
            put("day", day)
            put("note", note)
            put("start_value", start)
            put("read_only", if (readOnly) 1 else 0)
        }
        writableDatabase.update("counters", cv, "id=?", arrayOf(id.toString()))
        notifyChanged()
        return true
    }

    fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events WHERE counter_id=?", arrayOf<Any>(id))
            db.execSQL("DELETE FROM counters WHERE id=?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    /** Waktu catatan: hari ini = jam sekarang, tanggal lain = jam 23:00 di tanggal counter itu. */
    private fun eventTime(day: Long): Long =
        if (day == LocalDate.now().toEpochDay()) System.currentTimeMillis() else Days.millisAt(day, 23)

    /**
     * delta > 0: nilai +1 dan catat jamnya. delta < 0: nilai -1 dan HAPUS catatan jam terbaru
     * (kalau masih ada). Counter Read Only tidak bisa diubah. Nilai tidak pernah di bawah 0.
     */
    fun change(id: Long, delta: Int): Boolean {
        val db = writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            var cur = -1
            var day = 0L
            var readOnly = 0
            db.rawQuery(
                "SELECT value, day, read_only FROM counters WHERE id=?", arrayOf(id.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    cur = c.getInt(0)
                    day = c.getLong(1)
                    readOnly = c.getInt(2)
                }
            }
            if (cur >= 0 && readOnly == 0) {
                if (delta > 0) {
                    db.execSQL("UPDATE counters SET value=value+1 WHERE id=?", arrayOf<Any>(id))
                    db.execSQL(
                        "INSERT INTO events(counter_id, ts, delta) VALUES(?,?,1)",
                        arrayOf<Any>(id, eventTime(day))
                    )
                    changed = true
                } else if (delta < 0 && cur > 0) {
                    db.execSQL("UPDATE counters SET value=value-1 WHERE id=?", arrayOf<Any>(id))
                    db.execSQL(
                        "DELETE FROM events WHERE id=(SELECT id FROM events WHERE counter_id=? " +
                            "ORDER BY ts DESC, id DESC LIMIT 1)",
                        arrayOf<Any>(id)
                    )
                    changed = true
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (changed) notifyChanged()
        return changed
    }

    /** Reset tanggal ini: semua catatan jam dihapus, nilai kembali ke Start value. */
    fun reset(id: Long): Boolean {
        val db = writableDatabase
        var done = false
        db.beginTransaction()
        try {
            var readOnly = 1
            var found = false
            db.rawQuery(
                "SELECT read_only FROM counters WHERE id=?", arrayOf(id.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    found = true
                    readOnly = c.getInt(0)
                }
            }
            if (found && readOnly == 0) {
                db.execSQL("DELETE FROM events WHERE counter_id=?", arrayOf<Any>(id))
                db.execSQL("UPDATE counters SET value=start_value WHERE id=?", arrayOf<Any>(id))
                done = true
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (done) notifyChanged()
        return done
    }

    fun eventCount(id: Long): Int {
        var n = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE counter_id=?", arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) n = c.getInt(0) }
        return n
    }

    /** Waktu (ms) semua catatan +1 milik counter [id]. */
    fun eventTimes(id: Long): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT ts FROM events WHERE counter_id=? AND delta>0", arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** epoch-day -> nilai counter, untuk hari dalam [fromDay, toDay]. */
    fun valuesBetween(fromDay: Long, toDay: Long): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        readableDatabase.rawQuery(
            "SELECT day, value FROM counters WHERE day>=? AND day<=?",
            arrayOf(fromDay.toString(), toDay.toString())
        ).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
        }
        return out
    }

    fun sumBetween(fromDay: Long, toDay: Long): Int {
        var s = 0
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(value),0) FROM counters WHERE day>=? AND day<=?",
            arrayOf(fromDay.toString(), toDay.toString())
        ).use { c -> if (c.moveToFirst()) s = c.getInt(0) }
        return s
    }

    fun minDay(): Long? {
        var d: Long? = null
        readableDatabase.rawQuery("SELECT MIN(day) FROM counters", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) d = c.getLong(0)
        }
        return d
    }

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(ctx: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(ctx).also { instance = it }
            }
    }
}
