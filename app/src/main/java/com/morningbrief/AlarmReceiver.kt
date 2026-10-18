package com.morningbrief

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_ALARM -> {
                BriefingService.start(context)
                AlarmScheduler.schedule(context) // 다음 날 알람 예약
            }
            else -> AlarmScheduler.schedule(context) // 재부팅, 시간 변경 등
        }
    }
}
