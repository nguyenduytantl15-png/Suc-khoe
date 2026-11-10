package vn.suckhoe.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import java.util.Calendar

/** Lên lịch nhắc uống thuốc và đọc số bước định kỳ bằng AlarmManager. */
object Reminders {
    const val CHANNEL = "med_reminder"
    const val ACTION_MED = "vn.suckhoe.app.MED_ALARM"
    const val ACTION_TAKEN = "vn.suckhoe.app.MED_TAKEN"
    const val ACTION_SNOOZE = "vn.suckhoe.app.MED_SNOOZE"
    const val ACTION_STEP_TICK = "vn.suckhoe.app.STEP_TICK"
    const val ACTION_STEP_NIGHT = "vn.suckhoe.app.STEP_NIGHT"
    const val EXTRA_ID = "med_id"
    const val EXTRA_TAB = "open_tab"
    const val SNOOZE_MIN = 10

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Nhắc uống thuốc", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Thông báo đến giờ uống thuốc"
            ch.enableVibration(true)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }

    private fun medPi(ctx: Context, id: Long, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, MedReceiver::class.java)
            .setAction(ACTION_MED)
            .putExtra(EXTRA_ID, id)
        val req = (if (snooze) 100_000 else 0) + id.toInt()
        return PendingIntent.getBroadcast(ctx, req, i, FLAGS)
    }

    /** Thời điểm kế tiếp của giờ h:m (hôm nay nếu chưa qua, ngược lại ngày mai). */
    fun nextTrigger(h: Int, m: Int): Long {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now + 1000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = am(ctx)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Đặt báo thức kế tiếp cho một lịch thuốc (không đụng tới báo "nhắc lại"). */
    fun scheduleNext(ctx: Context, m: Med) {
        am(ctx).cancel(medPi(ctx, m.id, false))
        if (m.enabled) setAlarm(ctx, nextTrigger(m.hour, m.minute), medPi(ctx, m.id, false))
    }

    fun cancel(ctx: Context, id: Long) {
        am(ctx).cancel(medPi(ctx, id, false))
        am(ctx).cancel(medPi(ctx, id, true))
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.toInt())
    }

    fun snooze(ctx: Context, id: Long) {
        setAlarm(ctx, System.currentTimeMillis() + SNOOZE_MIN * 60_000L, medPi(ctx, id, true))
    }

    fun cancelSnooze(ctx: Context, id: Long) {
        am(ctx).cancel(medPi(ctx, id, true))
    }

    /** Gọi khi mở app, khởi động lại máy, cập nhật app. */
    fun scheduleAll(ctx: Context) {
        createChannel(ctx)
        for (m in Db(ctx).meds()) scheduleNext(ctx, m)
        scheduleStepTicks(ctx)
    }

    fun scheduleStepTicks(ctx: Context) {
        // Mỗi giờ (không đánh thức máy) đọc số bước một lần
        val tick = PendingIntent.getBroadcast(ctx, 900_001,
            Intent(ctx, StepTickReceiver::class.java).setAction(ACTION_STEP_TICK), FLAGS)
        am(ctx).setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR,
            AlarmManager.INTERVAL_HOUR, tick)
        scheduleNight(ctx)
    }

    /** 23:57 mỗi tối chốt số bước của ngày. */
    fun scheduleNight(ctx: Context) {
        val night = PendingIntent.getBroadcast(ctx, 900_002,
            Intent(ctx, StepTickReceiver::class.java).setAction(ACTION_STEP_NIGHT), FLAGS)
        setAlarm(ctx, nextTrigger(23, 57), night)
    }

    @Suppress("DEPRECATION")
    fun showMedNotification(ctx: Context, m: Med) {
        if (!canNotify(ctx)) return
        createChannel(ctx)
        val id = m.id.toInt()
        val open = PendingIntent.getActivity(ctx, 300_000 + id,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_TAB, MainActivity.TAB_MED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            FLAGS)
        val taken = PendingIntent.getBroadcast(ctx, 200_000 + id,
            Intent(ctx, MedReceiver::class.java).setAction(ACTION_TAKEN).putExtra(EXTRA_ID, m.id), FLAGS)
        val later = PendingIntent.getBroadcast(ctx, 400_000 + id,
            Intent(ctx, MedReceiver::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_ID, m.id), FLAGS)

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL)
        else Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        val body = "${m.name}" + (if (m.dose.isNotBlank()) " – ${m.dose}" else "") + " (lịch ${m.slot})\nBấm \"Đã uống\" để app ghi lại."
        b.setSmallIcon(R.drawable.ic_notify)
            .setColor(C.PRIMARY)
            .setContentTitle("💊 Đến giờ uống thuốc")
            .setContentText("${m.name} – lịch ${m.slot}")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_notify, "✅ Đã uống", taken)
            .addAction(R.drawable.ic_notify, "⏰ Nhắc lại sau $SNOOZE_MIN phút", later)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, b.build())
    }
}

/** Nhận báo thức uống thuốc và các nút bấm trên thông báo. */
class MedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        if (id < 0) return
        val db = Db(ctx)
        val m = db.med(id) ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        when (intent.action) {
            Reminders.ACTION_MED -> {
                Reminders.scheduleNext(ctx, m)          // đặt lịch cho ngày mai
                if (!m.enabled) return
                // Đã ghi uống hôm nay rồi thì không nhắc nữa (tránh uống trùng)
                if (db.takenOn(m.id, Util.today()) == null) Reminders.showMedNotification(ctx, m)
            }
            Reminders.ACTION_TAKEN -> {
                nm.cancel(id.toInt())
                Reminders.cancelSnooze(ctx, id)
                val prev = db.takenOn(m.id, Util.today())
                if (prev == null) {
                    db.logMed(m)
                    Toast.makeText(ctx, "Đã ghi: uống ${m.name} lúc ${Util.hm(System.currentTimeMillis())}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "${m.name} đã được ghi uống lúc ${Util.hm(prev.ts)} rồi.", Toast.LENGTH_LONG).show()
                }
            }
            Reminders.ACTION_SNOOZE -> {
                nm.cancel(id.toInt())
                Reminders.snooze(ctx, id)
                Toast.makeText(ctx, "Sẽ nhắc lại sau ${Reminders.SNOOZE_MIN} phút", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Đọc số bước định kỳ khi app không mở. */
class StepTickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Reminders.ACTION_STEP_NIGHT) Reminders.scheduleNight(ctx)
        if (Build.VERSION.SDK_INT >= 29 &&
            ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val pending = goAsync()
        StepTracker.snapshot(ctx.applicationContext) { pending.finish() }
    }
}

/** Đặt lại toàn bộ lịch sau khi khởi động lại máy hoặc cập nhật app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.scheduleAll(ctx)
    }
}
