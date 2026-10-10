package com.miku.launcher.holiday

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One holiday from mikuos/mascot/holidays/calendar.json (bundled into the launcher's assets).
 *
 * Schema the parser reads. It is tolerant: field aliases in brackets, unknown fields ignored, a
 * holiday with a rule it cannot read is skipped, not fatal.
 * ```
 * {
 *   "schema": 1,
 *   "holidays": [
 *     {
 *       "id": "us_thanksgiving",                 stable id, used by the Custom checklist
 *       "name": "Thanksgiving",                  [title]
 *       "regions": ["US"],                       US, UK (GB accepted), JP, intl   [region: "US"]
 *       "rule": {"type": "nth_weekday", "month": 11, "weekday": "THU", "n": 4},
 *       "observed": "none",                      none | us | uk | jp   (jp: also takes part in
 *                                                the substitute and citizens' holiday rules)
 *       "window_days_before": 3,                 art shows from date-3 to date   [window]
 *       "tone": "festive",                       festive | plain | respectful (reminder the day before)
 *       "art": {"miku": "drawable:theme_cozy_cafe_wallpaper", "hione": "art/holidays/thanksgiving.webp",
 *               "default": "..."},              per pack id; a pack path or drawable:<name>
 *       "reminder": "Thanksgiving is today."     tokens like {mascot_name} [reminder_text, or {"text": ..}]
 *     }
 *   ]
 * }
 * ```
 * Rule forms: {"type": "fixed", "month": 12, "day": 25} · {"type": "nth_weekday", "month": 5,
 * "weekday": "MON", "n": -1} (n -1 = last) · {"type": "easter", "offset": 1} ·
 * {"type": "jp_vernal_equinox"} · {"type": "jp_autumnal_equinox"}. A string works too:
 * "fixed:12-25", "nth:11:THU:4", "last:5:MON", "easter:+1", "equinox:vernal", "equinox:autumnal",
 * or a plain "12-25" / "MM-DD" in a "date" field.
 */
data class Holiday(
    val id: String,
    val name: String,
    val regions: Set<String>,
    val rule: DateRule,
    val observed: String = "none",
    val windowDaysBefore: Int = 0,
    val tone: String = "festive",
    val art: Map<String, String> = emptyMap(),
    val reminder: String? = null,
) {
    /** The art for [packId]: the pack's own entry, then "default". */
    fun artFor(packId: String): String? = art[packId] ?: art["default"]

    /** True for a day of remembrance: the reminder goes out the day before, in a plain tone. */
    val respectful: Boolean get() = tone.equals("respectful", true) || tone.equals("solemn", true)
}

object HolidayCalendar {
    val REGIONS = listOf("intl", "US", "UK", "JP")

    fun parse(text: String): List<Holiday> {
        val t = text.trim()
        val arr: JSONArray = if (t.startsWith("[")) JSONArray(t) else {
            val o = JSONObject(t)
            o.optJSONArray("holidays") ?: o.optJSONArray("items") ?: JSONArray()
        }
        val out = ArrayList<Holiday>()
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            runCatching { parseOne(h) }.getOrNull()?.let { out += it }
        }
        return out
    }

    fun parseOne(h: JSONObject): Holiday? {
        val id = h.optString("id").ifBlank { return null }
        val rule = parseRule(h.opt("rule") ?: h.opt("date")) ?: return null
        val regions = LinkedHashSet<String>()
        (h.optJSONArray("regions") ?: h.optJSONArray("region"))?.let { a ->
            for (i in 0 until a.length()) normRegion(a.optString(i))?.let { regions += it }
        }
        if (regions.isEmpty()) (h.optString("regions").ifBlank { h.optString("region") }).split(',', ' ')
            .forEach { s -> normRegion(s)?.let { regions += it } }
        if (regions.isEmpty()) regions += "intl"
        val art = LinkedHashMap<String, String>()
        h.optJSONObject("art")?.let { a -> for (k in a.keys()) a.optString(k).ifBlank { null }?.let { art[k] = it } }
        val reminder = when (val r = h.opt("reminder") ?: h.opt("reminder_text")) {
            is String -> r.ifBlank { null }
            is JSONObject -> r.optString("text").ifBlank { r.optString("en") }.ifBlank { null }
            else -> null
        }
        val window = when {
            h.has("window_days_before") -> h.optInt("window_days_before", 0)
            h.has("window") -> h.optInt("window", 0)
            else -> 0
        }
        val observed = h.optString("observed", "").ifBlank {
            when {
                "JP" in regions -> "jp"
                else -> "none"
            }
        }.lowercase()
        return Holiday(
            id = id,
            name = h.optString("name").ifBlank { h.optString("title").ifBlank { id } },
            regions = regions,
            rule = rule,
            observed = observed,
            windowDaysBefore = window.coerceIn(0, 60),
            tone = h.optString("tone", "festive").ifBlank { "festive" }.lowercase(),
            art = art,
            reminder = reminder,
        )
    }

    fun normRegion(s: String?): String? = when (s?.trim()?.uppercase()) {
        "US", "USA" -> "US"
        "UK", "GB", "GBR" -> "UK"
        "JP", "JPN", "JA" -> "JP"
        "INTL", "INTERNATIONAL", "GLOBAL", "WORLD", "ALL" -> "intl"
        else -> null
    }

    fun parseRule(r: Any?): DateRule? = when (r) {
        is JSONObject -> ruleFromObject(r)
        is String -> ruleFromString(r)
        else -> null
    }

    private fun ruleFromObject(o: JSONObject): DateRule? {
        val type = o.optString("type").lowercase().replace('-', '_')
        return when (type) {
            "fixed", "date" -> {
                if (o.has("date")) ruleFromString(o.getString("date"))
                else DateRule.Fixed(o.getInt("month"), o.getInt("day"))
            }
            "nth_weekday", "weekday", "nth" -> {
                val wd = weekdayOf(o.opt("weekday") ?: o.opt("dow")) ?: return null
                val n = o.optString("n", o.optString("nth", "1")).let {
                    if (it.equals("last", true)) -1 else it.toIntOrNull() ?: 1
                }
                DateRule.NthWeekday(o.getInt("month"), wd, n)
            }
            "last_weekday" -> DateRule.NthWeekday(o.getInt("month"), weekdayOf(o.opt("weekday")) ?: return null, -1)
            "easter" -> DateRule.Easter(o.optInt("offset", 0))
            "jp_vernal_equinox", "vernal_equinox", "equinox_vernal" -> DateRule.JpVernalEquinox
            "jp_autumnal_equinox", "autumnal_equinox", "equinox_autumnal" -> DateRule.JpAutumnalEquinox
            "equinox", "jp_equinox" -> if (o.optString("which", o.optString("season")).lowercase().startsWith("a"))
                DateRule.JpAutumnalEquinox else DateRule.JpVernalEquinox
            else -> null
        }
    }

    private fun weekdayOf(v: Any?): DayOfWeek? = when (v) {
        is Number -> v.toInt().takeIf { it in 1..7 }?.let { DayOfWeek.of(it) }
        is String -> HolidayRules.weekday(v)
        else -> null
    }

    private fun ruleFromString(s0: String): DateRule? {
        val s = s0.trim()
        val p = s.split(':')
        return when (p[0].lowercase()) {
            "fixed" -> mmdd(p.getOrNull(1))
            "nth" -> DateRule.NthWeekday(p[1].toInt(), HolidayRules.weekday(p[2]) ?: return null,
                p[3].let { if (it.equals("last", true)) -1 else it.toInt() })
            "last" -> DateRule.NthWeekday(p[1].toInt(), HolidayRules.weekday(p[2]) ?: return null, -1)
            "easter" -> DateRule.Easter(p.getOrNull(1)?.removePrefix("+")?.toIntOrNull() ?: 0)
            "equinox" -> if (p.getOrNull(1)?.lowercase()?.startsWith("a") == true) DateRule.JpAutumnalEquinox else DateRule.JpVernalEquinox
            else -> mmdd(s)
        }
    }

    private fun mmdd(s: String?): DateRule? {
        val m = Regex("""^(?:\d{4}-)?(\d{1,2})-(\d{1,2})$""").find(s?.trim() ?: return null) ?: return null
        return DateRule.Fixed(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
}

/** Which holidays the user sees: Settings.Global miku_holiday_scope and miku_holiday_custom. */
data class HolidayScope(
    /** intl (every region), local, custom, or off. */
    val mode: String = "local",
    /** Regions for local mode, from location, SIM and locale. "intl" is always added. */
    val localRegions: Set<String> = emptySet(),
    /** Holiday ids for custom mode. */
    val custom: Set<String> = emptySet(),
) {
    fun includes(h: Holiday): Boolean = when (mode) {
        "off" -> false
        "intl", "international", "all" -> true
        "custom" -> h.id in custom
        else -> h.regions.any { it == "intl" || it in localRegions }
    }

    companion object {
        fun parseCustom(s: String?): Set<String> =
            s.orEmpty().split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
