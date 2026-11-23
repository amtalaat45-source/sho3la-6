package com.example.studyvault

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * Social blocker (Facebook / Instagram / YouTube; WhatsApp is never touched).
 *
 * How it works (designed to be hard to bypass and to never block by mistake):
 *  1. Event driven: any window change re-checks the screen immediately.
 *  2. Prefs driven: the moment the NFC card locks the app (IS_APP_ACTIVE becomes true)
 *     the service checks the screen right away and kicks the user out.
 *  3. Watchdog: a light 400 ms loop catches launches the events missed
 *     (e.g. opening an app from the app drawer / launcher on HyperOS / MIUI).
 *  4. "Is a social app on screen?" uses TWO independent signals: any visible app window of a
 *     blocked package in the real window list (reopening a cached app is caught too), plus the
 *     latest window event as a backup. A background app has no window, so it is never blocked.
 *  5. After pressing HOME it re-checks and presses it again (up to 5 times)
 *     if the social app is still on screen.
 */
class SocialBlockerService : AccessibilityService() {

    private val blockedSocialApps = setOf(
        "com.facebook.katana",
        "com.facebook.lite",
        "com.instagram.android",
        "com.instagram.lite",
        "com.google.android.youtube"
    )

    private var imePackages: Set<String> = emptySet()

    private lateinit var prefs: SharedPreferences
    private lateinit var gamificationManager: GamificationManager
    private val handler = Handler(Looper.getMainLooper())

    private var lastToastTime = 0L
    private var lastHomeTime = 0L
    private var lastTickTime = 0L
    private var lastTickWasSocial = false
    private var retryCount = 0
    private var lastEventPkg = ""
    private var lastEventAt = 0L
    private var lastContentCheckAt = 0L

    // window id -> package, learned from events / readable roots (used when a root can't be read)
    private val windowPkgById = HashMap<Int, String>()

    // time accumulators (ms) so a 400 ms loop still counts whole seconds correctly
    private var bingeMsAccum = 0L
    private var spendMsAccum = 0L

    // must stay a field: SharedPreferences keeps listeners in a weak map
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "IS_APP_ACTIVE" || key == "IS_SPENDING_FLAMES_MODE" ||
            key == "PENALTY_END_TIME" || key == "BINGE_LOCK_END_TIME"
        ) {
            handler.post { checkNow() }
        }
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                // never let the watchdog die
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val retryRunnable = Runnable {
        if (socialPackageOnScreen() != null && blockReason() != null && retryCount < MAX_RETRIES) {
            retryCount++
            goHome()
            scheduleRetry()
        }
    }

    companion object {
        private const val TICK_MS = 400L
        private const val RETRY_DELAY_MS = 350L
        private const val MAX_RETRIES = 5
        private const val FLAME_EVERY_MS = 10_000L   // 1 flame / 10 s  = 6 flames / minute
        private const val BINGE_LIMIT_SECONDS = 10_800L
        private const val EVENT_TRUST_MS = 2000L

        // diagnostics shown in Settings
        @Volatile var connected = false
        @Volatile var lastTickElapsed = 0L
        @Volatile var lastSocialPkg = "-"
        @Volatile var lastDecision = "-"
        @Volatile var lastSignal = "-"
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("StudyVaultPrefs", Context.MODE_PRIVATE)
        gamificationManager = GamificationManager(this)
        refreshImePackages()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)   // never run two loops
        windowPkgById.clear()
        lastTickTime = 0L
        connected = true
        handler.post(monitorRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::prefs.isInitialized) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // A visible social app keeps emitting these (feed, video progress...). This catches a
                // re-opened app whose window-state event never fired and whose root is unreadable.
                val pkg = event.packageName?.toString() ?: return
                if (pkg !in blockedSocialApps) return
                rememberWindow(event.windowId, pkg)
                val now = SystemClock.elapsedRealtime()
                if (now - lastContentCheckAt > 300L) {
                    lastContentCheckAt = now
                    checkNow()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val pkg = event.packageName?.toString()
                    if (!pkg.isNullOrEmpty() && pkg != "com.android.systemui" && !isKeyboardPackage(pkg)) {
                        lastEventPkg = pkg
                        lastEventAt = SystemClock.elapsedRealtime()
                        rememberWindow(event.windowId, pkg)
                    }
                }
                checkNow()
                // the window list can lag behind the event: re-check a few times
                for (d in longArrayOf(100L, 300L, 700L)) handler.postDelayed({ checkNow() }, d)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        connected = false
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // =====================================================================
    // Foreground detection
    // =====================================================================

    private fun refreshImePackages() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePackages = imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) {
        }
    }

    private fun isKeyboardPackage(pkg: String): Boolean =
        imePackages.contains(pkg) ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true)

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /**
     * Blocked social package that is on screen right now, or null.
     * Signal 1: a visible (non picture-in-picture) app window of a blocked package in the window list.
     * Signal 2: the latest window-state event was a blocked package, and it is fresh.
     */
    @Suppress("DEPRECATION")
    private fun socialPackageOnScreen(): String? {
        // Signal 1: visible app windows. If a window's root can't be read (a re-opened app that is
        // still being thawed / drawn returns null), fall back to the package we learned for that
        // window id earlier. Before this fix such a window was skipped, so the 2nd open slipped through.
        try {
            val list = windows
            if (!list.isNullOrEmpty()) {
                for (w in list) {
                    try {
                        if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && w.isInPictureInPictureMode) continue
                        val root = w.root
                        var pkg = root?.packageName?.toString()
                        val rootWasNull = root == null
                        root?.recycle()
                        if (pkg != null) rememberWindow(w.id, pkg) else pkg = windowPkgById[w.id]
                        if (pkg != null && pkg in blockedSocialApps) {
                            lastSignal = if (rootWasNull) "نافذة (id محفوظ)" else "نافذة"
                            return pkg
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }
        // Signal 2: the active window, always checked (not only when the window list is empty)
        try {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            root?.recycle()
            if (pkg != null && pkg in blockedSocialApps) {
                lastSignal = "النافذة النشطة"
                return pkg
            }
        } catch (e: Exception) {
        }
        // Signal 3 (backup): the newest window event says a social app just appeared
        if (lastEventPkg in blockedSocialApps &&
            SystemClock.elapsedRealtime() - lastEventAt < EVENT_TRUST_MS
        ) {
            lastSignal = "حدث نافذة"
            return lastEventPkg
        }
        return null
    }

    private fun rememberWindow(id: Int, pkg: String) {
        if (id < 0) return
        if (windowPkgById.size > 200) windowPkgById.clear()
        windowPkgById[id] = pkg
    }

    // =====================================================================
    // Decision logic
    // =====================================================================

    /** Why social apps must be closed right now, or null if they are allowed. No side effects. */
    private fun blockReason(): String? {
        val now = System.currentTimeMillis()

        val bingeLockEnd = prefs.getLong("BINGE_LOCK_END_TIME", 0L)
        if (now < bingeLockEnd) {
            return "⛔ وصلت حد 3 ساعات سوشيال النهاردة! مقفول ${formatRemaining(bingeLockEnd - now)} (لحد 6 الصبح)"
        }

        val penaltyEnd = prefs.getLong("PENALTY_END_TIME", 0L)
        if (now < penaltyEnd) {
            return "⛔ عقوبة نشطة! ممنوع فيسبوك وإنستغرام ويوتيوب لمدة ${formatRemaining(penaltyEnd - now)}"
        }

        if (StudySessionService.isStudying) {
            return "🔒 أنت في جلسة مذاكرة حالياً! السوشيال مقفول تماماً"
        }

        if (!prefs.getBoolean("IS_APP_ACTIVE", false)) return null   // card not locked: free (3h cap applies)

        if (!prefs.getBoolean("IS_SPENDING_FLAMES_MODE", false)) {
            return "🔒 السوشيال مقفول بالكارت! افتح 'صرف الشعلات' أو المس الكارت"
        }

        if (gamificationManager.getSpendableFlames() <= 0L) {
            return "🔥 رصيدك 0 شعلة! ذاكر لجمع شعلات (الدقيقة = 6 🔥)"
        }
        return null
    }

    /** Check the screen right now (used by events and prefs changes; counts no time). */
    private fun checkNow() {
        if (!::prefs.isInitialized || !isScreenOn()) return
        val pkg = socialPackageOnScreen() ?: return
        lastSocialPkg = pkg
        enforce(0L)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        lastTickElapsed = now
        if (!isScreenOn()) {
            lastTickWasSocial = false
            lastTickTime = now
            return
        }
        val dt = if (lastTickTime == 0L) 0L else (now - lastTickTime).coerceIn(0L, 1500L)
        lastTickTime = now

        val pkg = socialPackageOnScreen()
        if (pkg != null) {
            lastSocialPkg = pkg
            // only count time that was really spent inside the social app
            enforce(if (lastTickWasSocial) dt else 0L)
            lastTickWasSocial = true
        } else {
            lastTickWasSocial = false
        }
    }

    /** Called only when a social app is really on screen. */
    private fun enforce(dtMs: Long) {
        val reason = blockReason()
        lastDecision = reason ?: "مسموح"
        if (reason != null) {
            // out of flames while in spending mode: switch the mode off so the state is clean
            if (prefs.getBoolean("IS_SPENDING_FLAMES_MODE", false) &&
                prefs.getBoolean("IS_APP_ACTIVE", false) &&
                gamificationManager.getSpendableFlames() <= 0L
            ) {
                prefs.edit().putBoolean("IS_SPENDING_FLAMES_MODE", false).apply()
            }
            block(reason)
            return
        }

        if (dtMs <= 0L) return

        // allowed: either card unlocked (3 h cap) or spending flames
        if (trackBingeLimit(dtMs)) return

        if (prefs.getBoolean("IS_APP_ACTIVE", false) && prefs.getBoolean("IS_SPENDING_FLAMES_MODE", false)) {
            spendMsAccum += dtMs
            while (spendMsAccum >= FLAME_EVERY_MS) {
                spendMsAccum -= FLAME_EVERY_MS
                gamificationManager.spendFlames(1L)
            }
            if (gamificationManager.getSpendableFlames() <= 0L) {
                prefs.edit().putBoolean("IS_SPENDING_FLAMES_MODE", false).apply()
                spendMsAccum = 0L
                block("🔥 نفد رصيد الشعلات! تم إعادة قفل السوشيال")
            }
        }
    }

    /** Adds time to today's social counter. Returns true if the 3-hour daily cap was hit (and locks until 06:00). */
    private fun trackBingeLimit(dtMs: Long): Boolean {
        bingeMsAccum += dtMs
        if (bingeMsAccum < 1000L) return false
        val addSeconds = bingeMsAccum / 1000L
        bingeMsAccum %= 1000L

        val today = socialDayKey()
        val savedDate = prefs.getString("BINGE_DATE", "")
        val used = (if (savedDate == today) prefs.getLong("BINGE_SOCIAL_SECONDS", 0L) else 0L) + addSeconds

        if (used >= BINGE_LIMIT_SECONDS) {
            prefs.edit()
                .putString("BINGE_DATE", today)
                .putLong("BINGE_SOCIAL_SECONDS", BINGE_LIMIT_SECONDS)   // stays at the limit so the UI shows 180/180
                .putLong("BINGE_LOCK_END_TIME", nextSocialDayStartMillis()) // locked until 06:00, not just 1 hour
                .putBoolean("IS_SPENDING_FLAMES_MODE", false)
                .apply()
            block("⛔ وصلت حد 3 ساعات سوشيال النهاردة! السوشيال مقفول لحد 6 الصبح")
            return true
        }
        prefs.edit()
            .putString("BINGE_DATE", today)
            .putLong("BINGE_SOCIAL_SECONDS", used)
            .apply()
        return false
    }

    // =====================================================================
    // Blocking action
    // =====================================================================

    private fun block(msg: String) {
        showThrottledToast(msg)
        goHome()
        retryCount = 0
        scheduleRetry()
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun goHome() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHomeTime < 250L) return
        lastHomeTime = now
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun showThrottledToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 3000L) {
            lastToastTime = now
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
