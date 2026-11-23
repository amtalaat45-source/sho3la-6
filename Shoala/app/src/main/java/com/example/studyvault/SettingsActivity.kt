package com.example.studyvault

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/** Alarm toggle, accessibility (social blocker) activation, NFC card reset, and diagnostics. */
class SettingsActivity : BaseActivity() {

    private lateinit var alarmSwitch: SwitchCompat
    private lateinit var accessibilityStatusText: TextView
    private lateinit var nfcStatusText: TextView
    private lateinit var diagChevron: ImageView
    private lateinit var debugText: TextView

    private var diagnosticsOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        alarmSwitch = findViewById(R.id.alarmSwitch)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        nfcStatusText = findViewById(R.id.nfcStatusText)
        diagChevron = findViewById(R.id.diagChevron)
        debugText = findViewById(R.id.debugText)

        setupBottomNav(NavTab.SETTINGS)

        // ---- Alarm: the whole row toggles the switch; the click listener saves the new value ----
        alarmSwitch.setOnClickListener {
            val on = alarmSwitch.isChecked
            appPrefs().edit().putBoolean("ALARM_ENABLED", on).apply()
            Toast.makeText(
                this,
                if (on) "تم تشغيل إنذار رفع الهاتف" else "تم إيقاف إنذار رفع الهاتف",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<View>(R.id.rowAlarm).setOnClickListener { alarmSwitch.performClick() }

        // ---- Accessibility service ----
        findViewById<View>(R.id.rowAccessibility).setOnClickListener {
            if (isAccessibilityEnabled()) {
                Toast.makeText(this, "الخدمة مفعّلة بالفعل (لو غيّرت التطبيق: أطفئها وشغّلها مرة أخرى)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "فعّل تطبيق 'شعلة' من قائمة التطبيقات المثبتة", Toast.LENGTH_LONG).show()
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ---- NFC card ----
        findViewById<View>(R.id.rowNfc).setOnClickListener {
            val prefs = appPrefs()
            if (prefs.getBoolean("IS_APP_ACTIVE", false) || StudySessionService.isStudying) {
                Toast.makeText(this, "المس الكارت القديم لفك القفل أولاً قبل تغييره!", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().remove("SAVED_NFC_UID").apply()
                Toast.makeText(this, "تم مسح الكارت القديم. المس الكارت الجديد لتسجيله", Toast.LENGTH_SHORT).show()
                refreshUI()
            }
        }

        // ---- Diagnostics (hidden until expanded) ----
        findViewById<View>(R.id.rowDiagnostics).setOnClickListener {
            diagnosticsOpen = !diagnosticsOpen
            debugText.visibility = if (diagnosticsOpen) View.VISIBLE else View.GONE
            diagChevron.animate().rotation(if (diagnosticsOpen) -90f else 0f).setDuration(150L).start()
            refreshUI()
        }

        findViewById<TextView>(R.id.versionText).text = "شعلة  •  الإصدار ${appVersionName()}"

        refreshUI()
    }

    override fun refreshUI() {
        alarmSwitch.isChecked = appPrefs().getBoolean("ALARM_ENABLED", true)

        val a11yOn = isAccessibilityEnabled()
        setPill(accessibilityStatusText, if (a11yOn) "مفعّل" else "غير مفعّل", if (a11yOn) PillKind.SUCCESS else PillKind.DANGER)

        val nfcRegistered = appPrefs().getString("SAVED_NFC_UID", null) != null
        setPill(nfcStatusText, if (nfcRegistered) "مسجّل" else "غير مسجّل", if (nfcRegistered) PillKind.SUCCESS else PillKind.NEUTRAL)

        if (diagnosticsOpen) {
            val ago = if (SocialBlockerService.lastTickElapsed == 0L) -1L
            else (SystemClock.elapsedRealtime() - SocialBlockerService.lastTickElapsed) / 1000L
            debugText.text = "الخدمة: ${if (SocialBlockerService.connected) "متصلة" else "غير متصلة"}" +
                    "  |  آخر فحص: ${if (ago < 0) "-" else "قبل $ago ث"}\n" +
                    "آخر سوشيال اتشاف: ${SocialBlockerService.lastSocialPkg} (${SocialBlockerService.lastSignal})\n" +
                    "آخر قرار: ${SocialBlockerService.lastDecision}"
        }
    }

    private enum class PillKind { SUCCESS, DANGER, NEUTRAL }

    private fun setPill(view: TextView, text: String, kind: PillKind) {
        view.text = text
        when (kind) {
            PillKind.SUCCESS -> {
                view.setBackgroundResource(R.drawable.bg_pill_success)
                view.setTextColor(ContextCompat.getColor(this, R.color.success))
            }
            PillKind.DANGER -> {
                view.setBackgroundResource(R.drawable.bg_pill_danger)
                view.setTextColor(ContextCompat.getColor(this, R.color.danger))
            }
            PillKind.NEUTRAL -> {
                view.setBackgroundResource(R.drawable.bg_pill_neutral)
                view.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val full = "$packageName/${SocialBlockerService::class.java.name}"
        val short = "$packageName/.SocialBlockerService"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }
}
