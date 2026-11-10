package vn.suckhoe.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Một bản ghi đo. v1/v2/v3 tùy loại:
 *  HR: v1 = bpm
 *  BP: v1 = tâm thu, v2 = tâm trương, v3 = mạch (0 nếu không nhập)
 *  SPO2: v1 = %, v3 = mạch (0 nếu không nhập)
 *  WEIGHT: v1 = kg, v2 = chiều cao cm lúc nhập
 */
data class Rec(val id: Long, val type: String, val ts: Long, val v1: Double, val v2: Double, val v3: Double)

/** Một lịch nhắc uống thuốc (mỗi giờ uống là một lịch riêng). */
data class Med(
    val id: Long,
    val name: String,
    val dose: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val created: Long = System.currentTimeMillis()
) {
    val slot: String get() = String.format(Locale.US, "%02d:%02d", hour, minute)
}

/** Một lần ghi nhận "đã uống thuốc". */
data class MedLog(val id: Long, val medId: Long, val name: String, val dose: String, val day: String, val slot: String, val ts: Long)

object T {
    const val HR = "HR"
    const val BP = "BP"
    const val SPO2 = "SPO2"
    const val WEIGHT = "WEIGHT"
}

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "suckhoe.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE m (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, ts INTEGER NOT NULL, v1 REAL, v2 REAL, v3 REAL)")
        db.execSQL("CREATE INDEX idx_m ON m(type, ts)")
        db.execSQL("CREATE TABLE steps (day TEXT PRIMARY KEY, steps INTEGER NOT NULL)")
        createV2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meds (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, dose TEXT, hour INTEGER NOT NULL, minute INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, created INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS medlog (id INTEGER PRIMARY KEY AUTOINCREMENT, med_id INTEGER, name TEXT, dose TEXT, day TEXT NOT NULL, slot TEXT, ts INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_medlog ON medlog(day, med_id)")
    }

    // ---------- Chỉ số đo ----------

    fun add(type: String, v1: Double, v2: Double = 0.0, v3: Double = 0.0, ts: Long = System.currentTimeMillis()) {
        val cv = ContentValues()
        cv.put("type", type)
        cv.put("ts", ts)
        cv.put("v1", v1)
        cv.put("v2", v2)
        cv.put("v3", v3)
        writableDatabase.insert("m", null, cv)
    }

    /** Mới nhất trước. */
    fun list(type: String, limit: Int = 300): List<Rec> {
        val out = ArrayList<Rec>()
        readableDatabase.rawQuery(
            "SELECT id, type, ts, v1, v2, v3 FROM m WHERE type = ? ORDER BY ts DESC LIMIT $limit",
            arrayOf(type)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Rec(c.getLong(0), c.getString(1), c.getLong(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)))
            }
        }
        return out
    }

    fun allRecs(): List<Rec> {
        val out = ArrayList<Rec>()
        readableDatabase.rawQuery("SELECT id, type, ts, v1, v2, v3 FROM m ORDER BY ts", null).use { c ->
            while (c.moveToNext()) out.add(Rec(c.getLong(0), c.getString(1), c.getLong(2), c.getDouble(3), c.getDouble(4), c.getDouble(5)))
        }
        return out
    }

    fun latest(type: String): Rec? = list(type, 1).firstOrNull()

    fun delete(id: Long) {
        writableDatabase.delete("m", "id = ?", arrayOf(id.toString()))
    }

    // ---------- Bước chân ----------

    fun getSteps(day: String): Int {
        return readableDatabase.rawQuery("SELECT steps FROM steps WHERE day = ?", arrayOf(day)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun setSteps(day: String, steps: Int) {
        val cv = ContentValues()
        cv.put("day", day)
        cv.put("steps", steps)
        writableDatabase.insertWithOnConflict("steps", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allSteps(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        readableDatabase.rawQuery("SELECT day, steps FROM steps ORDER BY day", null).use { c ->
            while (c.moveToNext()) out.add(Pair(c.getString(0), c.getInt(1)))
        }
        return out
    }

    /** N ngày gần nhất, cũ -> mới. first = "yyyy-MM-dd". */
    fun stepsLastDays(n: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
        for (i in 0 until n) {
            val d = Util.dayKey(cal.time)
            out.add(Pair(d, getSteps(d)))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    // ---------- Thuốc ----------

    fun meds(): List<Med> {
        val out = ArrayList<Med>()
        readableDatabase.rawQuery("SELECT id, name, dose, hour, minute, enabled, created FROM meds ORDER BY hour, minute, name", null).use { c ->
            while (c.moveToNext()) {
                out.add(Med(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getInt(3), c.getInt(4), c.getInt(5) != 0, c.getLong(6)))
            }
        }
        return out
    }

    fun med(id: Long): Med? = meds().firstOrNull { it.id == id }

    /** Thêm mới (id = 0) hoặc cập nhật. Trả về id. */
    fun saveMed(m: Med): Long {
        val cv = ContentValues()
        cv.put("name", m.name)
        cv.put("dose", m.dose)
        cv.put("hour", m.hour)
        cv.put("minute", m.minute)
        cv.put("enabled", if (m.enabled) 1 else 0)
        return if (m.id == 0L) {
            cv.put("created", m.created)
            writableDatabase.insert("meds", null, cv)
        } else {
            writableDatabase.update("meds", cv, "id = ?", arrayOf(m.id.toString()))
            m.id
        }
    }

    fun deleteMed(id: Long) {
        writableDatabase.delete("meds", "id = ?", arrayOf(id.toString()))
    }

    fun logMed(m: Med, ts: Long = System.currentTimeMillis()) {
        val cv = ContentValues()
        cv.put("med_id", m.id)
        cv.put("name", m.name)
        cv.put("dose", m.dose)
        cv.put("day", Util.dayKey(Date(ts)))
        cv.put("slot", m.slot)
        cv.put("ts", ts)
        writableDatabase.insert("medlog", null, cv)
    }

    fun deleteMedLog(id: Long) {
        writableDatabase.delete("medlog", "id = ?", arrayOf(id.toString()))
    }

    private fun readLogs(where: String, args: Array<String>?, limit: Int): List<MedLog> {
        val out = ArrayList<MedLog>()
        readableDatabase.rawQuery(
            "SELECT id, med_id, name, dose, day, slot, ts FROM medlog $where ORDER BY ts DESC LIMIT $limit", args
        ).use { c ->
            while (c.moveToNext()) {
                out.add(MedLog(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4), c.getString(5) ?: "", c.getLong(6)))
            }
        }
        return out
    }

    fun medLogs(limit: Int = 5000): List<MedLog> = readLogs("", null, limit)

    fun medLogsOfDay(day: String): List<MedLog> = readLogs("WHERE day = ?", arrayOf(day), 500)

    /** Lần ghi "đã uống" của lịch này trong ngày (null nếu chưa). */
    fun takenOn(medId: Long, day: String): MedLog? =
        readLogs("WHERE med_id = ? AND day = ?", arrayOf(medId.toString(), day), 1).firstOrNull()

    // ---------- Sao lưu / khôi phục ----------

    fun clearAll() {
        val w = writableDatabase
        w.delete("m", null, null)
        w.delete("steps", null, null)
        w.delete("meds", null, null)
        w.delete("medlog", null, null)
    }

    fun rawInsert(table: String, cv: ContentValues) {
        writableDatabase.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

object Util {
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm  dd/MM/yyyy", Locale.US)
    private val hmFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val shortFmt = SimpleDateFormat("dd/MM", Locale.US)

    fun dayKey(d: Date): String = dayFmt.format(d)
    fun today(): String = dayKey(Date())
    fun time(ts: Long): String = timeFmt.format(Date(ts))
    fun hm(ts: Long): String = hmFmt.format(Date(ts))
    fun short(ts: Long): String = shortFmt.format(Date(ts))
    fun shortFromKey(key: String): String = if (key.length == 10) key.substring(8, 10) + "/" + key.substring(5, 7) else key
    fun fullFromKey(key: String): String = if (key.length == 10) shortFromKey(key) + "/" + key.substring(0, 4) else key

    fun fmt(v: Double, digits: Int = 1): String {
        return if (v == Math.floor(v)) v.toLong().toString()
        else String.format(Locale.US, "%.${digits}f", v)
    }

    fun parse(s: CharSequence?): Double? = s?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("suckhoe", Context.MODE_PRIVATE)

    fun height(ctx: Context): Double = p(ctx).getFloat("height", 0f).toDouble()
    fun setHeight(ctx: Context, v: Double) = p(ctx).edit().putFloat("height", v.toFloat()).apply()

    fun goal(ctx: Context): Int = p(ctx).getInt("goal", 6000)
    fun setGoal(ctx: Context, v: Int) = p(ctx).edit().putInt("goal", v).apply()

    fun lastCounter(ctx: Context): Float = p(ctx).getFloat("lastCounter", -1f)
    fun setLastCounter(ctx: Context, v: Float) = p(ctx).edit().putFloat("lastCounter", v).apply()

    /** Hệ số cỡ chữ: 1.0 vừa, 1.2 lớn (mặc định), 1.4 rất lớn. */
    fun fontScale(ctx: Context): Float = p(ctx).getFloat("fontScale", 1.2f)
    fun setFontScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("fontScale", v).apply()

    fun notifAsked(ctx: Context): Boolean = p(ctx).getBoolean("notifAsked", false)
    fun setNotifAsked(ctx: Context) = p(ctx).edit().putBoolean("notifAsked", true).apply()
}
