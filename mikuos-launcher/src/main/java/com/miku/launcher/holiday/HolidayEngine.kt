package com.miku.launcher.holiday

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the home screen (and, in a holiday window, the lockscreen) should show for a day. */
data class ActiveArt(
    /** "holiday" or "season". */
    val kind: String,
    /** Holiday id, or spring / summer / autumn / winter. */
    val key: String,
    /** A pack path or "drawable:<name>". Null when the pack has nothing for it. */
    val art: String?,
    /** The holiday, when [kind] is "holiday". */
    val holiday: Holiday? = null,
    /** The holiday's date this window belongs to. */
    val date: LocalDate? = null,
)

/** A holiday on a concrete date, after the regional observance rules. */
data class Occurrence(val holiday: Holiday, val date: LocalDate, val observedFrom: LocalDate? = null)

/**
 * Holidays and seasons. Pure: everything it needs comes in as arguments, so it is unit-tested on
 * the JVM. The Android side (HolidayState) feeds it the calendar, the scope, the latitude and the
 * active pack's art slots.
 */
object HolidayEngine {

    /** Meteorological seasons. Southern latitudes get the opposite season. Null latitude = north. */
    fun season(date: LocalDate, latitude: Double?): String {
        val north = when (date.monthValue) {
            3, 4, 5 -> "spring"
            6, 7, 8 -> "summer"
            9, 10, 11 -> "autumn"
            else -> "winter"
        }
        if (latitude == null || latitude >= 0.0) return north
        return when (north) {
            "spring" -> "autumn"
            "summer" -> "winter"
            "autumn" -> "spring"
            else -> "summer"
        }
    }

    /**
     * Every holiday date in [year] for [holidays], with the regional rules applied: JP
     * substitute and citizens' holidays are added as extra occurrences, UK bank holidays on a
     * weekend get their substitute weekday as an extra occurrence, US federal ones the Friday or
     * Monday off. The holiday's own date is always in the list (art and greetings follow the
     * real day, the observed day is for the day off).
     */
    fun occurrences(holidays: List<Holiday>, year: Int): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        val jpBase = LinkedHashMap<LocalDate, String>()
        val ukBase = LinkedHashMap<LocalDate, String>()
        val byId = holidays.associateBy { it.id }
        for (h in holidays) {
            val d = h.rule.date(year) ?: continue
            out += Occurrence(h, d)
            when (h.observed) {
                "jp" -> jpBase[d] = h.id
                "uk" -> ukBase[d] = h.id
                "us" -> HolidayRules.usObserved(d).let { o -> if (o != d) out += Occurrence(h, o, d) }
            }
        }
        for ((d, id) in HolidayRules.jpExtraDays(jpBase)) {
            val src = byId[id.removeSuffix("_substitute")]
            val h = src ?: Holiday(id, "Citizens' Holiday", setOf("JP"), DateRule.Fixed(d.monthValue, d.dayOfMonth), "jp", 0, "plain")
            out += Occurrence(h, d, if (src != null) jpBase.entries.firstOrNull { it.value == src.id }?.key else null)
        }
        for ((d, o) in HolidayRules.ukObserved(ukBase)) {
            if (o != d) byId[ukBase[d]]?.let { out += Occurrence(it, o, d) }
        }
        return out.sortedBy { it.date }
    }

    /** Dates in [year] that are days off in [region] ("JP", "UK", "US"), incl. substitutes. */
    fun daysOff(holidays: List<Holiday>, region: String, year: Int): Set<LocalDate> {
        val mine = holidays.filter { region in it.regions }
        val occ = occurrences(mine, year)
        return when (region) {
            // a bank holiday on a weekend is not a day off itself: its substitute is
            "UK" -> occ.filter { it.observedFrom != null || !HolidayRules.isWeekend(it.date) }.map { it.date }.toSet()
            else -> occ.map { it.date }.toSet()
        }
    }

    /**
     * The holiday whose art window covers [today]: the window runs from windowDaysBefore days
     * before the holiday through the holiday itself. When windows overlap, the nearest holiday
     * wins, then the one listed first. Observed (substitute) days do not open a window.
     */
    fun activeHoliday(today: LocalDate, holidays: List<Holiday>, scope: HolidayScope): Occurrence? {
        val list = holidays.filter { scope.includes(it) }
        if (list.isEmpty()) return null
        var best: Occurrence? = null
        var bestDist = Long.MAX_VALUE
        for (year in listOf(today.year, today.year + 1)) {
            for (h in list) {
                val d = h.rule.date(year) ?: continue
                val dist = ChronoUnit.DAYS.between(today, d)
                if (dist < 0 || dist > h.windowDaysBefore) continue
                if (dist < bestDist) {
                    best = Occurrence(h, d)
                    bestDist = dist
                }
            }
        }
        return best
    }

    /**
     * The art for [today]. A holiday window beats the season. [packId] picks the holiday art
     * entry, [seasonArt] holds the pack's wallpaper slots (spring, summer, autumn, winter,
     * default). A holiday with no art for this pack falls through to the season.
     */
    fun activeArt(
        today: LocalDate,
        holidays: List<Holiday>,
        scope: HolidayScope,
        latitude: Double?,
        packId: String,
        seasonArt: Map<String, String?>,
    ): ActiveArt {
        val occ = activeHoliday(today, holidays, scope)
        val hArt = occ?.holiday?.artFor(packId)
        if (occ != null && hArt != null) return ActiveArt("holiday", occ.holiday.id, hArt, occ.holiday, occ.date)
        val s = season(today, latitude)
        return ActiveArt("season", s, seasonArt[s] ?: seasonArt["default"])
    }

    /** Respectful days get their reminder the day before, the rest on the day. */
    fun reminderDate(o: Occurrence): LocalDate = if (o.holiday.respectful) o.date.minusDays(1) else o.date

    /** Holidays whose reminder is due on [today] (own dates only, not observed days). */
    fun remindersDue(today: LocalDate, holidays: List<Holiday>, scope: HolidayScope): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        for (year in listOf(today.year, today.year + 1)) {
            for (h in holidays) {
                if (!scope.includes(h) || h.reminder == null) continue
                val d = h.rule.date(year) ?: continue
                val o = Occurrence(h, d)
                if (reminderDate(o) == today) out += o
            }
        }
        return out
    }
}
