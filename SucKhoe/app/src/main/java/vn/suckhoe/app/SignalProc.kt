package vn.suckhoe.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Ước lượng nhịp tim từ tín hiệu PPG (độ sáng ngón tay theo thời gian) bằng tự tương quan. */
object SignalProc {
    class Result(val bpm: Int, val quality: Double)

    private const val FS = 30.0

    /**
     * @param t thời điểm khung hình (nano giây), tăng dần
     * @param v giá trị tín hiệu tương ứng
     * @param windowSec chỉ dùng dữ liệu trong khoảng thời gian gần nhất này
     */
    fun estimate(t: List<Long>, v: List<Double>, windowSec: Double = 1e6): Result? {
        val n = min(t.size, v.size)
        if (n < 60) return null
        val tEnd = t[n - 1]
        val tLimit = tEnd - (windowSec * 1e9).toLong()
        var s = 0
        while (s < n - 1 && t[s] < tLimit) s++
        val t0 = t[s]
        val dur = (tEnd - t0) / 1e9
        if (dur < 5.0) return null

        // 1) Lấy mẫu lại đều 30 Hz
        val m = (dur * FS).toInt()
        val x = DoubleArray(m)
        var j = s
        for (i in 0 until m) {
            val ti = t0 + (i / FS * 1e9).toLong()
            while (j < n - 2 && t[j + 1] < ti) j++
            val ta = t[j]
            val tb = t[j + 1]
            val f = if (tb > ta) ((ti - ta).toDouble() / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            x[i] = v[j] + (v[j + 1] - v[j]) * f
        }

        // 2) Bỏ thành phần trôi chậm (trừ trung bình trượt ~1 s)
        val pre = DoubleArray(m + 1)
        for (i in 0 until m) pre[i + 1] = pre[i] + x[i]
        val y = DoubleArray(m)
        val half = 15
        for (i in 0 until m) {
            val a = max(0, i - half)
            val b = min(m - 1, i + half)
            y[i] = x[i] - (pre[b + 1] - pre[a]) / (b - a + 1)
        }
        // 3) Làm mượt (trung bình 3 mẫu)
        val z = DoubleArray(m)
        for (i in 0 until m) {
            var sum = 0.0
            var c = 0
            for (k in max(0, i - 1)..min(m - 1, i + 1)) { sum += y[k]; c++ }
            z[i] = sum / c
        }
        // Cắt biên độ đột biến (cử động tay)
        val sorted = z.map { abs(it) }.sorted()
        val clip = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)] * 2.5
        if (clip > 0) for (i in 0 until m) z[i] = z[i].coerceIn(-clip, clip)

        // 4) Tự tương quan cho nhịp 40–200 bpm
        val minLag = (FS * 60 / 200).toInt()      // 9
        val maxLag = (FS * 60 / 40).toInt()       // 45
        if (m < maxLag * 3) return null
        var energy = 0.0
        for (i in 0 until m) energy += z[i] * z[i]
        if (energy <= 1e-12) return null
        val r = DoubleArray(maxLag + 2)
        for (lag in (minLag - 1)..(maxLag + 1)) {
            var sum = 0.0
            for (i in 0 until m - lag) sum += z[i] * z[i + lag]
            r[lag] = sum / energy * m / (m - lag)
        }
        val peaks = ArrayList<Int>()
        for (lag in minLag..maxLag) if (r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] > 0) peaks.add(lag)
        if (peaks.isEmpty()) return null
        var best = peaks.maxByOrNull { r[it] }!!
        // Tránh nhầm sang bội số chu kỳ (nhịp/2)
        for (p in peaks.sorted()) {
            if (p >= best) break
            val ratio = best.toDouble() / p
            if (r[p] >= 0.65 * r[best] && abs(ratio - ratio.roundToInt()) < 0.15 && ratio.roundToInt() >= 2) { best = p; break }
        }
        val q = r[best]
        if (q < 0.25) return null
        val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
        val den = a - 2 * b + c
        val off = if (abs(den) > 1e-12) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
        val bpm = (60.0 * FS / (best + off)).roundToInt().coerceIn(40, 200)
        return Result(bpm, q.coerceIn(0.0, 1.0))
    }
}
