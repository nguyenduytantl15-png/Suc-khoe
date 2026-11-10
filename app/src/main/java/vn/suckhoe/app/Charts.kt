package vn.suckhoe.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Biểu đồ đường: trục x chia đều theo thứ tự lần đo. */
class LineChartView(ctx: Context) : View(ctx) {
    class Series(val values: FloatArray, val color: Int, val name: String)

    private var labels: List<String> = emptyList()
    private var series: List<Series> = emptyList()
    private val dens = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dens; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = C.LINE; strokeWidth = 1f * dens }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SUB; textSize = 11f * dens * Scale.f }

    fun setData(labels: List<String>, series: List<Series>) {
        this.labels = labels
        this.series = series
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = labels.size
        if (n == 0 || series.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            c.drawText("Chưa có dữ liệu", width / 2f, height / 2f, label)
            return
        }
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (s in series) for (v in s.values) { lo = min(lo, v); hi = max(hi, v) }
        if (hi - lo < 1f) { hi += 1f; lo -= 1f }
        val pad = (hi - lo) * 0.12f
        hi += pad; lo -= pad

        val left = 40f * dens
        val right = width - 12f * dens
        val top = 10f * dens
        val bottom = height - 24f * dens
        label.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            c.drawLine(left, y, right, y, grid)
            val v = hi - (hi - lo) * i / 4f
            c.drawText(Util.fmt(Math.round(v * 10) / 10.0), left - 6f * dens, y + 4f * dens, label)
        }
        fun xAt(i: Int): Float = if (n == 1) (left + right) / 2f else left + (right - left) * i / (n - 1f)
        fun yAt(v: Float): Float = bottom - (v - lo) / (hi - lo) * (bottom - top)

        for (s in series) {
            line.color = s.color
            dot.color = s.color
            val p = Path()
            for (i in 0 until min(n, s.values.size)) {
                val x = xAt(i); val y = yAt(s.values[i])
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(p, line)
            if (n <= 40) for (i in 0 until min(n, s.values.size)) c.drawCircle(xAt(i), yAt(s.values[i]), 3.5f * dens, dot)
        }
        label.textAlign = Paint.Align.CENTER
        val idx = if (n <= 2) (0 until n).toList() else listOf(0, n / 2, n - 1)
        for (i in idx) {
            label.textAlign = when (i) { 0 -> Paint.Align.LEFT; n - 1 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val x = if (n == 1) xAt(0) else xAt(i)
            c.drawText(labels[i], x, height - 6f * dens, label)
        }
    }
}

/** Biểu đồ cột cho số bước theo ngày, có vạch mục tiêu. */
class BarChartView(ctx: Context) : View(ctx) {
    private var labels: List<String> = emptyList()
    private var values: IntArray = IntArray(0)
    private var goal = 0
    private val dens = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goalP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.GREEN; strokeWidth = 1.5f * dens; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f * dens, 6f * dens), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SUB; textSize = 10f * dens * Scale.f; textAlign = Paint.Align.CENTER }

    fun setData(labels: List<String>, values: IntArray, goal: Int) {
        this.labels = labels; this.values = values; this.goal = goal
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val n = values.size
        if (n == 0) return
        val maxV = max(values.maxOrNull() ?: 0, goal).coerceAtLeast(1) * 1.1f
        val top = 18f * dens
        val bottom = height - 20f * dens
        val w = width.toFloat() / n
        for (i in 0 until n) {
            val h = (bottom - top) * values[i] / maxV
            bar.color = if (values[i] >= goal && goal > 0) C.GREEN else C.PRIMARY
            val r = RectF(i * w + w * 0.18f, bottom - h, (i + 1) * w - w * 0.18f, bottom)
            c.drawRoundRect(r, 4f * dens, 4f * dens, bar)
            if (n <= 7 || i % 2 == (n - 1) % 2) c.drawText(labels[i], i * w + w / 2, height - 5f * dens, label)
        }
        if (goal > 0) {
            val gy = bottom - (bottom - top) * goal / maxV
            val p = Path(); p.moveTo(0f, gy); p.lineTo(width.toFloat(), gy)
            c.drawPath(p, goalP)
            label.textAlign = Paint.Align.RIGHT
            label.color = C.GREEN
            c.drawText("Mục tiêu ${goal}", width - 2f * dens, gy - 4f * dens, label)
            label.textAlign = Paint.Align.CENTER
            label.color = C.SUB
        }
    }
}

/** Vòng tròn tiến độ (số bước hôm nay). */
class RingView(ctx: Context) : View(ctx) {
    private var progress = 0f
    private var main = ""
    private var sub = ""
    private val dens = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12f * dens; color = 0xFFFDE2E6.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12f * dens; color = C.PRIMARY; strokeCap = Paint.Cap.ROUND }
    private val t1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT; textSize = 26f * dens * Scale.f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val t2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SUB; textSize = 12f * dens * Scale.f; textAlign = Paint.Align.CENTER }

    fun set(progress: Float, main: String, sub: String) {
        this.progress = progress.coerceIn(0f, 1f)
        this.main = main; this.sub = sub
        fg.color = if (progress >= 1f) C.GREEN else C.PRIMARY
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = min(width, height).toFloat()
        val pad = 10f * dens
        val r = RectF((width - s) / 2 + pad, (height - s) / 2 + pad, (width + s) / 2 - pad, (height + s) / 2 - pad)
        c.drawArc(r, 0f, 360f, false, bg)
        c.drawArc(r, -90f, 360f * progress, false, fg)
        c.drawText(main, width / 2f, height / 2f + 4f * dens, t1)
        c.drawText(sub, width / 2f, height / 2f + 22f * dens, t2)
    }
}

/** Sóng mạch trực tiếp khi đo nhịp tim. */
class WaveView(ctx: Context) : View(ctx) {
    private val cap = 180
    private val buf = FloatArray(cap)
    private var count = 0
    private var head = 0
    private val dens = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * dens; color = C.PRIMARY; strokeJoin = Paint.Join.ROUND }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SUB; textSize = 13f * dens * Scale.f; textAlign = Paint.Align.CENTER }
    var message = "Đặt ngón tay che kín camera sau và đèn flash"

    fun add(v: Float) {
        buf[head] = v
        head = (head + 1) % cap
        if (count < cap) count++
        invalidate()
    }

    fun clear() { count = 0; head = 0; invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (count < 3) {
            c.drawText(message, width / 2f, height / 2f, hint)
            return
        }
        // Dùng ~3 giây gần nhất để co giãn biên độ
        val start = (head - count + cap) % cap
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        val from = max(0, count - 90)
        for (i in from until count) { val v = buf[(start + i) % cap]; lo = min(lo, v); hi = max(hi, v) }
        if (hi - lo < 0.5f) { val m = (hi + lo) / 2; hi = m + 0.25f; lo = m - 0.25f }
        val path = Path()
        val padY = 12f * dens
        for (i in 0 until count) {
            val v = buf[(start + i) % cap]
            val x = width * i / (cap - 1f)
            val y = padY + (height - 2 * padY) * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, p)
    }
}
