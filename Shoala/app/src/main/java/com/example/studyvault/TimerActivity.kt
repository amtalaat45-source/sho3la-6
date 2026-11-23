package com.example.studyvault

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max

/**
 * Focus page.
 *  1. Setup: count-up or countdown.
 *  2. Focus: big timer inside a progress ring, end button, "phone removed" alert.
 *  3. Ended: the final time stays on screen (it does NOT reset) until "new session".
 */
class TimerActivity : BaseActivity() {

    companion object {
        private const val DEFAULT_STATUS = DEFAULT_STATUS_MESSAGE
    }

    // ---- timer options ----
    private var isCountdown = false
    private var minutes = 25

    // ---- session / UI state ----
    private var sessionEnded = false
    private var wasStudying = false
    private var pendingStart = false
    private var pendingStartAt = 0L
    private var pendingTarget = 0L

    // ---- views ----
    private lateinit var setupGroup: LinearLayout
    private lateinit var focusGroup: LinearLayout
    private lateinit var countdownPanel: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var btnModeUp: Button
    private lateinit var btnModeDown: Button
    private lateinit var modeHintText: TextView
    private lateinit var minutesText: TextView
    private lateinit var btnMinus: ImageButton
    private lateinit var btnPlus: ImageButton
    private lateinit var chips: List<Pair<Button, Int>>
    private lateinit var btnStart: Button
    private lateinit var modeLabelText: TextView
    private lateinit var timerText: TextView
    private lateinit var timerCaptionText: TextView
    private lateinit var countdownProgress: RingProgressView
    private lateinit var alertCard: TextView
    private lateinit var btnEnd: Button
    private lateinit var btnNewSession: Button
    private lateinit var statusText: TextView

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timer)

        val p = appPrefs()
        isCountdown = p.getBoolean("TIMER_MODE_DOWN", false)
        minutes = p.getInt("TIMER_MINUTES", 25).coerceIn(1, 300)

        // If the session finished while this page was closed, show its final time.
        sessionEnded = StudySessionService.sessionCompleted && !StudySessionService.isStudying

        bindViews()
        setupClicks()
        applyModeUI()
        refreshUI()
    }

    private fun bindViews() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        setupGroup = findViewById(R.id.setupGroup)
        focusGroup = findViewById(R.id.focusGroup)
        countdownPanel = findViewById(R.id.countdownPanel)
        controlsRow = findViewById(R.id.controlsRow)
        btnModeUp = findViewById(R.id.btnModeUp)
        btnModeDown = findViewById(R.id.btnModeDown)
        modeHintText = findViewById(R.id.modeHintText)
        minutesText = findViewById(R.id.minutesText)
        btnMinus = findViewById(R.id.btnMinus)
        btnPlus = findViewById(R.id.btnPlus)
        chips = listOf(
            findViewById<Button>(R.id.chip15) to 15,
            findViewById<Button>(R.id.chip25) to 25,
            findViewById<Button>(R.id.chip45) to 45,
            findViewById<Button>(R.id.chip60) to 60,
            findViewById<Button>(R.id.chip90) to 90
        )
        btnStart = findViewById(R.id.btnStart)
        modeLabelText = findViewById(R.id.modeLabelText)
        timerText = findViewById(R.id.timerText)
        timerCaptionText = findViewById(R.id.timerCaptionText)
        countdownProgress = findViewById(R.id.countdownProgress)
        alertCard = findViewById(R.id.alertCard)
        btnEnd = findViewById(R.id.btnEnd)
        btnNewSession = findViewById(R.id.btnNewSession)
        statusText = findViewById(R.id.statusText)
    }

    private fun setupClicks() {
        btnModeUp.setOnClickListener { setMode(false) }
        btnModeDown.setOnClickListener { setMode(true) }
        btnMinus.setOnClickListener { setMinutes(max(1, minutes - 5)) }
        btnPlus.setOnClickListener { setMinutes(minOf(300, minutes + 5)) }
        chips.forEach { (btn, value) -> btn.setOnClickListener { setMinutes(value) } }

        btnStart.setOnClickListener { startSession() }
        btnEnd.setOnClickListener { endSession() }
        btnNewSession.setOnClickListener { newSession() }
    }

    // =====================================================================
    // Setup options
    // =====================================================================

    private fun setMode(down: Boolean) {
        isCountdown = down
        appPrefs().edit().putBoolean("TIMER_MODE_DOWN", down).apply()
        applyModeUI()
    }

    private fun setMinutes(value: Int) {
        minutes = value
        appPrefs().edit().putInt("TIMER_MINUTES", value).apply()
        applyModeUI()
    }

    private fun applyModeUI() {
        btnModeUp.applySegmentStyle(!isCountdown)
        btnModeDown.applySegmentStyle(isCountdown)
        countdownPanel.visibility = if (isCountdown) View.VISIBLE else View.GONE
        modeHintText.text = if (isCountdown) {
            "التنازلي: تحدد المدة والمؤقت يعد لحد الصفر وتنتهي الجلسة تلقائياً."
        } else {
            "التصاعدي: يعد من 00:00 لحد ما توقفه بنفسك."
        }
        minutesText.text = minutes.toString()
        chips.forEach { (btn, value) -> btn.applyChipStyle(value == minutes) }
    }

    // =====================================================================
    // Start / end
    // =====================================================================

    private fun startSession() {
        if (StudySessionService.isStudying) return
        sessionEnded = false

        val target = if (isCountdown) minutes * 60L else 0L
        val serviceIntent = Intent(this, StudySessionService::class.java)
            .putExtra(StudySessionService.EXTRA_TARGET_SECONDS, target)
        ContextCompat.startForegroundService(this, serviceIntent)

        pendingStart = true
        pendingStartAt = SystemClock.elapsedRealtime()
        pendingTarget = target

        Toast.makeText(this, "بدأ العد! ضع الموبايل ثابتاً على المكتب", Toast.LENGTH_SHORT).show()
        refreshUI()
    }

    private fun endSession() {
        if (!StudySessionService.isStudying) return
        val mins = StudySessionService.studySeconds / 60L
        stopService(Intent(this, StudySessionService::class.java))
        StudySessionService.statusMessage = "تم إنهاء الجلسة وحفظ $mins دقيقة"
        Toast.makeText(this, "تم حفظ الدقائق والشعلات بنجاح", Toast.LENGTH_SHORT).show()
        // Keep the final time on screen instead of resetting it to zero.
        sessionEnded = true
        wasStudying = false
        refreshUI()
    }

    private fun newSession() {
        sessionEnded = false
        wasStudying = false
        pendingStart = false
        StudySessionService.sessionCompleted = false
        if (!StudySessionService.isStudying) StudySessionService.statusMessage = DEFAULT_STATUS
        refreshUI()
    }

    // =====================================================================
    // UI refresh (every second)
    // =====================================================================

    private fun fmt(totalSeconds: Long) =
        String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)

    override fun refreshUI() {
        val studying = StudySessionService.isStudying
        if (studying) {
            wasStudying = true
            pendingStart = false
        }
        if (wasStudying && !studying) {
            // Session ended by itself (countdown finished, NFC unlock, penalty...)
            wasStudying = false
            sessionEnded = true
        }
        if (pendingStart && SystemClock.elapsedRealtime() - pendingStartAt > 3000L) pendingStart = false

        val showFocus = sessionEnded || studying || pendingStart
        setupGroup.visibility = if (showFocus) View.GONE else View.VISIBLE
        focusGroup.visibility = if (showFocus) View.VISIBLE else View.GONE

        val message = StudySessionService.statusMessage.stripEmoji()
        if (message.isBlank() || message == DEFAULT_STATUS) {
            statusText.visibility = View.GONE
        } else {
            statusText.text = message
            statusText.visibility = View.VISIBLE
        }

        if (showFocus) updateFocus(studying)
    }

    private fun updateFocus(studying: Boolean) {
        if (sessionEnded) {
            // Final time stays visible.
            modeLabelText.text = "انتهت الجلسة"
            timerCaptionText.text = "مدة المذاكرة"
            timerText.text = fmt(StudySessionService.studySeconds)
            countdownProgress.showsProgress = true
            countdownProgress.progress = 100
            alertCard.visibility = View.GONE
            controlsRow.visibility = View.GONE
            btnNewSession.visibility = View.VISIBLE
            return
        }

        controlsRow.visibility = View.VISIBLE
        btnNewSession.visibility = View.GONE

        val target = if (studying) StudySessionService.targetSeconds else pendingTarget
        val elapsed = if (studying) StudySessionService.studySeconds else 0L
        if (target > 0L) {
            timerText.text = fmt(max(0L, target - elapsed))
            modeLabelText.text = "مؤقت تنازلي"
            timerCaptionText.text = "الوقت المتبقي"
            countdownProgress.showsProgress = true
            countdownProgress.progress = ((elapsed * 100L) / target).toInt().coerceIn(0, 100)
        } else {
            timerText.text = fmt(elapsed)
            modeLabelText.text = "مؤقت تصاعدي"
            timerCaptionText.text = "وقت التركيز"
            countdownProgress.showsProgress = false
        }

        // "Phone removed" alert
        val showAlert = studying && StudySessionService.alarmArmed &&
                StudySessionService.isPhoneLifted && !StudySessionService.isInCall
        if (showAlert) {
            val cd = StudySessionService.alarmCountdown
            val grace = StudySessionService.graceSecondsLeft
            alertCard.text = when {
                cd > 0 -> "الهاتف خرج من مكانه\nالإنذار بعد $cd ث"
                StudySessionService.isAlarmRinging -> "رجّع الهاتف مكانه لإيقاف الإنذار\nالجلسة تنتهي بعد $grace ث"
                else -> "الهاتف خارج مكانه. رجّعه خلال $grace ث"
            }
            alertCard.visibility = View.VISIBLE
        } else {
            alertCard.visibility = View.GONE
        }
    }
}
