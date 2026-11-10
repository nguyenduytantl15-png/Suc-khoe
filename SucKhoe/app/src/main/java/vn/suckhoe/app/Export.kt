package vn.suckhoe.app

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/** Xuất báo cáo Excel (.xlsx) và sao lưu / khôi phục dữ liệu (.json). */
object Export {

    // ---------------- Excel ----------------

    private class Sheet(val name: String, val widths: List<Int>, val rows: List<List<Any>>)

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    private fun colName(i: Int): String {
        var n = i + 1
        var s = ""
        while (n > 0) { val r = (n - 1) % 26; s = ('A' + r).toString() + s; n = (n - 1) / 26 }
        return s
    }

    private fun sheetXml(sh: Sheet): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<cols>")
        sh.widths.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        sb.append("</cols><sheetData>")
        sh.rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                when (v) {
                    is Number -> sb.append("<c r=\"$ref\"><v>${v}</v></c>")
                    else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun writeXlsx(out: OutputStream, sheets: List<Sheet>) {
        val zip = ZipOutputStream(out)
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val ct = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in sheets.indices) ct.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        ct.append("</Types>")
        put("[Content_Types].xml", ct.toString())
        put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>")
        val wb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        wb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
        sheets.forEachIndexed { i, s -> wb.append("<sheet name=\"${esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>") }
        wb.append("</sheets></workbook>")
        put("xl/workbook.xml", wb.toString())
        val rels = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in sheets.indices) rels.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
        rels.append("</Relationships>")
        put("xl/_rels/workbook.xml.rels", rels.toString())
        sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        zip.finish()
        zip.flush()
    }

    fun excel(ctx: Context, out: OutputStream) {
        val db = Db(ctx)
        val height = Prefs.height(ctx)
        val sheets = ArrayList<Sheet>()

        val bp = ArrayList<List<Any>>()
        bp.add(listOf("Thời gian", "Tâm thu (mmHg)", "Tâm trương (mmHg)", "Mạch", "Đánh giá"))
        for (r in db.list(T.BP, 100000).reversed()) {
            val s = r.v1.roundToInt(); val d = r.v2.roundToInt()
            bp.add(listOf(Util.time(r.ts), s, d, if (r.v3 > 0) r.v3.roundToInt() else "", Health.bp(s, d).label))
        }
        sheets.add(Sheet("Huyết áp", listOf(20, 15, 17, 8, 22), bp))

        val med = ArrayList<List<Any>>()
        med.add(listOf("Ngày", "Giờ uống", "Thuốc", "Liều", "Lịch nhắc"))
        for (l in db.medLogs().reversed()) med.add(listOf(Util.fullFromKey(l.day), Util.hm(l.ts), l.name, l.dose, l.slot))
        sheets.add(Sheet("Uống thuốc", listOf(12, 10, 26, 14, 10), med))

        val hr = ArrayList<List<Any>>()
        hr.add(listOf("Thời gian", "Nhịp tim (bpm)", "Đánh giá"))
        for (r in db.list(T.HR, 100000).reversed()) hr.add(listOf(Util.time(r.ts), r.v1.roundToInt(), Health.hr(r.v1.roundToInt()).label))
        sheets.add(Sheet("Nhịp tim", listOf(20, 15, 16), hr))

        val sp = ArrayList<List<Any>>()
        sp.add(listOf("Thời gian", "SpO2 (%)", "Mạch", "Đánh giá"))
        for (r in db.list(T.SPO2, 100000).reversed()) sp.add(listOf(Util.time(r.ts), r.v1.roundToInt(), if (r.v3 > 0) r.v3.roundToInt() else "", Health.spo2(r.v1.roundToInt()).label))
        sheets.add(Sheet("SpO2", listOf(20, 10, 8, 16), sp))

        val w = ArrayList<List<Any>>()
        w.add(listOf("Thời gian", "Cân nặng (kg)", "Chiều cao (cm)", "BMI", "Đánh giá"))
        for (r in db.list(T.WEIGHT, 100000).reversed()) {
            val h = if (r.v2 > 0) r.v2 else height
            val b = Health.bmi(r.v1, h)
            w.add(listOf(Util.time(r.ts), r.v1, if (h > 0) h else "", if (b != null) (b * 10).roundToInt() / 10.0 else "", b?.let { Health.bmiStatus(it).label } ?: ""))
        }
        sheets.add(Sheet("Cân nặng", listOf(20, 14, 14, 8, 16), w))

        val st = ArrayList<List<Any>>()
        st.add(listOf("Ngày", "Số bước", "Quãng đường (km)"))
        for ((d, n) in db.allSteps()) st.add(listOf(Util.fullFromKey(d), n, (Health.distanceKm(n, height) * 100).roundToInt() / 100.0))
        sheets.add(Sheet("Bước chân", listOf(12, 10, 17), st))

        writeXlsx(out, sheets)
    }

    // ---------------- Sao lưu (JSON) ----------------

    fun backup(ctx: Context, out: OutputStream) {
        val db = Db(ctx)
        val root = JSONObject()
        root.put("app", "vn.suckhoe.app")
        root.put("version", 2)
        root.put("created", System.currentTimeMillis())

        val m = JSONArray()
        for (r in db.allRecs()) m.put(JSONObject().put("type", r.type).put("ts", r.ts).put("v1", r.v1).put("v2", r.v2).put("v3", r.v3))
        root.put("measurements", m)

        val s = JSONArray()
        for ((d, n) in db.allSteps()) s.put(JSONObject().put("day", d).put("steps", n))
        root.put("steps", s)

        val meds = JSONArray()
        for (x in db.meds()) meds.put(JSONObject().put("id", x.id).put("name", x.name).put("dose", x.dose)
            .put("hour", x.hour).put("minute", x.minute).put("enabled", x.enabled).put("created", x.created))
        root.put("meds", meds)

        val logs = JSONArray()
        for (l in db.medLogs(1_000_000)) logs.put(JSONObject().put("med_id", l.medId).put("name", l.name).put("dose", l.dose)
            .put("day", l.day).put("slot", l.slot).put("ts", l.ts))
        root.put("medlog", logs)

        root.put("prefs", JSONObject().put("height", Prefs.height(ctx)).put("goal", Prefs.goal(ctx)))
        out.write(root.toString(1).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Thay toàn bộ dữ liệu bằng file sao lưu. Trả về thông báo kết quả. */
    fun restore(ctx: Context, text: String): String {
        val root = JSONObject(text)
        if (root.optString("app") != "vn.suckhoe.app") throw IllegalArgumentException("Không phải file sao lưu của app Sức Khỏe.")
        val db = Db(ctx)
        // Hủy lịch nhắc cũ
        for (x in db.meds()) Reminders.cancel(ctx, x.id)
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            db.clearAll()
            val m = root.optJSONArray("measurements") ?: JSONArray()
            for (i in 0 until m.length()) {
                val o = m.getJSONObject(i)
                val cv = ContentValues()
                cv.put("type", o.getString("type")); cv.put("ts", o.getLong("ts"))
                cv.put("v1", o.optDouble("v1", 0.0)); cv.put("v2", o.optDouble("v2", 0.0)); cv.put("v3", o.optDouble("v3", 0.0))
                db.rawInsert("m", cv)
            }
            val s = root.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until s.length()) {
                val o = s.getJSONObject(i)
                val cv = ContentValues()
                cv.put("day", o.getString("day")); cv.put("steps", o.getInt("steps"))
                db.rawInsert("steps", cv)
            }
            val meds = root.optJSONArray("meds") ?: JSONArray()
            for (i in 0 until meds.length()) {
                val o = meds.getJSONObject(i)
                val cv = ContentValues()
                cv.put("id", o.getLong("id")); cv.put("name", o.getString("name")); cv.put("dose", o.optString("dose"))
                cv.put("hour", o.getInt("hour")); cv.put("minute", o.getInt("minute"))
                cv.put("enabled", if (o.optBoolean("enabled", true)) 1 else 0); cv.put("created", o.optLong("created", 0L))
                db.rawInsert("meds", cv)
            }
            val logs = root.optJSONArray("medlog") ?: JSONArray()
            for (i in 0 until logs.length()) {
                val o = logs.getJSONObject(i)
                val cv = ContentValues()
                cv.put("med_id", o.optLong("med_id")); cv.put("name", o.optString("name")); cv.put("dose", o.optString("dose"))
                cv.put("day", o.getString("day")); cv.put("slot", o.optString("slot")); cv.put("ts", o.getLong("ts"))
                db.rawInsert("medlog", cv)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        val p = root.optJSONObject("prefs")
        if (p != null) {
            val h = p.optDouble("height", 0.0)
            if (h > 0) Prefs.setHeight(ctx, h)
            val g = p.optInt("goal", 0)
            if (g > 0) Prefs.setGoal(ctx, g)
        }
        Reminders.scheduleAll(ctx)
        val n = (root.optJSONArray("measurements")?.length() ?: 0)
        val k = (root.optJSONArray("meds")?.length() ?: 0)
        return String.format(Locale.US, "Đã khôi phục %d lần đo, %d lịch thuốc.", n, k)
    }
}
