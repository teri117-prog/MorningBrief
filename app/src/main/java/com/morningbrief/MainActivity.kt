package com.morningbrief

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var tvNext: TextView
    private lateinit var tvTime: TextView
    private lateinit var swEnabled: Switch
    private lateinit var cbWeekdays: CheckBox
    private lateinit var tvExactWarn: TextView
    private lateinit var tvPlace: TextView
    private lateinit var etCity: EditText

    companion object {
        private const val REQ_NOTIF = 1
        private const val REQ_LOCATION = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvNext = findViewById(R.id.tvNext)
        tvTime = findViewById(R.id.tvTime)
        swEnabled = findViewById(R.id.swEnabled)
        cbWeekdays = findViewById(R.id.cbWeekdays)
        tvExactWarn = findViewById(R.id.tvExactWarn)
        tvPlace = findViewById(R.id.tvPlace)
        etCity = findViewById(R.id.etCity)

        // 초기 값
        swEnabled.isChecked = Prefs.enabled(this)
        cbWeekdays.isChecked = Prefs.weekdaysOnly(this)
        tvPlace.text = Prefs.placeName(this)
        updateTimeText()

        val rgNews = findViewById<RadioGroup>(R.id.rgNews)
        rgNews.check(
            when (Prefs.newsCount(this)) {
                3 -> R.id.rbNews3
                10 -> R.id.rbNews10
                else -> R.id.rbNews5
            }
        )
        rgNews.setOnCheckedChangeListener { _, id ->
            Prefs.setNewsCount(
                this, when (id) {
                    R.id.rbNews3 -> 3
                    R.id.rbNews10 -> 10
                    else -> 5
                }
            )
        }

        val rgRate = findViewById<RadioGroup>(R.id.rgRate)
        val rate = Prefs.speechRate(this)
        rgRate.check(
            when {
                rate < 0.95f -> R.id.rbSlow
                rate > 1.05f -> R.id.rbFast
                else -> R.id.rbNormal
            }
        )
        rgRate.setOnCheckedChangeListener { _, id ->
            Prefs.setSpeechRate(
                this, when (id) {
                    R.id.rbSlow -> 0.8f
                    R.id.rbFast -> 1.25f
                    else -> 1.0f
                }
            )
        }

        // 알람
        swEnabled.setOnCheckedChangeListener { _, on ->
            Prefs.setEnabled(this, on)
            AlarmScheduler.schedule(this)
            refreshStatus()
            if (on) toast("매일 ${formatTime(Prefs.hour(this), Prefs.minute(this))}에 브리핑해 드릴게요")
        }
        cbWeekdays.setOnCheckedChangeListener { _, on ->
            Prefs.setWeekdaysOnly(this, on)
            AlarmScheduler.schedule(this)
            refreshStatus()
        }
        tvTime.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                Prefs.setTime(this, h, m)
                updateTimeText()
                if (!swEnabled.isChecked) swEnabled.isChecked = true // 리스너가 예약까지 처리
                else AlarmScheduler.schedule(this)
                refreshStatus()
            }, Prefs.hour(this), Prefs.minute(this), false).show()
        }
        tvExactWarn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }

        // 재생
        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            BriefingService.start(this)
            toast("뉴스와 날씨를 불러오는 중이에요…")
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            BriefingService.stop(this)
        }

        // 위치
        findViewById<Button>(R.id.btnSearch).setOnClickListener { searchCity() }
        etCity.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchCity(); true
            } else false
        }
        findViewById<Button>(R.id.btnLocation).setOnClickListener { useCurrentLocation() }

        findViewById<TextView>(R.id.tvTtsSettings).setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmScheduler.schedule(this) // 권한 변경 등을 반영해 재예약
        refreshStatus()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ----- 화면 갱신 -----

    private fun updateTimeText() {
        tvTime.text = formatTime(Prefs.hour(this), Prefs.minute(this))
    }

    private fun formatTime(h: Int, m: Int): String {
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.KOREA, "%s %d:%02d", ampm, h12, m)
    }

    private fun refreshStatus() {
        if (Prefs.enabled(this)) {
            val next = Date(AlarmScheduler.nextTriggerMillis(this))
            val fmt = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREA)
            tvNext.text = "다음 브리핑: ${fmt.format(next)}"
        } else {
            tvNext.text = "알람이 꺼져 있어요"
        }
        tvExactWarn.visibility =
            if (Prefs.enabled(this) && !AlarmScheduler.canScheduleExact(this)) View.VISIBLE
            else View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ----- 도시 검색 -----

    private fun searchCity() {
        val name = etCity.text.toString().trim()
        if (name.isEmpty()) return
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(etCity.windowToken, 0)
        io.execute {
            val result = try {
                Weather.searchCity(name)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (result == null) {
                    toast("'$name'을(를) 찾지 못했어요")
                } else {
                    Prefs.setPlace(this, result.first, result.second, result.third)
                    tvPlace.text = result.third
                    etCity.setText("")
                    toast("날씨 지역: ${result.third}")
                }
            }
        }
    }

    // ----- 현재 위치 -----

    private fun useCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        // 최근 위치가 있으면 바로 사용
        try {
            val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time < 30 * 60 * 1000L) {
                applyLocation(last)
                return
            }
        } catch (e: SecurityException) {
            return
        }
        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> {
                toast("위치 서비스를 켜 주세요")
                return
            }
        }
        toast("현재 위치를 찾는 중이에요…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                    if (loc != null) applyLocation(loc) else toast("위치를 가져오지 못했어요")
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) = applyLocation(location)
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            toast("위치 권한이 필요해요")
        }
    }

    private fun applyLocation(loc: Location) {
        val lat = loc.latitude
        val lon = loc.longitude
        io.execute {
            val name = try {
                @Suppress("DEPRECATION")
                val list = Geocoder(this, Locale.KOREA).getFromLocation(lat, lon, 1)
                val a = list?.firstOrNull()
                a?.locality ?: a?.subLocality ?: a?.adminArea ?: "현재 위치"
            } catch (e: Exception) {
                "현재 위치"
            }
            main.post {
                Prefs.setPlace(this, lat, lon, name)
                tvPlace.text = name
                toast("날씨 지역: $name")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) useCurrentLocation()
            else toast("위치 권한 없이도 도시 이름으로 검색할 수 있어요")
        }
    }
}
