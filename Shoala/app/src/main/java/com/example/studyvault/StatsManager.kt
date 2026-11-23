package com.example.studyvault

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class StatsManager(context: Context) {
    private val prefs = context.getSharedPreferences("StudyStatsPrefs", Context.MODE_PRIVATE)

    fun addStudySeconds(seconds: Long) {
        val todayKey = getTodayKey()
        val currentToday = prefs.getLong(todayKey, 0L)
        prefs.edit().putLong(todayKey, currentToday + seconds).apply()
    }

    fun getTodayMinutes(): Long {
        return prefs.getLong(getTodayKey(), 0L) / 60L
    }

    // Week starts on Saturday
    fun getWeekMinutes(): Long {
        val cal = Calendar.getInstance()
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        var totalSeconds = 0L
        for (i in 0 until 7) {
            totalSeconds += prefs.getLong(formatKey(cal), 0L)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return totalSeconds / 60L
    }

    fun getMonthMinutes(): Long {
        val cal = Calendar.getInstance()
        val prefix = SimpleDateFormat("yyyy-MM-", Locale.US).format(cal.time)
        var totalSeconds = 0L
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is Long) {
                totalSeconds += value
            }
        }
        return totalSeconds / 60L
    }

    fun getYearMinutes(): Long {
        val cal = Calendar.getInstance()
        val prefix = SimpleDateFormat("yyyy-", Locale.US).format(cal.time)
        var totalSeconds = 0L
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is Long) {
                totalSeconds += value
            }
        }
        return totalSeconds / 60L
    }

    fun getLast7DaysChartData(): List<Pair<String, Long>> {
        val arabicDays = arrayOf("", "أحد", "اثنين", "ثلاثاء", "أربعاء", "خميس", "جمعة", "سبت")
        val result = mutableListOf<Pair<String, Long>>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)

        for (i in 0..6) {
            val mins = prefs.getLong(formatKey(cal), 0L) / 60L
            val dayName = arabicDays[cal.get(Calendar.DAY_OF_WEEK)]
            result.add(Pair(dayName, mins))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    fun getCurrentMonthChartData(): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        val cal = Calendar.getInstance()
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        for (day in 1..daysInMonth) {
            cal.set(Calendar.DAY_OF_MONTH, day)
            val mins = prefs.getLong(formatKey(cal), 0L) / 60L
            result.add(Pair(day.toString(), mins))
        }
        return result
    }

    private fun getTodayKey(): String = formatKey(Calendar.getInstance())

    private fun formatKey(cal: Calendar): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }
}
