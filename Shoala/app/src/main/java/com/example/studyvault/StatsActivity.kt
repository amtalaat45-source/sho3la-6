package com.example.studyvault

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Charts, totals, records and badges. */
class StatsActivity : BaseActivity() {

    private lateinit var statsManager: StatsManager
    private lateinit var gamificationManager: GamificationManager

    private lateinit var chartTitleText: TextView
    private lateinit var btnWeeklyChart: Button
    private lateinit var btnMonthlyChart: Button
    private lateinit var weeklyChart: WeeklyBarChartView
    private lateinit var todayStatsText: TextView
    private lateinit var weekStatsText: TextView
    private lateinit var monthStatsText: TextView
    private lateinit var yearStatsText: TextView
    private lateinit var bestSessionText: TextView
    private lateinit var bestDayText: TextView
    private lateinit var bestStreakText: TextView
    private lateinit var badgesContainer: LinearLayout
    private lateinit var badgesEmptyText: TextView

    private var isShowingMonthlyChart = false

    // refreshUI() runs every second; only rebuild the badge rows when they change.
    private var renderedBadges: List<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        statsManager = StatsManager(this)
        gamificationManager = GamificationManager(this)

        chartTitleText = findViewById(R.id.chartTitleText)
        btnWeeklyChart = findViewById(R.id.btnWeeklyChart)
        btnMonthlyChart = findViewById(R.id.btnMonthlyChart)
        weeklyChart = findViewById(R.id.weeklyChart)
        todayStatsText = findViewById(R.id.todayStatsText)
        weekStatsText = findViewById(R.id.weekStatsText)
        monthStatsText = findViewById(R.id.monthStatsText)
        yearStatsText = findViewById(R.id.yearStatsText)
        bestSessionText = findViewById(R.id.bestSessionText)
        bestDayText = findViewById(R.id.bestDayText)
        bestStreakText = findViewById(R.id.bestStreakText)
        badgesContainer = findViewById(R.id.badgesContainer)
        badgesEmptyText = findViewById(R.id.badgesEmptyText)

        setupBottomNav(NavTab.STATS)

        btnWeeklyChart.setOnClickListener {
            isShowingMonthlyChart = false
            applyChartMode()
        }
        btnMonthlyChart.setOnClickListener {
            isShowingMonthlyChart = true
            applyChartMode()
        }

        applyChartMode()
        refreshUI()
    }

    private fun applyChartMode() {
        btnWeeklyChart.applySegmentStyle(!isShowingMonthlyChart)
        btnMonthlyChart.applySegmentStyle(isShowingMonthlyChart)
        chartTitleText.text = if (isShowingMonthlyChart) "هذا الشهر" else "آخر 7 أيام"
        refreshChart()
    }

    override fun refreshUI() {
        todayStatsText.text = formatMinutes(statsManager.getTodayMinutes())
        weekStatsText.text = formatMinutes(statsManager.getWeekMinutes())
        monthStatsText.text = formatMinutes(statsManager.getMonthMinutes())
        yearStatsText.text = formatMinutes(statsManager.getYearMinutes())

        bestSessionText.text = formatMinutes(gamificationManager.getBestSessionMins())
        bestDayText.text = formatMinutes(gamificationManager.getBestDayMins())
        bestStreakText.text = "${gamificationManager.getBestStreakDays()} يوم"

        // Stored badge names contain emoji; strip them for display only.
        val badges = gamificationManager.getUnlockedBadges().map { it.stripEmoji() }.sorted()
        if (badges != renderedBadges) {
            renderBadges(badges)
            renderedBadges = badges
        }

        refreshChart()
    }

    private fun renderBadges(badges: List<String>) {
        badgesContainer.removeAllViews()
        badgesEmptyText.visibility = if (badges.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (name in badges) {
            val row = inflater.inflate(R.layout.item_badge, badgesContainer, false)
            row.findViewById<TextView>(R.id.badgeNameText).text = name
            badgesContainer.addView(row)
        }
    }

    private fun refreshChart() {
        if (isShowingMonthlyChart) {
            weeklyChart.setData(statsManager.getCurrentMonthChartData(), isMonthly = true)
        } else {
            weeklyChart.setData(statsManager.getLast7DaysChartData(), isMonthly = false)
        }
    }

    private fun formatMinutes(totalMins: Long): String {
        val hours = totalMins / 60L
        val mins = totalMins % 60L
        return if (hours > 0) "${hours}س ${mins}د" else "${mins} د"
    }
}
