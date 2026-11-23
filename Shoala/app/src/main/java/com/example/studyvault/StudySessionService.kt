package com.example.studyvault

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs

class StudySessionService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "ShoalaSessionChannel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TARGET_SECONDS = "TARGET_SECONDS"
        const val GRACE_SECONDS = 60   // مهلة إرجاع الهاتف: دقيقة واحدة

        @Volatile var isStudying = false
        @Volatile var isPhoneLifted = false
        @Volatile var isInCall = false
        @Volatile var resetSensorAnchor = false
        @Volatile var alarmArmed = false
        @Volatile var isAlarmRinging = false
        @Volatile var alarmCountdown = -1
        @Volatile var studySeconds = 0L
        @Volatile var graceSecondsLeft = GRACE_SECONDS
        @Volatile var targetSeconds = 0L          // 0 = count-up, >0 = countdown length in seconds
        @Volatile var sessionCompleted = false    // true when a countdown reached zero
        @Volatile var statusMessage = "اختر نوع المؤقت ثم ابدأ التركيز"
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var statsManager: StatsManager
    private lateinit var gamificationManager: GamificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyPhoneListener: PhoneStateListener? = null

    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
    private var initialPositionSet = false
    private var stillStartTime = 0L

    private var liftedSeconds = 0
    private var alarmPlayer: MediaPlayer? = null

    private val handler = Handler(Looper.getMainLooper())

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isStudying) return

            if (!isInCall) {
                if (!isPhoneLifted) {
                    alarmArmed = true      // phone is settled on the desk: removal alarm is now armed
                    liftedSeconds = 0
                    stopAlarm()
                    studySeconds++
                    graceSecondsLeft = GRACE_SECONDS
                    statsManager.addStudySeconds(1L)

                    if (studySeconds % 60L == 0L) {
                        val awards = gamificationManager.addOneMinuteFlames(
                            sessionMinutes = studySeconds / 60L,
                            todayTotalMinutes = statsManager.getTodayMinutes()
                        )
                        awards.forEach { award ->
                            Toast.makeText(this@StudySessionService, "${award.title}\n${award.message}", Toast.LENGTH_LONG).show()
                        }
                    }

                    if (targetSeconds > 0L && studySeconds >= targetSeconds) {
                        completeCountdown()
                        return
                    }

                    val mins = studySeconds / 60L
                    val secs = studySeconds % 60L
                    statusMessage = "🔥 وضع المذاكرة نشط - الهاتف ثابت على المكتب"
                    updateNotification("جاري المذاكرة: ${String.format(Locale.US, "%02d:%02d", mins, secs)}")
                } else {
                    graceSecondsLeft--
                    statusMessage = "⚠️ ضع الهاتف ثابتاً على المكتب خلال: $graceSecondsLeft ثانية!"
                    updateNotification("⚠️ أعد الهاتف للمكتب خلال $graceSecondsLeft ثانية")
                    handleRemovalAlarm()

                    if (graceSecondsLeft <= 0) {
                        triggerOneHourPenalty()
                        return
                    }
                }
            } else {
                stopAlarm()
                statusMessage = "📞 مكالمة جارية - تم إيقاف المؤقت مؤقتاً"
                updateNotification("مكالمة جارية - المؤقت متوقف مؤقتاً")
            }

            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        statsManager = StatsManager(this)
        gamificationManager = GamificationManager(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Shoala::SessionWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isStudying) {
            getSharedPreferences("StudyVaultPrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("IS_SPENDING_FLAMES_MODE", false).apply()

            isStudying = true
            isPhoneLifted = true
            isInCall = false
            resetSensorAnchor = true
            studySeconds = 0L
            targetSeconds = intent?.getLongExtra(EXTRA_TARGET_SECONDS, 0L) ?: 0L
            sessionCompleted = false
            alarmArmed = false
            liftedSeconds = 0
            alarmCountdown = -1
            isAlarmRinging = false
            graceSecondsLeft = GRACE_SECONDS
            initialPositionSet = false
            stillStartTime = 0L
            statusMessage = "بدأت الجلسة! ضع الهاتف ثابتاً على مكتبك خلال $GRACE_SECONDS ثانية"

            val notification = buildNotification("بدأت الجلسة - ضع الهاتف على المكتب")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            wakeLock?.acquire(12 * 60 * 60 * 1000L)
            accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            registerCallStateListener()
            handler.post(timerRunnable)
        }
        return START_NOT_STICKY
    }

    private fun alarmEnabled(): Boolean =
        getSharedPreferences("StudyVaultPrefs", Context.MODE_PRIVATE).getBoolean("ALARM_ENABLED", true)

    // Called once per second while the phone is off the desk during an active session
    private fun handleRemovalAlarm() {
        if (!alarmArmed || !alarmEnabled()) return
        liftedSeconds++
        val delay = 5
        if (liftedSeconds < delay) {
            alarmCountdown = delay - liftedSeconds
            statusMessage = "🚨 الهاتف اتشال من مكانه! الإنذار بعد $alarmCountdown ثانية"
            updateNotification("🚨 رجّع الهاتف خلال $alarmCountdown ثانية")
        } else {
            alarmCountdown = 0
            startAlarm()
            statusMessage = "🚨 إنذار! رجّع الهاتف مكانه (الجلسة تنتهي بعد $graceSecondsLeft ثانية)"
            updateNotification("🚨 إنذار! رجّع الهاتف مكانه")
        }
    }

    private fun startAlarm() {
        if (isAlarmRinging) return
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(this, uri)
            player.isLooping = true
            player.prepare()
            player.start()
            alarmPlayer = player
            isAlarmRinging = true
        } catch (e: Exception) {
            isAlarmRinging = false
        }
    }

    private fun stopAlarm() {
        alarmCountdown = -1
        if (alarmPlayer == null && !isAlarmRinging) return
        try { alarmPlayer?.stop() } catch (e: Exception) { }
        try { alarmPlayer?.release() } catch (e: Exception) { }
        alarmPlayer = null
        isAlarmRinging = false
    }

    private fun completeCountdown() {
        val mins = studySeconds / 60L
        sessionCompleted = true
        statusMessage = "🎉 انتهى الوقت! تم حفظ $mins دقيقة وشعلاتك ✅"
        Toast.makeText(this, "🎉 انتهت الجلسة! أحسنت 🔥", Toast.LENGTH_LONG).show()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        } catch (e: Exception) { }
        stopSelf()
    }

    private fun triggerOneHourPenalty() {
        val oneHourMillis = 60L * 60L * 1000L
        val prefs = getSharedPreferences("StudyVaultPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("PENALTY_END_TIME", System.currentTimeMillis() + oneHourMillis)
            .putBoolean("IS_SPENDING_FLAMES_MODE", false)
            .apply()
        statusMessage = "⛔ انتهت مهلة الدقيقة! تم قفل فيسبوك وإنستغرام ويوتيوب لمدة ساعة."
        Toast.makeText(this, "تم تطبيق عقوبة حظر السوشيال ميديا لمدة ساعة!", Toast.LENGTH_LONG).show()
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isStudying || isInCall || event == null) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!initialPositionSet || resetSensorAnchor) {
            lastX = x; lastY = y; lastZ = z
            initialPositionSet = true
            resetSensorAnchor = false
            stillStartTime = System.currentTimeMillis()
            return
        }

        val delta = abs(x - lastX) + abs(y - lastY) + abs(z - lastZ)

        if (delta > 1.2f) {
            lastX = x; lastY = y; lastZ = z
            stillStartTime = 0L
            if (!isPhoneLifted) {
                isPhoneLifted = true
                graceSecondsLeft = GRACE_SECONDS
                Toast.makeText(this, "تم تحريك الهاتف! أمامك دقيقة لإعادته", Toast.LENGTH_SHORT).show()
            }
        } else if (isPhoneLifted) {
            val now = System.currentTimeMillis()
            if (stillStartTime == 0L) {
                stillStartTime = now
            } else if (now - stillStartTime >= 3000L) {
                isPhoneLifted = false
                graceSecondsLeft = GRACE_SECONDS
                stillStartTime = 0L
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun registerCallStateListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            telephonyCallback = callback
            telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            legacyPhoneListener = listener
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                isInCall = true
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isInCall) {
                    isInCall = false
                    isPhoneLifted = true
                    alarmArmed = false
                    liftedSeconds = 0
                    graceSecondsLeft = GRACE_SECONDS
                    resetSensorAnchor = true
                    stillStartTime = 0L
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "جلسة المذاكرة - شعلة", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("شعلة 🔥")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shoala_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
        alarmArmed = false
        isStudying = false
        isPhoneLifted = false
        isInCall = false
        resetSensorAnchor = false
        handler.removeCallbacks(timerRunnable)
        sensorManager.unregisterListener(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                telephonyManager?.unregisterTelephonyCallback(it)
            }
        } else {
            @Suppress("DEPRECATION")
            legacyPhoneListener?.let {
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
