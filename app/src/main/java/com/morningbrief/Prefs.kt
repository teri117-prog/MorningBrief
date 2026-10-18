package com.morningbrief

import android.content.Context
import android.content.SharedPreferences

/** 앱 설정 저장소 */
object Prefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("morning_brief", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    fun hour(ctx: Context) = sp(ctx).getInt("hour", 7)
    fun minute(ctx: Context) = sp(ctx).getInt("minute", 0)
    fun setTime(ctx: Context, h: Int, m: Int) =
        sp(ctx).edit().putInt("hour", h).putInt("minute", m).apply()

    fun weekdaysOnly(ctx: Context) = sp(ctx).getBoolean("weekdays_only", false)
    fun setWeekdaysOnly(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("weekdays_only", v).apply()

    fun newsCount(ctx: Context) = sp(ctx).getInt("news_count", 5)
    fun setNewsCount(ctx: Context, v: Int) = sp(ctx).edit().putInt("news_count", v).apply()

    /** 말하기 속도 (1.0 = 보통) */
    fun speechRate(ctx: Context) = sp(ctx).getFloat("speech_rate", 1.0f)
    fun setSpeechRate(ctx: Context, v: Float) = sp(ctx).edit().putFloat("speech_rate", v).apply()

    // 날씨 위치 (기본: 서울)
    fun lat(ctx: Context) = sp(ctx).getFloat("lat", 37.5665f).toDouble()
    fun lon(ctx: Context) = sp(ctx).getFloat("lon", 126.9780f).toDouble()
    fun placeName(ctx: Context) = sp(ctx).getString("place", "서울") ?: "서울"
    fun setPlace(ctx: Context, lat: Double, lon: Double, name: String) =
        sp(ctx).edit()
            .putFloat("lat", lat.toFloat())
            .putFloat("lon", lon.toFloat())
            .putString("place", name)
            .apply()
}
