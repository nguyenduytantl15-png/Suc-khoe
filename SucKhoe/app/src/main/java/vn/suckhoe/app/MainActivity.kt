package vn.suckhoe.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var steps: StepTracker
    private lateinit var content: FrameLayout
    private val navItems = ArrayList<TextView>()
    private var current = 0
    private var historyTab = 0
    private var hr: HeartRateMonitor? = null
    private var stepListener: ((Int) -> Unit)? = null
    private var pendingRestore: String? = null

    companion object {
        const val TAB_HOME = 0
        const val TAB_HR = 1
        const val TAB_MED = 2
        const val TAB_INPUT = 3
        const val TAB_HISTORY = 4
        private const val REQ_START = 1
        private const val REQ_CAMERA = 2
        private const val REQ_NOTIF = 3
        private const val REQ_EXCEL = 10
        private const val REQ_BACKUP = 11
        private const val REQ_RESTORE = 12
        private val TABS = listOf("🏠\nTổng quan", "❤️\nĐo tim", "💊\nThuốc", "✍️\nNhập", "📊\nLịch sử")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        Scale.f = Prefs.fontScale(this)
        db = Db(this)
        steps = StepTracker(this, db) { n -> stepListener?.invoke(n) }
        Reminders.scheduleAll(this)

        val root = vbox()
        root.setBackgroundColor(C.BG)
        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))

        val nav = hbox()
        nav.setBackgroundColor(C.CARD)
        nav.elevation = dpf(8f)
        TABS.forEachIndexed { i, label ->
            val t = text(label, 13f, C.SUB, scaled = false)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(8), 0, dp(8))
            t.setOnClickListener { show(i) }
            navItems.add(t)
            nav.addView(t, lp(0, WRAP, 1f))
        }
        root.addView(nav, lp())
        setContentView(root)

        // Xin các quyền cần thiết một lần khi mở app
        val need = ArrayList<String>()
        if (!hasStepPermission()) need.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.notifAsked(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
            Prefs.setNotifAsked(this)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_START)

        show(intent?.getIntExtra(Reminders.EXTRA_TAB, TAB_HOME) ?: TAB_HOME)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val tab = intent?.getIntExtra(Reminders.EXTRA_TAB, -1) ?: -1
        if (tab >= 0) show(tab)
    }

    override fun onResume() {
        super.onResume()
        if (hasStepPermission()) steps.start()
        if (current == TAB_HOME || current == TAB_MED) show(current)
    }

    override fun onPause() {
        super.onPause()
        steps.stop()
        if (hr != null) {
            stopHr()
            if (current == TAB_HR) show(TAB_HR)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current != TAB_HOME) show(TAB_HOME) else super.onBackPressed()
    }

    private fun hasStepPermission(): Boolean =
        Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_START, REQ_NOTIF -> {
                if (hasStepPermission()) steps.start()
                show(current)
            }
            REQ_CAMERA -> {
                if (ok && current == TAB_HR) show(TAB_HR, autoStart = true)
                else if (!ok) toast("Cần quyền camera để đo nhịp tim.")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------- Điều hướng ----------------

    private fun show(tab: Int, autoStart: Boolean = false) {
        if (tab != TAB_HR && hr != null) stopHr()
        current = tab
        stepListener = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        navItems.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) C.PRIMARY else C.SUB)
            t.setTypeface(null, if (i == tab) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        val body = when (tab) {
            TAB_HOME -> homeScreen()
            TAB_HR -> measureScreen(autoStart)
            TAB_MED -> medScreen()
            TAB_INPUT -> inputScreen()
            else -> historyScreen()
        }
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        body.setPadding(dp(16), dp(16), dp(16), dp(24))
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        content.removeAllViews()
        content.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun title(s: String, sub: String? = null): View {
        val v = vbox()
        v.addView(text(s, 24f, C.TEXT, true))
        if (sub != null) v.addView(text(sub, 14f, C.SUB))
        v.setPadding(0, 0, 0, dp(12))
        return v
    }

    private fun gap(h: Int = 12): View {
        val v = View(this)
        v.layoutParams = lp(MATCH, dp(h))
        return v
    }

    // ---------------- Tổng quan ----------------

    private fun homeScreen(): LinearLayout {
        val col = vbox()
        val date = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("vi", "VN")).format(Date())
        col.addView(title("Sức khỏe hôm nay", date.replaceFirstChar { it.uppercase() }))
        if (db.meds().any { it.enabled }) {
            col.addView(medTodayCard(), lp())
            col.addView(gap(14))
        }

        // Thẻ bước chân
        val sc = card()
        sc.addView(text("🚶 Bước chân hôm nay", 16f, C.TEXT, true))
        val row = hbox()
        val ring = RingView(this)
        row.addView(ring, lp(dp(150), dp(150)))
        val info = vbox()
        info.setPadding(dp(12), 0, 0, 0)
        val tDist = text("", 15f, C.TEXT)
        val tKcal = text("", 15f, C.TEXT)
        val tGoal = text("", 13f, C.SUB)
        info.addView(tDist); info.addView(gap(6)); info.addView(tKcal); info.addView(gap(6)); info.addView(tGoal)
        row.addView(info, lp(0, WRAP, 1f))
        sc.addView(row)

        val goal = Prefs.goal(this)
        val height = Prefs.height(this)
        val weight = db.latest(T.WEIGHT)?.v1 ?: 0.0
        val update = { n: Int ->
            ring.set(n.toFloat() / goal, "%,d".format(n).replace(',', '.'), "/ ${"%,d".format(goal).replace(',', '.')} bước")
            tDist.text = "📏 ${String.format(Locale.US, "%.2f", Health.distanceKm(n, height))} km"
            tKcal.text = "🔥 ${Health.kcal(n, weight).roundToInt()} kcal"
            tGoal.text = if (n >= goal) "🎉 Đã đạt mục tiêu!" else "Còn ${goal - n} bước nữa để đạt mục tiêu"
        }
        update(db.getSteps(Util.today()))
        stepListener = update

        if (!steps.available) {
            sc.addView(text("Điện thoại này không có cảm biến đếm bước.", 13f, C.ORANGE))
        } else if (!hasStepPermission()) {
            sc.addView(gap(8))
            sc.addView(button("Cho phép đếm bước chân") {
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_STEPS)
            }, lp())
        }
        col.addView(sc, lp())
        col.addView(gap(14))

        // Lưới chỉ số
        val hrRec = db.latest(T.HR)
        val bpRec = db.latest(T.BP)
        val spRec = db.latest(T.SPO2)
        val wRec = db.latest(T.WEIGHT)

        val r1 = hbox()
        r1.addView(metricCard("❤️ Nhịp tim", hrRec?.let { it.v1.roundToInt().toString() }, "bpm",
            hrRec?.let { Health.hr(it.v1.roundToInt()) }, hrRec?.ts) { show(TAB_HR) }, lp(0, WRAP, 1f).m(0, 0, dp(6), 0))
        r1.addView(metricCard("🩺 Huyết áp", bpRec?.let { "${it.v1.roundToInt()}/${it.v2.roundToInt()}" }, "mmHg",
            bpRec?.let { Health.bp(it.v1.roundToInt(), it.v2.roundToInt()) }, bpRec?.ts) { show(TAB_INPUT) }, lp(0, WRAP, 1f).m(dp(6), 0, 0, 0))
        col.addView(r1, lp())
        col.addView(gap(12))

        val r2 = hbox()
        r2.addView(metricCard("🫁 SpO2", spRec?.let { it.v1.roundToInt().toString() }, "%",
            spRec?.let { Health.spo2(it.v1.roundToInt()) }, spRec?.ts) { show(TAB_INPUT) }, lp(0, WRAP, 1f).m(0, 0, dp(6), 0))
        val bmi = wRec?.let { Health.bmi(it.v1, if (it.v2 > 0) it.v2 else height) }
        r2.addView(metricCard("⚖️ Cân nặng", wRec?.let { Util.fmt(it.v1) }, if (bmi != null) "kg · BMI ${String.format(Locale.US, "%.1f", bmi)}" else "kg",
            bmi?.let { Health.bmiStatus(it) }, wRec?.ts) { show(TAB_INPUT) }, lp(0, WRAP, 1f).m(dp(6), 0, 0, 0))
        col.addView(r2, lp())

        col.addView(gap(16))
        col.addView(text("⚠️ Ứng dụng chỉ hỗ trợ theo dõi, không thay thế thiết bị y tế hay chẩn đoán của bác sĩ.", 12f, C.SUB))
        return col
    }

    private fun metricCard(title: String, value: String?, unit: String, st: Status?, ts: Long?, onClick: () -> Unit): View {
        val c = card()
        c.setPadding(dp(14), dp(14), dp(14), dp(14))
        c.addView(text(title, 14f, C.SUB, true))
        c.addView(gap(6))
        val vr = hbox()
        vr.gravity = Gravity.BOTTOM
        vr.addView(text(value ?: "--", 26f, C.TEXT, true))
        c.addView(vr)
        c.addView(text(unit, 12f, C.SUB))
        c.addView(gap(6))
        if (st != null) c.addView(badge(st), lp(WRAP, WRAP)) else c.addView(text("Chưa có dữ liệu", 12f, C.SUB))
        if (ts != null) c.addView(text(Util.time(ts), 11f, C.SUB).also { it.setPadding(0, dp(4), 0, 0) })
        c.setOnClickListener { onClick() }
        return c
    }

    // ---------------- Đo nhịp tim ----------------

    private fun measureScreen(autoStart: Boolean): LinearLayout {
        val col = vbox()
        col.addView(title("Đo nhịp tim", "Dùng camera sau và đèn flash"))

        val guide = card()
        guide.addView(text("Cách đo", 15f, C.TEXT, true))
        guide.addView(text(
            "1. Ngồi yên, nghỉ ngơi khoảng 1–2 phút.\n" +
            "2. Đặt nhẹ đầu ngón trỏ che kín CẢ camera sau và đèn flash.\n" +
            "3. Không ấn quá mạnh, giữ yên tay khoảng 30 giây.\n" +
            "Lưu ý: đèn flash có thể làm camera hơi nóng.", 14f, C.SUB))
        col.addView(guide, lp())
        col.addView(gap(12))

        val mc = card()
        mc.gravity = Gravity.CENTER_HORIZONTAL
        val wave = WaveView(this)
        mc.addView(wave, lp(MATCH, dp(140)))
        val bpmRow = hbox()
        bpmRow.gravity = Gravity.CENTER
        val tBpm = text("--", 56f, C.PRIMARY, true)
        bpmRow.addView(tBpm)
        bpmRow.addView(text(" bpm", 18f, C.SUB))
        mc.addView(bpmRow, lp(WRAP, WRAP))
        val tStatus = text("Nhấn \"Bắt đầu đo\"", 15f, C.SUB)
        tStatus.gravity = Gravity.CENTER
        mc.addView(tStatus, lp())
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progressTintList = ColorStateList.valueOf(C.PRIMARY)
        mc.addView(bar, lp().m(0, dp(10), 0, dp(10)))
        val resultBox = vbox()
        mc.addView(resultBox, lp())
        val btn = button("Bắt đầu đo") {}
        mc.addView(btn, lp())
        col.addView(mc, lp())

        fun resetUi() {
            btn.text = "Bắt đầu đo"
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val listener = object : HeartRateMonitor.Listener {
            override fun onSample(value: Double, fingerOn: Boolean, elapsedSec: Double) {
                if (hr == null) return
                if (!fingerOn) {
                    wave.clear()
                    tStatus.text = "👆 Đặt ngón tay che kín camera và đèn flash"
                    tStatus.setTextColor(C.ORANGE)
                    tBpm.text = "--"
                    bar.progress = 0
                    return
                }
                wave.add(value.toFloat())
                if (elapsedSec <= 0.0) {
                    tStatus.text = "Đã nhận ngón tay, giữ yên…"
                } else {
                    val left = (HeartRateMonitor.DURATION_SEC - elapsedSec).coerceAtLeast(0.0).roundToInt()
                    tStatus.text = "Đang đo… giữ yên tay (còn $left giây)"
                }
                tStatus.setTextColor(C.SUB)
                bar.progress = (elapsedSec / HeartRateMonitor.DURATION_SEC * 100).roundToInt().coerceIn(0, 100)
            }

            override fun onLive(bpm: Int, quality: Double) {
                if (hr == null) return
                tBpm.text = bpm.toString()
            }

            override fun onDone(result: SignalProc.Result?) {
                hr = null
                resetUi()
                bar.progress = 100
                resultBox.removeAllViews()
                if (result == null) {
                    tBpm.text = "--"
                    tStatus.text = "Không đo được tín hiệu ổn định. Hãy thử lại: giữ yên tay, che kín camera và đèn, đừng ấn quá mạnh."
                    tStatus.setTextColor(C.RED)
                    return
                }
                tBpm.text = result.bpm.toString()
                db.add(T.HR, result.bpm.toDouble())
                val st = Health.hr(result.bpm)
                tStatus.text = "Đã lưu kết quả · Chất lượng tín hiệu: " + when {
                    result.quality >= 0.6 -> "tốt"
                    result.quality >= 0.4 -> "khá"
                    else -> "yếu (nên đo lại)"
                }
                tStatus.setTextColor(C.SUB)
                resultBox.addView(badge(st), lp(WRAP, WRAP).m(0, 0, 0, dp(6)))
                resultBox.addView(text(st.advice, 14f, C.TEXT), lp().m(0, 0, 0, dp(10)))
            }

            override fun onError(msg: String) {
                hr = null
                resetUi()
                tStatus.text = msg
                tStatus.setTextColor(C.RED)
            }
        }

        fun startMeasure() {
            if (!hasCameraPermission()) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                return
            }
            resultBox.removeAllViews()
            wave.clear()
            tBpm.text = "--"
            bar.progress = 0
            btn.text = "Dừng"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val m = HeartRateMonitor(this, listener)
            hr = m
            m.start()
        }

        btn.setOnClickListener {
            if (hr != null) {
                stopHr()
                resetUi()
                tStatus.text = "Đã dừng."
                tStatus.setTextColor(C.SUB)
                bar.progress = 0
            } else startMeasure()
        }

        col.addView(gap(12))
        col.addView(text("Kết quả đo bằng camera chỉ mang tính tham khảo. Nếu có triệu chứng bất thường, hãy dùng thiết bị y tế và hỏi ý kiến bác sĩ.", 12f, C.SUB))

        if (autoStart) btn.post { startMeasure() }
        return col
    }

    private fun stopHr() {
        val m = hr
        hr = null
        m?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------- Nhập chỉ số ----------------

    private fun field(label: String, e: EditText): View {
        val v = vbox()
        v.addView(text(label, 13f, C.SUB), lp().m(0, 0, 0, dp(4)))
        v.addView(e, lp())
        return v
    }

    private fun saved(title: String, st: Status) {
        AlertDialog.Builder(this)
            .setTitle("Đã lưu: $title")
            .setMessage("Đánh giá: ${st.label}\n\n${st.advice}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun inputScreen(): LinearLayout {
        val col = vbox()
        col.addView(title("Nhập chỉ số", "Từ máy đo huyết áp, máy kẹp SpO2, cân"))

        // Huyết áp
        val bp = card()
        bp.addView(text("🩺 Huyết áp", 17f, C.TEXT, true), lp().m(0, 0, 0, dp(10)))
        val eSys = input("VD: 120")
        val eDia = input("VD: 80")
        val ePulse = input("Không bắt buộc")
        val bpRow = hbox()
        bpRow.addView(field("Tâm thu (số trên)", eSys), lp(0, WRAP, 1f).m(0, 0, dp(6), 0))
        bpRow.addView(field("Tâm trương (số dưới)", eDia), lp(0, WRAP, 1f).m(dp(6), 0, 0, 0))
        bp.addView(bpRow, lp())
        bp.addView(field("Mạch (lần/phút)", ePulse), lp().m(0, dp(10), 0, dp(12)))
        bp.addView(button("Lưu huyết áp") {
            val s = Util.parse(eSys.text)?.roundToInt()
            val d = Util.parse(eDia.text)?.roundToInt()
            val p = Util.parse(ePulse.text)?.roundToInt()
            when {
                s == null || d == null -> toast("Hãy nhập đủ số tâm thu và tâm trương.")
                s !in 60..260 || d !in 30..160 -> toast("Giá trị huyết áp không hợp lệ.")
                s <= d -> toast("Tâm thu (số trên) phải lớn hơn tâm trương (số dưới).")
                p != null && p !in 30..220 -> toast("Mạch không hợp lệ.")
                else -> {
                    db.add(T.BP, s.toDouble(), d.toDouble(), (p ?: 0).toDouble())
                    if (p != null) db.add(T.HR, p.toDouble())
                    eSys.setText(""); eDia.setText(""); ePulse.setText("")
                    saved("$s/$d mmHg", Health.bp(s, d))
                }
            }
        }, lp())
        col.addView(bp, lp())
        col.addView(gap(14))

        // SpO2
        val sp = card()
        sp.addView(text("🫁 SpO2 (độ bão hòa oxy)", 17f, C.TEXT, true), lp().m(0, 0, 0, dp(10)))
        val eSp = input("VD: 98")
        val eSpPulse = input("Không bắt buộc")
        val spRow = hbox()
        spRow.addView(field("SpO2 (%)", eSp), lp(0, WRAP, 1f).m(0, 0, dp(6), 0))
        spRow.addView(field("Mạch (lần/phút)", eSpPulse), lp(0, WRAP, 1f).m(dp(6), 0, 0, 0))
        sp.addView(spRow, lp().m(0, 0, 0, dp(12)))
        sp.addView(button("Lưu SpO2", C.BLUE) {
            val v = Util.parse(eSp.text)?.roundToInt()
            val p = Util.parse(eSpPulse.text)?.roundToInt()
            when {
                v == null -> toast("Hãy nhập SpO2.")
                v !in 50..100 -> toast("SpO2 phải trong khoảng 50–100%.")
                p != null && p !in 30..220 -> toast("Mạch không hợp lệ.")
                else -> {
                    db.add(T.SPO2, v.toDouble(), 0.0, (p ?: 0).toDouble())
                    if (p != null) db.add(T.HR, p.toDouble())
                    eSp.setText(""); eSpPulse.setText("")
                    saved("SpO2 $v%", Health.spo2(v))
                }
            }
        }, lp())
        col.addView(sp, lp())
        col.addView(gap(14))

        // Cân nặng
        val wc = card()
        wc.addView(text("⚖️ Cân nặng & BMI", 17f, C.TEXT, true), lp().m(0, 0, 0, dp(10)))
        val eW = input("VD: 60.5", true)
        val eH = input("VD: 165", true)
        val h0 = Prefs.height(this)
        if (h0 > 0) eH.setText(Util.fmt(h0))
        val wRow = hbox()
        wRow.addView(field("Cân nặng (kg)", eW), lp(0, WRAP, 1f).m(0, 0, dp(6), 0))
        wRow.addView(field("Chiều cao (cm)", eH), lp(0, WRAP, 1f).m(dp(6), 0, 0, 0))
        wc.addView(wRow, lp().m(0, 0, 0, dp(12)))
        wc.addView(button("Lưu cân nặng", C.PURPLE) {
            val w = Util.parse(eW.text)
            val h = Util.parse(eH.text)
            when {
                w == null || h == null -> toast("Hãy nhập cân nặng và chiều cao.")
                w < 2 || w > 300 -> toast("Cân nặng không hợp lệ.")
                h < 50 || h > 250 -> toast("Chiều cao không hợp lệ.")
                else -> {
                    Prefs.setHeight(this, h)
                    db.add(T.WEIGHT, w, h)
                    eW.setText("")
                    val b = Health.bmi(w, h)!!
                    val st = Health.bmiStatus(b)
                    saved("${Util.fmt(w)} kg · BMI ${String.format(Locale.US, "%.1f", b)}", st)
                }
            }
        }, lp())
        col.addView(wc, lp())
        col.addView(gap(14))

        // Mục tiêu bước
        val gc = card()
        gc.addView(text("🎯 Mục tiêu bước mỗi ngày", 17f, C.TEXT, true), lp().m(0, 0, 0, dp(10)))
        val eG = input("VD: 6000")
        eG.setText(Prefs.goal(this).toString())
        gc.addView(eG, lp().m(0, 0, 0, dp(12)))
        gc.addView(button("Lưu mục tiêu", C.TEAL) {
            val g = Util.parse(eG.text)?.roundToInt()
            if (g == null || g !in 500..100000) toast("Mục tiêu nên trong khoảng 500–100.000 bước.")
            else { Prefs.setGoal(this, g); toast("Đã lưu mục tiêu $g bước/ngày.") }
        }, lp())
        col.addView(gc, lp())
        col.addView(gap(14))
        col.addView(settingsCard(), lp())
        return col
    }

    // ---------------- Lịch sử ----------------

    private fun historyScreen(): LinearLayout {
        val col = vbox()
        col.addView(title("Lịch sử", "Nhấn giữ một dòng để xóa"))

        val names = listOf("Bước chân", "Nhịp tim", "Huyết áp", "SpO2", "Cân nặng")
        val chips = hbox()
        names.forEachIndexed { i, n ->
            val t = text(n, 14f, if (i == historyTab) 0xFFFFFFFF.toInt() else C.TEXT, i == historyTab)
            t.background = if (i == historyTab) rounded(C.PRIMARY, dpf(18f)) else rounded(C.CARD, dpf(18f), C.LINE, dp(1))
            t.setPadding(dp(14), dp(8), dp(14), dp(8))
            t.setOnClickListener { historyTab = i; show(TAB_HISTORY) }
            chips.addView(t, lp(WRAP, WRAP).m(0, 0, dp(8), 0))
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips)
        col.addView(hs, lp().m(0, 0, 0, dp(12)))

        val chartCard = card()
        col.addView(chartCard, lp())
        col.addView(gap(12))
        val listCard = card()
        listCard.setPadding(dp(16), dp(6), dp(16), dp(6))
        col.addView(listCard, lp())

        if (historyTab == 0) {
            val goal = Prefs.goal(this)
            val days = db.stepsLastDays(14)
            chartCard.addView(text("Số bước 14 ngày gần nhất", 15f, C.TEXT, true))
            val ch = BarChartView(this)
            ch.setData(days.map { it.first.substring(8, 10) }, days.map { it.second }.toIntArray(), goal)
            chartCard.addView(ch, lp(MATCH, dp(200)))
            val last7 = days.takeLast(7).map { it.second }
            chartCard.addView(text("Trung bình 7 ngày: ${last7.average().roundToInt()} bước/ngày", 13f, C.SUB))
            for (d in days.reversed()) {
                val st = if (d.second >= goal) Status("Đạt", C.GREEN) else Status("Chưa đạt", C.SUB)
                listCard.addView(row("${d.second} bước", st, Util.shortFromKey(d.first), null))
            }
            return col
        }

        val type = when (historyTab) { 1 -> T.HR; 2 -> T.BP; 3 -> T.SPO2; else -> T.WEIGHT }
        val recs = db.list(type)
        val chrono = recs.take(30).reversed()
        val labels = chrono.map { Util.short(it.ts) }
        val chart = LineChartView(this)
        when (type) {
            T.HR -> {
                chartCard.addView(text("Nhịp tim (bpm)", 15f, C.TEXT, true))
                chart.setData(labels, listOf(LineChartView.Series(chrono.map { it.v1.toFloat() }.toFloatArray(), C.PRIMARY, "Nhịp tim")))
            }
            T.BP -> {
                val lg = hbox()
                lg.addView(text("Huyết áp (mmHg)  ", 15f, C.TEXT, true))
                lg.addView(text("● Tâm thu  ", 12f, C.RED))
                lg.addView(text("● Tâm trương", 12f, C.BLUE))
                chartCard.addView(lg)
                chart.setData(labels, listOf(
                    LineChartView.Series(chrono.map { it.v1.toFloat() }.toFloatArray(), C.RED, "Tâm thu"),
                    LineChartView.Series(chrono.map { it.v2.toFloat() }.toFloatArray(), C.BLUE, "Tâm trương")
                ))
            }
            T.SPO2 -> {
                chartCard.addView(text("SpO2 (%)", 15f, C.TEXT, true))
                chart.setData(labels, listOf(LineChartView.Series(chrono.map { it.v1.toFloat() }.toFloatArray(), C.BLUE, "SpO2")))
            }
            else -> {
                chartCard.addView(text("Cân nặng (kg)", 15f, C.TEXT, true))
                chart.setData(labels, listOf(LineChartView.Series(chrono.map { it.v1.toFloat() }.toFloatArray(), C.PURPLE, "Cân nặng")))
            }
        }
        chartCard.addView(chart, lp(MATCH, dp(200)))
        if (recs.size > 30) chartCard.addView(text("Biểu đồ hiển thị 30 lần đo gần nhất", 12f, C.SUB))

        if (recs.isEmpty()) {
            listCard.addView(text("Chưa có dữ liệu.", 14f, C.SUB).also { it.setPadding(0, dp(10), 0, dp(10)) })
        }
        val height = Prefs.height(this)
        for (r in recs) {
            val (label, st) = when (type) {
                T.HR -> Pair("${r.v1.roundToInt()} bpm", Health.hr(r.v1.roundToInt()))
                T.BP -> Pair("${r.v1.roundToInt()}/${r.v2.roundToInt()} mmHg" + (if (r.v3 > 0) " · mạch ${r.v3.roundToInt()}" else ""),
                    Health.bp(r.v1.roundToInt(), r.v2.roundToInt()))
                T.SPO2 -> Pair("${r.v1.roundToInt()}%" + (if (r.v3 > 0) " · mạch ${r.v3.roundToInt()}" else ""), Health.spo2(r.v1.roundToInt()))
                else -> {
                    val b = Health.bmi(r.v1, if (r.v2 > 0) r.v2 else height)
                    Pair("${Util.fmt(r.v1)} kg" + (if (b != null) " · BMI ${String.format(Locale.US, "%.1f", b)}" else ""),
                        if (b != null) Health.bmiStatus(b) else Status("", C.SUB))
                }
            }
            listCard.addView(row(label, st, Util.time(r.ts)) {
                AlertDialog.Builder(this)
                    .setTitle("Xóa bản ghi?")
                    .setMessage("$label\n${Util.time(r.ts)}")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Xóa") { _, _ -> db.delete(r.id); show(TAB_HISTORY) }
                    .show()
            })
        }
        return col
    }

    private fun row(label: String, st: Status, time: String, onLongClick: (() -> Unit)?): View {
        val r = hbox()
        r.setPadding(0, dp(10), 0, dp(10))
        val left = vbox()
        left.addView(text(label, 16f, C.TEXT, true))
        left.addView(text(time, 12f, C.SUB))
        r.addView(left, lp(0, WRAP, 1f))
        if (st.label.isNotEmpty()) r.addView(badge(st), lp(WRAP, WRAP))
        if (onLongClick != null) r.setOnLongClickListener { onLongClick(); true }
        return r
    }

    // ---------------- Thuốc ----------------

    private fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun markTaken(m: Med) {
        db.logMed(m)
        Reminders.cancelSnooze(this, m.id)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(m.id.toInt())
        toast("Đã ghi: uống ${m.name} lúc ${Util.hm(System.currentTimeMillis())}")
        show(current)
    }

    /** Danh sách thuốc cần uống hôm nay + trạng thái đã uống / chưa. */
    private fun medTodayCard(): LinearLayout {
        val c = card()
        c.addView(text("💊 Thuốc hôm nay", 18f, C.TEXT, true), lp().m(0, 0, 0, dp(4)))
        val meds = db.meds().filter { it.enabled }
        if (meds.isEmpty()) {
            c.addView(text("Chưa có lịch nhắc nào. Bấm \"Thêm lịch nhắc\" bên dưới.", 15f, C.SUB))
            return c
        }
        val today = Util.today()
        val nowMin = nowMinutes()
        for (m in meds) {
            val row = hbox()
            row.setPadding(0, dp(10), 0, dp(10))
            val left = vbox()
            left.addView(text("${m.slot}  ${m.name}", 17f, C.TEXT, true))
            if (m.dose.isNotBlank()) left.addView(text(m.dose, 14f, C.SUB))
            val log = db.takenOn(m.id, today)
            if (log != null) {
                left.addView(text("✅ Đã uống lúc ${Util.hm(log.ts)}", 16f, C.GREEN, true))
                row.addView(left, lp(0, WRAP, 1f))
                row.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Hủy ghi nhận?")
                        .setMessage("Xóa ghi nhận đã uống ${m.name} lúc ${Util.hm(log.ts)} (nếu bấm nhầm).")
                        .setNegativeButton("Không", null)
                        .setPositiveButton("Xóa ghi nhận") { _, _ -> db.deleteMedLog(log.id); show(current) }
                        .show()
                    true
                }
            } else {
                val due = m.hour * 60 + m.minute <= nowMin
                left.addView(text(if (due) "⚠️ Chưa uống" else "Chưa đến giờ", 16f, if (due) C.RED else C.SUB, due))
                row.addView(left, lp(0, WRAP, 1f))
                val b = button("Đã uống", C.GREEN) {
                    if (due) markTaken(m)
                    else AlertDialog.Builder(this)
                        .setTitle("Chưa đến giờ ${m.slot}")
                        .setMessage("Ghi nhận bạn đã uống ${m.name} bây giờ?")
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Đã uống") { _, _ -> markTaken(m) }
                        .show()
                }
                b.setPadding(dp(16), 0, dp(16), 0)
                row.addView(b, lp(WRAP, WRAP))
            }
            c.addView(row, lp())
        }
        c.addView(text("Bấm nhầm? Nhấn giữ vào thuốc đã uống để hủy.", 12f, C.SUB))
        return c
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        try {
            val i = if (Build.VERSION.SDK_INT >= 26)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    @Suppress("BatteryLife")
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (e2: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    private fun medScreen(): LinearLayout {
        val col = vbox()
        col.addView(title("Thuốc", "Nhắc giờ uống và ghi lại để không quên"))

        if (!Reminders.canNotify(this)) {
            val w = card()
            w.background = rounded(0xFFFFF4E5.toInt(), dpf(16f))
            w.addView(text("🔕 Thông báo đang bị tắt – app sẽ không nhắc được giờ uống thuốc.", 15f, C.ORANGE, true))
            w.addView(gap(8))
            w.addView(button("Bật thông báo", C.ORANGE) { openNotificationSettings() }, lp())
            col.addView(w, lp())
            col.addView(gap(14))
        }

        col.addView(medTodayCard(), lp())
        col.addView(gap(14))

        // Danh sách lịch nhắc
        val sc = card()
        sc.addView(text("⏰ Lịch nhắc", 18f, C.TEXT, true), lp().m(0, 0, 0, dp(4)))
        val all = db.meds()
        if (all.isEmpty()) sc.addView(text("Chưa có lịch nhắc.", 15f, C.SUB).also { it.setPadding(0, dp(8), 0, dp(8)) })
        for (m in all) {
            val row = hbox()
            row.setPadding(0, dp(10), 0, dp(10))
            val left = vbox()
            left.addView(text("${m.slot}  ${m.name}", 17f, if (m.enabled) C.TEXT else C.SUB, true))
            left.addView(text((if (m.dose.isNotBlank()) m.dose + " · " else "") + (if (m.enabled) "Đang bật – bấm để sửa" else "Đang tắt"), 13f, C.SUB))
            row.addView(left, lp(0, WRAP, 1f))
            val sw = Switch(this)
            sw.isChecked = m.enabled
            sw.setOnCheckedChangeListener { _, on ->
                val upd = m.copy(enabled = on)
                db.saveMed(upd)
                if (on) Reminders.scheduleNext(this, upd) else Reminders.cancel(this, m.id)
                show(TAB_MED)
            }
            row.addView(sw, lp(WRAP, WRAP))
            row.setOnClickListener { editMed(m) }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Xóa lịch nhắc?")
                    .setMessage("${m.slot} ${m.name}\n(Nhật ký đã uống trước đây vẫn được giữ lại.)")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Xóa") { _, _ -> Reminders.cancel(this, m.id); db.deleteMed(m.id); show(TAB_MED) }
                    .show()
                true
            }
            sc.addView(row, lp())
        }
        sc.addView(gap(6))
        sc.addView(button("+ Thêm lịch nhắc", C.PRIMARY) { editMed(null) }, lp())
        sc.addView(text("Uống nhiều lần trong ngày (sáng, tối…): thêm mỗi giờ uống một lịch. Nhấn giữ một lịch để xóa.", 13f, C.SUB).also { it.setPadding(0, dp(8), 0, 0) })
        col.addView(sc, lp())
        col.addView(gap(14))

        // Nhật ký 14 ngày
        val hc = card()
        hc.addView(text("📋 Nhật ký uống thuốc 14 ngày", 18f, C.TEXT, true), lp().m(0, 0, 0, dp(6)))
        val cal = Calendar.getInstance()
        val today = Util.today()
        val nowMin = nowMinutes()
        var anything = false
        for (i in 0 until 14) {
            val day = Util.dayKey(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val logs = db.medLogsOfDay(day)
            val meds = all.filter { Util.dayKey(Date(it.created)) <= day && (it.enabled || logs.any { l -> l.medId == it.id }) }
            if (meds.isEmpty() && logs.isEmpty()) continue
            anything = true
            val label = if (day == today) "Hôm nay" else if (i == 1) "Hôm qua" else Util.fullFromKey(day)
            hc.addView(text(label, 15f, C.PRIMARY, true).also { it.setPadding(0, dp(10), 0, dp(2)) })
            for (m in meds) {
                val l = logs.firstOrNull { it.medId == m.id }
                val line = when {
                    l != null -> text("✅ ${m.slot} ${m.name} – uống lúc ${Util.hm(l.ts)}", 15f, C.GREEN)
                    day == today && m.hour * 60 + m.minute > nowMin -> text("⏳ ${m.slot} ${m.name} – chưa đến giờ", 15f, C.SUB)
                    else -> text("❌ ${m.slot} ${m.name} – không ghi nhận", 15f, C.RED)
                }
                hc.addView(line)
            }
            for (l in logs.filter { lg -> meds.none { it.id == lg.medId } }) {
                hc.addView(text("✅ ${l.slot} ${l.name} – uống lúc ${Util.hm(l.ts)}", 15f, C.GREEN))
            }
        }
        if (!anything) hc.addView(text("Chưa có dữ liệu.", 15f, C.SUB))
        col.addView(hc, lp())
        col.addView(gap(14))

        // Chạy nền
        val bc = card()
        bc.addView(text("🔋 Không thấy nhắc đúng giờ?", 16f, C.TEXT, true))
        bc.addView(text("Một số máy (Xiaomi, Oppo, Vivo, Realme, Samsung…) tự tắt app chạy nền để tiết kiệm pin. Hãy cho phép app chạy nền / không tối ưu pin, và bật \"Tự khởi chạy\" nếu máy có mục này.", 14f, C.SUB).also { it.setPadding(0, dp(4), 0, dp(10)) })
        bc.addView(button("Cho phép chạy nền", C.BLUE) { openBatterySettings() }, lp())
        col.addView(bc, lp())
        return col
    }

    private fun editMed(m: Med?) {
        val box = vbox()
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val eName = input("VD: Amlodipin 5mg", plainText = true)
        val eDose = input("VD: 1 viên, sau ăn sáng", plainText = true)
        if (m != null) { eName.setText(m.name); eDose.setText(m.dose) }
        var hh = m?.hour ?: 7
        var mm = m?.minute ?: 0
        val tTime = button(String.format(Locale.US, "%02d:%02d", hh, mm), C.BLUE) {}
        tTime.setOnClickListener {
            TimePickerDialog(this, { _, h, mi ->
                hh = h; mm = mi
                tTime.text = String.format(Locale.US, "%02d:%02d", hh, mm)
            }, hh, mm, true).show()
        }
        box.addView(text("Tên thuốc", 14f, C.SUB), lp().m(0, 0, 0, dp(4)))
        box.addView(eName, lp())
        box.addView(text("Liều dùng (không bắt buộc)", 14f, C.SUB), lp().m(0, dp(12), 0, dp(4)))
        box.addView(eDose, lp())
        box.addView(text("Giờ uống (bấm để chọn)", 14f, C.SUB), lp().m(0, dp(12), 0, dp(4)))
        box.addView(tTime, lp())

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (m == null) "Thêm lịch nhắc" else "Sửa lịch nhắc")
            .setView(box)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = eName.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Hãy nhập tên thuốc.")
                } else {
                    val base = m ?: Med(0L, name, "", hh, mm, true)
                    val upd = base.copy(name = name, dose = eDose.text.toString().trim(), hour = hh, minute = mm, enabled = true)
                    val id = db.saveMed(upd)
                    Reminders.scheduleNext(this, upd.copy(id = id))
                    dlg.dismiss()
                    toast("Đã lưu. App sẽ nhắc lúc ${upd.slot} mỗi ngày.")
                    show(TAB_MED)
                }
            }
        }
        dlg.show()
    }

    // ---------------- Cài đặt & dữ liệu ----------------

    private fun settingsCard(): LinearLayout {
        val c = card()
        c.addView(text("⚙️ Cài đặt & dữ liệu", 17f, C.TEXT, true), lp().m(0, 0, 0, dp(10)))
        c.addView(text("Cỡ chữ", 14f, C.SUB), lp().m(0, 0, 0, dp(6)))
        val sizes = listOf(Pair("Vừa", 1.0f), Pair("Lớn", 1.2f), Pair("Rất lớn", 1.4f))
        val row = hbox()
        for ((name, v) in sizes) {
            val sel = Math.abs(Scale.f - v) < 0.01f
            val t = text(name, 15f, if (sel) 0xFFFFFFFF.toInt() else C.TEXT, sel)
            t.gravity = Gravity.CENTER
            t.background = if (sel) rounded(C.PRIMARY, dpf(10f)) else rounded(C.CARD, dpf(10f), C.LINE, dp(1))
            t.setPadding(0, dp(10), 0, dp(10))
            t.setOnClickListener {
                Prefs.setFontScale(this, v)
                Scale.f = v
                show(TAB_INPUT)
            }
            row.addView(t, lp(0, WRAP, 1f).m(dp(3), 0, dp(3), 0))
        }
        c.addView(row, lp().m(0, 0, 0, dp(14)))

        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        c.addView(button("📊 Xuất báo cáo Excel", C.GREEN) {
            createDoc("SucKhoe_baocao_$stamp.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", REQ_EXCEL)
        }, lp().m(0, 0, 0, dp(8)))
        c.addView(button("💾 Sao lưu dữ liệu", C.BLUE) {
            createDoc("SucKhoe_saoluu_$stamp.json", "application/json", REQ_BACKUP)
        }, lp().m(0, 0, 0, dp(8)))
        c.addView(button("♻️ Khôi phục từ bản sao lưu", C.SUB) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try { startActivityForResult(i, REQ_RESTORE) } catch (e: Exception) { toast("Máy không hỗ trợ chọn file.") }
        }, lp())
        c.addView(text("Báo cáo Excel có thể gửi bác sĩ hoặc người nhà. Nên sao lưu định kỳ và gửi file qua Zalo/Google Drive để không mất dữ liệu khi đổi máy hay gỡ app.", 13f, C.SUB).also { it.setPadding(0, dp(10), 0, 0) })
        return c
    }

    private fun createDoc(name: String, mime: String, req: Int) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name)
        try { startActivityForResult(i, req) } catch (e: Exception) { toast("Máy không hỗ trợ lưu file.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_EXCEL -> {
                    contentResolver.openOutputStream(uri)?.use { Export.excel(this, it) }
                    toast("Đã xuất báo cáo Excel.")
                }
                REQ_BACKUP -> {
                    contentResolver.openOutputStream(uri)?.use { Export.backup(this, it) }
                    toast("Đã sao lưu dữ liệu.")
                }
                REQ_RESTORE -> {
                    val txt = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
                    pendingRestore = txt
                    AlertDialog.Builder(this)
                        .setTitle("Khôi phục dữ liệu?")
                        .setMessage("Toàn bộ dữ liệu hiện tại trong app sẽ được THAY THẾ bằng dữ liệu trong file sao lưu.")
                        .setNegativeButton("Hủy") { _, _ -> pendingRestore = null }
                        .setPositiveButton("Khôi phục") { _, _ ->
                            val t = pendingRestore ?: return@setPositiveButton
                            pendingRestore = null
                            try {
                                toast(Export.restore(this, t))
                                show(TAB_HOME)
                            } catch (e: Exception) {
                                toast("Không khôi phục được: ${e.message}")
                            }
                        }
                        .show()
                }
            }
        } catch (e: Exception) {
            toast("Lỗi: ${e.message}")
        }
    }
}
