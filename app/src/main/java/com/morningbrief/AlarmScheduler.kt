package com.morningbrief

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** 매일 아침 알람 예약 */
object AlarmScheduler {
    const val ACTION_ALARM = "com.morningbrief.ACTION_ALARM"

    private fun alarmIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(
            ctx, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(AlarmManager::class.java)
        return am.canScheduleExactAlarms()
    }

    /** 설정에 맞게 다음 알람을 (재)예약. 꺼져 있으면 취소만 한다. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        if (!Prefs.enabled(ctx)) return

        val next = nextTriggerMillis(ctx)
        if (canScheduleExact(ctx)) {
            val show = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 알람 시계 방식: 절전 모드에서도 정확히 울리고 상태바에 알람 아이콘 표시
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    fun nextTriggerMillis(ctx: Context): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs.hour(ctx))
            set(Calendar.MINUTE, Prefs.minute(ctx))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 방금 울린 알람이 같은 시각으로 다시 잡히지 않도록 1분 여유
        if (cal.timeInMillis <= now + 60_000L) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (Prefs.weekdaysOnly(ctx)) {
            while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
            ) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }
}
