package vn.suckhoe.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * Dùng cảm biến TYPE_STEP_COUNTER (phần cứng, đếm cả khi app đóng, tính từ lúc khởi động máy).
 * Mỗi lần đọc, phần chênh lệch so với lần đọc trước được cộng vào ngày hiện tại.
 * Ngoài lúc mở app, app còn tự đọc mỗi giờ và lúc 23:57 (xem Reminders) để số bước vào đúng ngày.
 */
class StepTracker(private val ctx: Context, private val db: Db, private val onUpdate: (Int) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var running = false

    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        if (running) return
        running = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (running) sm.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        onUpdate(record(ctx, db, e.values[0]))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /** Ghi nhận giá trị bộ đếm tổng; trả về số bước hôm nay. */
        fun record(ctx: Context, db: Db, total: Float): Int {
            val last = Prefs.lastCounter(ctx)
            val today = Util.today()
            var delta = 0
            if (last >= 0f) {
                // total < last nghĩa là máy vừa khởi động lại -> bộ đếm về 0
                delta = if (total >= last) (total - last).toInt() else total.toInt()
            }
            Prefs.setLastCounter(ctx, total)
            if (delta > 0) db.setSteps(today, db.getSteps(today) + delta)
            return db.getSteps(today)
        }

        /** Đọc nhanh bộ đếm một lần (dùng khi chạy nền), gọi done() khi xong hoặc sau 5 giây. */
        fun snapshot(ctx: Context, done: () -> Unit) {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (s == null) { done(); return }
            val h = Handler(Looper.getMainLooper())
            var finished = false
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (finished) return
                    finished = true
                    sm.unregisterListener(this)
                    try { record(ctx, Db(ctx), e.values[0]) } catch (ex: Exception) { }
                    done()
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            if (!sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL, h)) { done(); return }
            h.postDelayed({
                if (!finished) {
                    finished = true
                    sm.unregisterListener(listener)
                    done()
                }
            }, 5000)
        }
    }
}
