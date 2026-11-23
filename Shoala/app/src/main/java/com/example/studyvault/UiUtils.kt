package com.example.studyvault

import android.widget.Button
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** The idle text of the session service. Screens hide the status line while it is showing. */
const val DEFAULT_STATUS_MESSAGE = "اختر نوع المؤقت ثم ابدأ التركيز"

private val EMOJI_REGEX = Regex("[\\p{So}\\uFE0F\\u200D]")
private val MULTI_SPACE_REGEX = Regex(" {2,}")

/**
 * Removes emoji so messages coming from services and the gamification layer
 * read as clean text in the UI. Display-only: stored values are never changed.
 */
fun String.stripEmoji(): String =
    EMOJI_REGEX.replace(this, "")
        .lines()
        .joinToString("\n") { it.replace(MULTI_SPACE_REGEX, " ").trim() }
        .trim()

/** Segmented-control look (selected = solid red with white text, idle = transparent). */
fun Button.applySegmentStyle(selected: Boolean) {
    setBackgroundResource(if (selected) R.drawable.bg_segment_selected else R.drawable.bg_segment_idle)
    setTextColor(ContextCompat.getColor(context, if (selected) R.color.on_accent else R.color.text_secondary))
}

/** Preset-chip look used by the duration picker. */
fun Button.applyChipStyle(selected: Boolean) {
    setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_idle)
    setTextColor(ContextCompat.getColor(context, if (selected) R.color.on_accent else R.color.text_secondary))
}

/** "3 س و20 د" / "45 د" - used for lock countdowns. */
fun formatRemaining(ms: Long): String {
    val totalMins = maxOf(1L, (ms + 59_999L) / 60_000L)
    val h = totalMins / 60L
    val m = totalMins % 60L
    return when {
        h > 0 && m > 0 -> "$h س و$m د"
        h > 0 -> "$h س"
        else -> "$m د"
    }
}

/** The daily social-media allowance resets at this hour (06:00) instead of midnight. */
const val SOCIAL_DAY_START_HOUR = 6

/**
 * Date key of the current "social day". Between 00:00 and 05:59 it is still
 * yesterday's key, so late-night scrolling counts toward the previous day.
 */
fun socialDayKey(): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.HOUR_OF_DAY, -SOCIAL_DAY_START_HOUR)
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
}

/** The next 06:00 in the future: when the social allowance resets. */
fun nextSocialDayStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, SOCIAL_DAY_START_HOUR)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
    return cal.timeInMillis
}
