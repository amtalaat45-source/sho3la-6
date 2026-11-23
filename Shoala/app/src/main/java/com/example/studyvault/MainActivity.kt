package com.example.studyvault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Home page: level, lock status, social-time balance, and the entry point to focus mode. */
class MainActivity : BaseActivity() {

    /** What the lock-status card should show for the current state. */
    private class LockUi(val iconRes: Int, val tintRes: Int, val title: String, val subtitle: String)

    private lateinit var gamificationManager: GamificationManager

    private lateinit var levelBadgeText: TextView
    private lateinit var profileRankText: TextView
    private lateinit var levelLabelText: TextView
    private lateinit var flamesCountText: TextView
    private lateinit var levelProgressBar: ProgressBar
    private lateinit var totalXpText: TextView
    private lateinit var nextLevelText: TextView
    private lateinit var streakValueText: TextView
    private lateinit var rateValueText: TextView
    private lateinit var lockIcon: ImageView
    private lateinit var lockTitleText: TextView
    private lateinit var lockSubtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var rewardText: TextView
    private lateinit var bingeStatsText: TextView
    private lateinit var bingeProgress: ProgressBar
    private lateinit var btnStartFocus: Button
    private lateinit var btnSpendFlames: Button

    // Avoid re-inflating the icon every second (refreshUI runs once per second).
    private var shownLockIcon = 0
    private var shownLockTint = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gamificationManager = GamificationManager(this)

        bindViews()
        setupBottomNav(NavTab.HOME)
        requestRequiredPermissions()
        setupButtons()
        refreshUI()

        if (savedInstanceState == null) handleNfcIntent(intent)
    }

    private fun bindViews() {
        levelBadgeText = findViewById(R.id.levelBadgeText)
        profileRankText = findViewById(R.id.profileRankText)
        levelLabelText = findViewById(R.id.levelLabelText)
        flamesCountText = findViewById(R.id.flamesCountText)
        levelProgressBar = findViewById(R.id.levelProgressBar)
        totalXpText = findViewById(R.id.totalXpText)
        nextLevelText = findViewById(R.id.nextLevelText)
        streakValueText = findViewById(R.id.streakValueText)
        rateValueText = findViewById(R.id.rateValueText)
        lockIcon = findViewById(R.id.lockIcon)
        lockTitleText = findViewById(R.id.lockTitleText)
        lockSubtitleText = findViewById(R.id.lockSubtitleText)
        statusText = findViewById(R.id.statusText)
        rewardText = findViewById(R.id.rewardText)
        bingeStatsText = findViewById(R.id.bingeStatsText)
        bingeProgress = findViewById(R.id.bingeProgress)
        btnStartFocus = findViewById(R.id.btnStartFocus)
        btnSpendFlames = findViewById(R.id.btnSpendFlames)
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    private fun setupButtons() {
        btnStartFocus.setOnClickListener { startActivity(Intent(this, TimerActivity::class.java)) }

        btnSpendFlames.setOnClickListener {
            val prefs = appPrefs()
            val isAppActive = prefs.getBoolean("IS_APP_ACTIVE", false)

            if (!isAppActive) {
                Toast.makeText(this, "البرنامج مقفول بالكارت أصلاً والسوشيال مفتوح", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (StudySessionService.isStudying) {
                Toast.makeText(this, "أنهِ جلسة المذاكرة أولاً لصرف الشعلات", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentlySpending = prefs.getBoolean("IS_SPENDING_FLAMES_MODE", false)
            if (!currentlySpending) {
                if (gamificationManager.getSpendableFlames() <= 0L) {
                    Toast.makeText(this, "رصيدك 0 شعلة! ذاكر لجمع شعلات أولاً", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putBoolean("IS_SPENDING_FLAMES_MODE", true).apply()
                Toast.makeText(this, "تم فتح السوشيال بالشعلات (الدقيقة = 6 شعلات)", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putBoolean("IS_SPENDING_FLAMES_MODE", false).apply()
                Toast.makeText(this, "تم إعادة قفل السوشيال والحفاظ على شعلاتك", Toast.LENGTH_SHORT).show()
            }
            refreshUI()
        }
    }

    override fun refreshUI() {
        val prefs = appPrefs()
        val isAppActive = prefs.getBoolean("IS_APP_ACTIVE", false)
        val isSpendingFlames = prefs.getBoolean("IS_SPENDING_FLAMES_MODE", false)
        val now = System.currentTimeMillis()
        val penaltyEnd = prefs.getLong("PENALTY_END_TIME", 0L)
        val bingeLockEnd = prefs.getLong("BINGE_LOCK_END_TIME", 0L)

        // ---- Lock status card ----
        val lock = when {
            now < bingeLockEnd -> LockUi(
                R.drawable.ic_warning, R.color.danger,
                "وصلت حد السوشيال اليومي",
                "مقفول لحد 6 الصبح (متبقي ${formatRemaining(bingeLockEnd - now)})"
            )
            now < penaltyEnd -> LockUi(
                R.drawable.ic_warning, R.color.danger,
                "عقوبة رفع الهاتف",
                "متبقي ${formatRemaining(penaltyEnd - now)}"
            )
            isAppActive && isSpendingFlames -> LockUi(
                R.drawable.ic_flame, R.color.accent,
                "وضع صرف الشعلات",
                "يُخصم 6 شعلات عن كل دقيقة سوشيال"
            )
            isAppActive -> LockUi(
                R.drawable.ic_lock, R.color.success,
                "قفل السوشيال مفعّل",
                "فيسبوك وإنستا ويوتيوب محظورة الآن"
            )
            else -> LockUi(
                R.drawable.ic_lock_open, R.color.text_secondary,
                "قفل السوشيال متوقف",
                "السوشيال متاح بحد أقصى 3 ساعات يومياً (تتجدد 6 الصبح). المس كارت NFC للتفعيل"
            )
        }
        if (shownLockIcon != lock.iconRes) {
            lockIcon.setImageResource(lock.iconRes)
            shownLockIcon = lock.iconRes
        }
        if (shownLockTint != lock.tintRes) {
            lockIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, lock.tintRes))
            shownLockTint = lock.tintRes
        }
        lockTitleText.text = lock.title
        lockSubtitleText.text = lock.subtitle

        // Service messages only appear when there is something to say.
        val message = StudySessionService.statusMessage.stripEmoji()
        if (message.isBlank() || message == DEFAULT_STATUS_MESSAGE) {
            statusText.visibility = View.GONE
        } else {
            statusText.text = message
            statusText.visibility = View.VISIBLE
        }

        btnStartFocus.text = if (StudySessionService.isStudying) "العودة إلى الجلسة" else "ابدأ التركيز"
        btnSpendFlames.text = if (isSpendingFlames) "إيقاف الصرف وقفل السوشيال" else "فتح السوشيال بالشعلات"

        // ---- Social balance card ----
        rewardText.text = "${gamificationManager.getAvailableSocialMinutes()} د"

        val today = socialDayKey()
        val bingeSecs = if (prefs.getString("BINGE_DATE", "") == today) prefs.getLong("BINGE_SOCIAL_SECONDS", 0L) else 0L
        bingeStatsText.text = "${bingeSecs / 60L} / 180 د"
        bingeProgress.progress = ((bingeSecs * 100L) / (180L * 60L)).toInt().coerceIn(0, 100)

        // ---- Level card ----
        val profile = gamificationManager.getProfileStatus()
        val streak = gamificationManager.getValidCurrentStreak()
        val rate = gamificationManager.getFlamesPerFiveMinutes()

        levelBadgeText.text = profile.level.toString()
        profileRankText.text = profile.rankTitle
        levelLabelText.text = "المستوى ${profile.level}"
        flamesCountText.text = profile.spendableFlames.toString()
        levelProgressBar.progress = profile.progressPercent
        totalXpText.text = "${profile.totalXp} نقطة خبرة"
        nextLevelText.text = "متبقي ${profile.xpToNextLevel} للمستوى ${profile.level + 1}"
        streakValueText.text = "$streak يوم"
        rateValueText.text = "$rate شعلات / 5 د"
    }
}
