package com.morningbrief

import android.content.Context
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

object Http {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MorningBrief/1.0")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** 주요 뉴스: 구글 뉴스 한국판 RSS의 헤드라인 */
object News {
    private const val TOP_URL = "https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko"

    fun headlines(count: Int): List<String> {
        val xml = Http.get(TOP_URL)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val result = mutableListOf<String>()
        var inItem = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && result.size < count) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> inItem = true
                    "title" -> if (inItem) {
                        val t = clean(parser.nextText())
                        if (t.isNotBlank()) result.add(t)
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "item") {
                inItem = false
            }
            event = parser.next()
        }
        return result
    }

    /** "제목 - 언론사" 형태에서 언론사 이름 제거, 읽기 어려운 기호 정리 */
    private fun clean(raw: String): String {
        var t = raw.trim()
        val idx = t.lastIndexOf(" - ")
        if (idx > 0) t = t.substring(0, idx)
        return t.replace(Regex("[\"“”‘’'…]"), " ")
            .replace("…", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/** 날씨: Open-Meteo (API 키 불필요) */
object Weather {
    fun spoken(lat: Double, lon: Double, place: String): String {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1",
            lat, lon
        )
        val json = JSONObject(Http.get(url))
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")

        val temp = cur.getDouble("temperature_2m").roundToInt()
        val feels = cur.getDouble("apparent_temperature").roundToInt()
        val curCode = cur.getInt("weather_code")
        val dayCode = daily.getJSONArray("weather_code").getInt(0)
        val max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
        val min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
        val rainArr = daily.optJSONArray("precipitation_probability_max")
        val rain = if (rainArr != null && !rainArr.isNull(0)) rainArr.getInt(0) else -1

        val sb = StringBuilder()
        sb.append("먼저 날씨입니다. 지금 ").append(place).append(" 날씨는 ")
            .append(describe(curCode)).append(". ")
        sb.append("기온은 ").append(deg(temp)).append(", 체감 온도는 ").append(deg(feels)).append("입니다. ")
        sb.append("오늘 하루는 ").append(describe(dayCode)).append(", ")
        sb.append("최고 ").append(deg(max)).append(", 최저 ").append(deg(min))
        if (rain >= 0) sb.append(", 강수 확률은 ").append(rain).append("퍼센트")
        sb.append("입니다. ")

        if (rain >= 50) sb.append("외출하실 때 우산을 꼭 챙기세요. ")
        else if (rain >= 30) sb.append("비가 올 수도 있으니 작은 우산을 챙기시면 좋겠습니다. ")
        if (min <= 0) sb.append("영하의 추운 날씨이니 따뜻하게 입으세요. ")
        else if (max - min >= 10) sb.append("일교차가 크니 겉옷을 챙기세요. ")
        else if (max >= 30) sb.append("더운 날씨이니 물을 자주 드세요. ")
        return sb.toString()
    }

    private fun deg(v: Int) = if (v < 0) "영하 ${-v}도" else "${v}도"

    /** WMO 날씨 코드 → 한국어 */
    fun describe(code: Int): String = when (code) {
        0 -> "맑음"
        1 -> "대체로 맑음"
        2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        51, 53, 55 -> "이슬비"
        56, 57 -> "어는 이슬비"
        61 -> "약한 비"
        63 -> "비"
        65 -> "강한 비"
        66, 67 -> "어는 비"
        71 -> "약한 눈"
        73 -> "눈"
        75 -> "많은 눈"
        77 -> "싸락눈"
        80, 81 -> "소나기"
        82 -> "강한 소나기"
        85, 86 -> "눈 소나기"
        95 -> "뇌우"
        96, 99 -> "우박을 동반한 뇌우"
        else -> "알 수 없음"
    }

    /** 도시 이름으로 좌표 검색 (Open-Meteo Geocoding). 결과: (위도, 경도, 표시 이름) */
    fun searchCity(name: String): Triple<Double, Double, String>? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val json = JSONObject(
            Http.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=ko&format=json")
        )
        val arr = json.optJSONArray("results") ?: return null
        if (arr.length() == 0) return null
        val r = arr.getJSONObject(0)
        return Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", name))
    }
}

/** 읽어줄 전체 원고 만들기 (백그라운드 스레드에서 호출) */
object BriefingBuilder {
    private val ORDINALS = listOf(
        "첫 번째", "두 번째", "세 번째", "네 번째", "다섯 번째",
        "여섯 번째", "일곱 번째", "여덟 번째", "아홉 번째", "열 번째"
    )
    private val DAYS = listOf("", "일", "월", "화", "수", "목", "금", "토")

    fun build(ctx: Context): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val greet = if (hour in 4..11) "좋은 아침입니다." else "안녕하세요."
        val sb = StringBuilder()
        sb.append(greet).append(" 오늘은 ")
            .append(cal.get(Calendar.MONTH) + 1).append("월 ")
            .append(cal.get(Calendar.DAY_OF_MONTH)).append("일 ")
            .append(DAYS[cal.get(Calendar.DAY_OF_WEEK)]).append("요일입니다. ")
            .append("지금 시각은 ").append(hour).append("시 ")
            .append(cal.get(Calendar.MINUTE)).append("분입니다.\n")

        try {
            sb.append(Weather.spoken(Prefs.lat(ctx), Prefs.lon(ctx), Prefs.placeName(ctx))).append("\n")
        } catch (e: Exception) {
            sb.append("날씨 정보를 가져오지 못했습니다. 인터넷 연결을 확인해 주세요.\n")
        }

        try {
            val news = News.headlines(Prefs.newsCount(ctx))
            if (news.isEmpty()) {
                sb.append("오늘의 뉴스를 찾지 못했습니다.\n")
            } else {
                sb.append("다음은 오늘의 주요 뉴스입니다.\n")
                news.forEachIndexed { i, title ->
                    val ord = ORDINALS.getOrElse(i) { "${i + 1}번째" }
                    sb.append(ord).append(" 뉴스. ").append(title).append(".\n")
                }
            }
        } catch (e: Exception) {
            sb.append("뉴스를 가져오지 못했습니다.\n")
        }

        sb.append("이상으로 브리핑을 마칩니다. 오늘도 좋은 하루 보내세요.")
        return sb.toString()
    }
}
