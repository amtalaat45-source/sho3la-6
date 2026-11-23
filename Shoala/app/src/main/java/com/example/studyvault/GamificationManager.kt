package com.example.studyvault

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class AwardEvent(val title: String, val message: String)

data class ProfileStatus(
    val level: Int,
    val rankTitle: String,
    val rankEmoji: String,
    val totalXp: Long,
    val spendableFlames: Long,
    val progressPercent: Int,
    val xpToNextLevel: Long
)

class GamificationManager(context: Context) {
    private val prefs = context.getSharedPreferences("StudyGamificationPrefs", Context.MODE_PRIVATE)

    private val levelThresholds = listOf(
        Triple(0L, "مبتدئ", "🌱"),
        Triple(100L, "شرارة أولى", "✨"),
        Triple(300L, "مثابر", "🔥"),
        Triple(600L, "مقاتل التركيز", "⚔️"),
        Triple(1100L, "محترف", "🎯"),
        Triple(2000L, "نخبة", "🛡️"),
        Triple(3500L, "قائد الوقت", "⚡"),
        Triple(6000L, "أسطورة", "👑")
    )

    fun checkAndUpdateStreakOnStudy(): AwardEvent? {
        val today = getDateKey(0)
        val yesterday = getDateKey(-1)
        val lastStudyDate = prefs.getString("LAST_STUDY_DATE", "") ?: ""

        if (lastStudyDate == today) return null

        var currentStreak = prefs.getInt("CURRENT_STREAK_DAYS", 0)
        currentStreak = if (lastStudyDate == yesterday) currentStreak + 1 else 1

        val editor = prefs.edit()
        editor.putString("LAST_STUDY_DATE", today)
        editor.putInt("CURRENT_STREAK_DAYS", currentStreak)

        val bestStreak = prefs.getInt("BEST_STREAK_DAYS", 0)
        var newAward: AwardEvent? = null

        if (currentStreak > bestStreak) {
            editor.putInt("BEST_STREAK_DAYS", currentStreak)
            if (currentStreak >= 3) {
                newAward = AwardEvent("رقم قياسي جديد!", "أطول استمرارية لك: $currentStreak يوم متواصل 🔥")
            }
        }

        when (currentStreak) {
            3 -> unlockBadge("شرارة البداية (3 أيام) 🔥")
            7 -> {
                unlockBadge("أسبوع من نار (7 أيام) 🔥🔥")
                newAward = AwardEvent("جائزة أسبوع استمرارية!", "معدل الشعلات زاد الآن إلى 7 🔥 لكل 5 دقائق!")
            }
            14 -> {
                unlockBadge("انضباط حديدي (أسبوعين) ⚡")
                newAward = AwardEvent("أسبوعان بدون انقطاع!", "معدل الشعلات زاد الآن إلى 8 🔥 لكل 5 دقائق!")
            }
            30 -> unlockBadge("أسطورة الشهر (30 يوم) 👑")
        }

        editor.apply()
        return newAward
    }

    // Day 1: 5 flames/5m | Days 2-6: 6 flames/5m | Week 1 (days 7-13): 7 flames/5m | Week 2: 8 flames/5m ...
    fun getFlamesPerFiveMinutes(): Int {
        val streak = getValidCurrentStreak()
        return if (streak < 2) 5 else 6 + (streak / 7)
    }

    // Adds earned flames to both permanent TOTAL_XP and spendable wallet SPENDABLE_FLAMES
    fun addOneMinuteFlames(sessionMinutes: Long, todayTotalMinutes: Long): List<AwardEvent> {
        val events = mutableListOf<AwardEvent>()
        checkAndUpdateStreakOnStudy()?.let { events.add(it) }

        val oldLevel = getProfileStatus().level
        val minuteCounter = prefs.getInt("FIVE_MIN_STEP", 0) + 1
        val flamesPerFive = getFlamesPerFiveMinutes()

        val earnedFlames = if (minuteCounter >= 5) {
            val bonus = flamesPerFive - 5
            prefs.edit().putInt("FIVE_MIN_STEP", 0).apply()
            1L + bonus
        } else {
            prefs.edit().putInt("FIVE_MIN_STEP", minuteCounter).apply()
            1L
        }

        val newTotalXp = getTotalXp() + earnedFlames
        val newSpendable = getSpendableFlames() + earnedFlames

        prefs.edit()
            .putLong("TOTAL_XP", newTotalXp)
            .putLong("SPENDABLE_FLAMES", newSpendable)
            .apply()

        val newProfile = getProfileStatus(newTotalXp, newSpendable)
        if (newProfile.level > oldLevel) {
            events.add(
                AwardEvent(
                    "🎉 ترقية بروفايل! المستوى ${newProfile.level}",
                    "رتبتك الجديدة: ${newProfile.rankEmoji} ${newProfile.rankTitle}"
                )
            )
        }

        val bestSession = prefs.getLong("BEST_SESSION_MINS", 0L)
        if (sessionMinutes > bestSession) {
            prefs.edit().putLong("BEST_SESSION_MINS", sessionMinutes).apply()
            if (sessionMinutes >= 30 && sessionMinutes % 15 == 0L) {
                unlockBadge("بطل الجلسات (${sessionMinutes}د) 🏆")
                events.add(AwardEvent("رقم قياسي جديد!", "أطول جلسة متواصلة: $sessionMinutes دقيقة 🏆"))
            }
        }

        val bestDay = prefs.getLong("BEST_DAY_MINS", 0L)
        if (todayTotalMinutes > bestDay) {
            prefs.edit().putLong("BEST_DAY_MINS", todayTotalMinutes).apply()
            if (todayTotalMinutes == 180L) unlockBadge("وحش اليوم (3 ساعات) 🦁")
            if (todayTotalMinutes == 360L) unlockBadge("إنجاز خارق (6 ساعات) 💎")
        }

        return events
    }

    // Spends flames from wallet only (never deducts from permanent XP)
    fun spendFlames(amount: Long): Boolean {
        val current = getSpendableFlames()
        if (current < amount) return false
        prefs.edit().putLong("SPENDABLE_FLAMES", current - amount).apply()
        return true
    }

    fun getAvailableSocialMinutes(): Long = getSpendableFlames() / 6L

    fun getProfileStatus(
        xp: Long = getTotalXp(),
        wallet: Long = getSpendableFlames()
    ): ProfileStatus {
        if (xp >= 6000L) {
            val extraLevels = ((xp - 6000L) / 2500L).toInt()
            val level = levelThresholds.size + extraLevels
            val currentMin = 6000L + (extraLevels * 2500L)
            val nextMin = currentMin + 2500L
            val progress = (((xp - currentMin) * 100L) / 2500L).toInt()
            val title = if (extraLevels == 0) "أسطورة" else "أسطورة خالدة"
            return ProfileStatus(level, title, "👑", xp, wallet, progress.coerceIn(0, 100), nextMin - xp)
        }

        var currentIdx = 0
        for (i in levelThresholds.indices) {
            if (xp >= levelThresholds[i].first) currentIdx = i
        }

        val currentMin = levelThresholds[currentIdx].first
        val nextMin = levelThresholds[(currentIdx + 1).coerceAtMost(levelThresholds.lastIndex)].first
        val range = maxOf(1L, nextMin - currentMin)
        val progress = (((xp - currentMin) * 100L) / range).toInt()

        return ProfileStatus(
            level = currentIdx + 1,
            rankTitle = levelThresholds[currentIdx].second,
            rankEmoji = levelThresholds[currentIdx].third,
            totalXp = xp,
            spendableFlames = wallet,
            progressPercent = progress.coerceIn(0, 100),
            xpToNextLevel = maxOf(0L, nextMin - xp)
        )
    }

    fun getValidCurrentStreak(): Int {
        val lastDate = prefs.getString("LAST_STUDY_DATE", "") ?: ""
        return if (lastDate == getDateKey(0) || lastDate == getDateKey(-1)) {
            prefs.getInt("CURRENT_STREAK_DAYS", 0)
        } else {
            0
        }
    }

    fun getTotalXp(): Long = prefs.getLong("TOTAL_XP", 0L)
    fun getSpendableFlames(): Long = prefs.getLong("SPENDABLE_FLAMES", 0L)
    fun getBestSessionMins(): Long = prefs.getLong("BEST_SESSION_MINS", 0L)
    fun getBestDayMins(): Long = prefs.getLong("BEST_DAY_MINS", 0L)
    fun getBestStreakDays(): Int = prefs.getInt("BEST_STREAK_DAYS", 0)

    fun getUnlockedBadges(): Set<String> {
        return prefs.getStringSet("UNLOCKED_BADGES", emptySet())?.toSet() ?: emptySet()
    }

    private fun unlockBadge(badgeName: String) {
        val current = getUnlockedBadges().toMutableSet()
        if (current.add(badgeName)) {
            prefs.edit().putStringSet("UNLOCKED_BADGES", current).apply()
        }
    }

    private fun getDateKey(offsetDays: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }
}
