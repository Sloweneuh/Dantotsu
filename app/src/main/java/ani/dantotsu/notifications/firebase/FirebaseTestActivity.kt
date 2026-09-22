package ani.dantotsu.notifications.firebase

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ani.dantotsu.R
import ani.dantotsu.initActivity
import ani.dantotsu.notifications.anilist.AnilistNotificationTask
import ani.dantotsu.notifications.subscription.SubscriptionNotificationTask
import ani.dantotsu.notifications.unread.MuUnreadNotificationTask
import ani.dantotsu.notifications.unread.UnreadChapterNotificationTask
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Test activity for Firebase notifications
 * Allows manual triggering and status checking
 */
class FirebaseTestActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvStatus: TextView
    private lateinit var btnGetToken: Button
    private lateinit var btnSubscribeTopics: Button
    private lateinit var btnTriggerUnread: Button
    private lateinit var btnTriggerSubscriptions: Button
    private lateinit var btnForceSchedulerTick: Button
    private lateinit var btnRefreshStatus: Button
    private lateinit var btnWriteTestLog: Button
    private lateinit var btnViewLogs: Button
    private lateinit var btnTestAnilistImage: Button
    private lateinit var btnTestAnilistActivityLike: Button
    private lateinit var btnTestUnreadChapterImage: Button
    private lateinit var btnTestUnreadEpisodeImage: Button
    private lateinit var btnTestMuImage: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only allow access in debug builds
        if (!ani.dantotsu.BuildConfig.DEBUG) {
            finish()
            return
        }

        ThemeManager(this).applyTheme()
        initActivity(this)

        setContentView(R.layout.activity_firebase_test)

        // Self-contained rather than reusing the app-wide statusBarHeight/navBarHeight globals:
        // those are only populated once some other activity (normally MainActivity) has computed
        // them, which hasn't necessarily happened when this screen is opened directly. Also asks
        // for the display cutout inset (camera punch-hole), which those globals don't cover.
        val root = findViewById<android.view.View>(R.id.firebaseTestRoot)
        val basePaddingLeft = root.paddingLeft
        val basePaddingTop = root.paddingTop
        val basePaddingRight = root.paddingRight
        val basePaddingBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bars.bottom
            )
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        btnGetToken = findViewById(R.id.btnGetToken)
        btnSubscribeTopics = findViewById(R.id.btnSubscribeTopics)
        btnTriggerUnread = findViewById(R.id.btnTriggerUnread)
        btnTriggerSubscriptions = findViewById(R.id.btnTriggerSubscriptions)
        btnForceSchedulerTick = findViewById(R.id.btnForceSchedulerTick)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)
        btnWriteTestLog = findViewById(R.id.btnWriteTestLog)
        btnViewLogs = findViewById(R.id.btnViewLogs)
        btnTestAnilistImage = findViewById(R.id.btnTestAnilistImage)
        btnTestAnilistActivityLike = findViewById(R.id.btnTestAnilistActivityLike)
        btnTestUnreadChapterImage = findViewById(R.id.btnTestUnreadChapterImage)
        btnTestUnreadEpisodeImage = findViewById(R.id.btnTestUnreadEpisodeImage)
        btnTestMuImage = findViewById(R.id.btnTestMuImage)

        setupUI()
        updateStatus()
    }

    private fun setupUI() {
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        btnGetToken.setOnClickListener {
            getToken()
        }

        btnSubscribeTopics.setOnClickListener {
            subscribeToTopics()
        }

        btnTriggerUnread.setOnClickListener {
            triggerUnreadCheck()
        }

        btnTriggerSubscriptions.setOnClickListener {
            triggerSubscriptionCheck()
        }

        btnForceSchedulerTick.setOnClickListener {
            forceSchedulerTick()
        }

        btnRefreshStatus.setOnClickListener {
            updateStatus()
        }

        btnWriteTestLog.setOnClickListener {
            writeTestLog()
        }

        btnViewLogs.setOnClickListener {
            viewFullLogs()
        }

        btnTestAnilistImage.setOnClickListener {
            sendTestNotification("AniList") { AnilistNotificationTask().sendTestNotification(this) }
        }

        btnTestAnilistActivityLike.setOnClickListener {
            sendTestNotification("\"liked your activity\"") {
                AnilistNotificationTask().sendTestActivityLikeNotification(this)
            }
        }

        btnTestUnreadChapterImage.setOnClickListener {
            sendTestNotification("unread chapter") {
                UnreadChapterNotificationTask().sendTestNotification(this, isAnime = false)
            }
        }

        btnTestUnreadEpisodeImage.setOnClickListener {
            sendTestNotification("unread episode") {
                UnreadChapterNotificationTask().sendTestNotification(this, isAnime = true)
            }
        }

        btnTestMuImage.setOnClickListener {
            sendTestNotification("MangaUpdates") { MuUnreadNotificationTask().sendTestNotification(this) }
        }
    }

    /**
     * Posts a synthetic notification carrying a real cover image, bypassing the account state
     * (actual unread AniList notifications, actual unread chapters) that the real tasks need and
     * that can't reliably be produced on demand — this exercises the same large-icon code with a
     * fixed test payload instead.
     */
    private fun sendTestNotification(label: String, post: suspend Context.() -> Unit) {
        tvStatus.append("\n\n🖼️ Sending test $label notification...")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { applicationContext.post() }
                tvStatus.append("\n✅ Test $label notification sent — check the notification shade")
            } catch (e: Exception) {
                tvStatus.append("\n❌ Error: ${e.message}")
                toast("Error: ${e.message}")
            }
        }
    }

    private fun getToken() {
        tvStatus.append("\n\nFetching FCM token...")
        DantotsuFirebaseMessagingService.getCurrentToken(this) { token ->
            runOnUiThread {
                if (token != null) {
                    tvStatus.append("\n✅ Token: ${token.take(20)}...")
                    PrefManager.setVal(PrefName.FirebaseToken, token)
                    toast("Token retrieved successfully")
                } else {
                    tvStatus.append("\n❌ Failed to get token")
                    toast("Failed to get token")
                }
            }
        }
    }

    private fun subscribeToTopics() {
        tvStatus.append("\n\nSubscribing to topics...")

        DantotsuFirebaseMessagingService.subscribeToTopic("unread_chapters_check")
        tvStatus.append("\n📢 Subscribed to: unread_chapters_check")

        DantotsuFirebaseMessagingService.subscribeToTopic("subscriptions_check")
        tvStatus.append("\n📢 Subscribed to: subscriptions_check")

        toast("Subscription requests sent")
    }

    private fun triggerUnreadCheck() {
        tvStatus.append("\n\n🔄 Triggering unread chapters check...")
        toast("Starting unread chapters check...")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Update timestamp BEFORE running to prevent immediate re-trigger
                    val now = System.currentTimeMillis()
                    PrefManager.setVal(PrefName.LastUnreadChapterCheck, now)

                    UnreadChapterNotificationTask().execute(applicationContext)
                }
                tvStatus.append("\n${if (result) "✅" else "❌"} Unread check completed: $result")

                // Log the last check time
                val lastCheck = PrefManager.getVal<Long>(PrefName.LastUnreadChapterCheck)
                tvStatus.append("\nLast check: ${formatTime(lastCheck)}")
                tvStatus.append("\n⏰ Next check in 5 minutes")

                toast(if (result) "Check completed successfully" else "Check failed")
            } catch (e: Exception) {
                tvStatus.append("\n❌ Error: ${e.message}")
                toast("Error: ${e.message}")
            }
        }
    }

    private fun triggerSubscriptionCheck() {
        tvStatus.append("\n\n🔄 Triggering subscription check...")
        toast("Starting subscription check...")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SubscriptionNotificationTask().execute(applicationContext)
                }
                tvStatus.append("\n${if (result) "✅" else "❌"} Subscription check completed: $result")
                toast(if (result) "Check completed successfully" else "Check failed")
            } catch (e: Exception) {
                tvStatus.append("\n❌ Error: ${e.message}")
                toast("Error: ${e.message}")
            }
        }
    }

    private fun forceSchedulerTick() {
        tvStatus.append("\n\n⚡ Forcing scheduler tick (simulates 5min interval)...")
        toast("Simulating scheduler tick...")

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PrefManager.init(applicationContext)
                    val now = System.currentTimeMillis()

                    // Check if unread check should trigger
                    val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
                    if (unreadInterval > 0) {
                        val unreadLastCheck = PrefManager.getVal<Long>(PrefName.LastUnreadChapterCheck)
                        val elapsed = now - unreadLastCheck
                        val required = unreadInterval * 60 * 1000

                        if (elapsed >= required) {
                            tvStatus.append("\n✅ Unread check triggered (${elapsed/60000}min elapsed >= ${unreadInterval}min required)")
                            UnreadChapterNotificationTask().execute(applicationContext)
                            PrefManager.setVal(PrefName.LastUnreadChapterCheck, now)
                        } else {
                            val remaining = (required - elapsed) / 60000
                            tvStatus.append("\n⏱️ Unread check: ${remaining}min remaining (need ${unreadInterval}min)")
                        }
                    } else {
                        tvStatus.append("\n⚠️ Unread notifications disabled (interval: 0)")
                    }

                    // Update Firebase timestamp
                    PrefManager.setVal(PrefName.LastFirebaseBackgroundCheck, now)
                }
                toast("Scheduler tick completed")
            } catch (e: Exception) {
                tvStatus.append("\n❌ Error: ${e.message}")
                toast("Error: ${e.message}")
            }
        }
    }

    private fun updateStatus() {
        val sb = StringBuilder()
        sb.append("🔥 Firebase Status\n")
        sb.append("=".repeat(40))

        // Build flavor
        sb.append("\n\n📦 Build: ${ani.dantotsu.BuildConfig.FLAVOR}")

        // Token
        val token = PrefManager.getVal<String>(PrefName.FirebaseToken)
        sb.append("\n\n🔑 FCM Token: ")
        if (token.isEmpty()) {
            sb.append("Not set")
        } else {
            sb.append("${token.take(20)}...")
        }

        // Scheduler status
        sb.append("\n\n⚙️ Active Schedulers:")
        val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
        sb.append("\n• WorkManager: ${if (!useAlarmManager) "✅ Active" else "❌ Disabled"}")
        sb.append("\n• AlarmManager: ${if (useAlarmManager) "✅ Active" else "❌ Disabled"}")
        sb.append("\n• Firebase Fallback: ✅ Active (5min)")

        // Last checks
        sb.append("\n\n⏰ Last Checks:")
        val lastFirebase = PrefManager.getVal<Long>(PrefName.LastFirebaseBackgroundCheck)
        sb.append("\n• Firebase: ${formatTime(lastFirebase)}")

        val lastUnread = PrefManager.getVal<Long>(PrefName.LastUnreadChapterCheck)
        sb.append("\n• Unread: ${formatTime(lastUnread)}")

        val lastSub = PrefManager.getVal<Long>(PrefName.LastSubscriptionCheck)
        sb.append("\n• Subscriptions: ${formatTime(lastSub)}")

        // Next check estimation
        sb.append("\n\n⏱️ Configured Intervals:")
        val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
        sb.append("\n• Unread: ${unreadInterval}min")
        if (unreadInterval > 0 && lastUnread > 0) {
            val nextCheck = lastUnread + (unreadInterval * 60 * 1000)
            val remaining = nextCheck - System.currentTimeMillis()
            if (remaining > 0) {
                sb.append(" (next: ${TimeUnit.MILLISECONDS.toMinutes(remaining)}min)")
            } else {
                sb.append(" (⚠️ OVERDUE)")
            }
        }

        val subInterval = PrefManager.getVal<Long>(PrefName.SubscriptionNotificationIntervalMinutes)
        sb.append("\n• Subscriptions: ${subInterval}min")

        // Show recent logs from SharedPreferences
        sb.append("\n\n📝 Recent Background Activity:")
        val prefs = getSharedPreferences("firebase_logs", MODE_PRIVATE)
        val lastLogTime = prefs.getLong("last_log_time", 0)
        if (lastLogTime > 0) {
            sb.append("\n• Last activity: ${formatTime(lastLogTime)}")
            val logs = prefs.getString("last_logs", "") ?: ""
            if (logs.isNotEmpty()) {
                val recentLogs = logs.lines().takeLast(5).joinToString("\n")
                sb.append("\n\n${recentLogs}")
            }
        } else {
            sb.append("\n• No background activity yet")
            sb.append("\n• Scheduler checks every 5min")
            sb.append("\n• First check will be in ~5min")
            sb.append("\n\n💡 Note: Timestamps are initialized")
            sb.append("\n  on first run to prevent immediate")
            sb.append("\n  triggers. Manual triggers reset the")
            sb.append("\n  timer to prevent back-to-back runs.")
        }

        // Log file locations
        sb.append("\n\n📁 Log File Locations:")
        sb.append("\n• Internal: ${filesDir}/firebase_background.log")
        sb.append("\n• Test: ${filesDir}/firebase_test.log")

        // Instructions
        sb.append("\n\n💡 To view logs via ADB:")
        sb.append("\nadb shell cat ${filesDir}/firebase_background.log")

        tvStatus.text = sb.toString()
    }

    private fun writeTestLog() {
        try {
            val logFile = java.io.File(filesDir, "firebase_test.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logEntry = "[$timestamp] Manual test triggered\n"

            logFile.appendText(logEntry)

            // Also update SharedPreferences
            val prefs = getSharedPreferences("firebase_logs", MODE_PRIVATE)
            val existingLogs = prefs.getString("last_logs", "") ?: ""
            val newLogs = (existingLogs + logEntry).takeLast(10000)
            prefs.edit().putString("last_logs", newLogs).apply()
            prefs.edit().putLong("last_log_time", System.currentTimeMillis()).apply()

            tvStatus.append("\n\n✅ Log written to: ${logFile.absolutePath}")
            toast("Log written successfully")

            Logger.log("Firebase Test: Manual test at $timestamp")
        } catch (e: Exception) {
            tvStatus.append("\n\n❌ Failed to write log: ${e.message}")
            toast("Failed to write log")
        }
    }

    private fun viewFullLogs() {
        val sb = StringBuilder()
        sb.append("📝 Background Activity Logs\n")
        sb.append("=".repeat(40))
        sb.append("\n\n")

        // Try to read from file
        try {
            val logFile = java.io.File(filesDir, "firebase_background.log")
            if (logFile.exists()) {
                val logs = logFile.readText()
                if (logs.isNotEmpty()) {
                    sb.append("From file (${logFile.absolutePath}):\n\n")
                    sb.append(logs.lines().takeLast(50).joinToString("\n"))
                } else {
                    sb.append("Log file is empty.\n")
                }
            } else {
                sb.append("Log file doesn't exist yet.\n")
            }
        } catch (e: Exception) {
            sb.append("Error reading file: ${e.message}\n")
        }

        // Also show SharedPreferences logs
        sb.append("\n\n" + "=".repeat(40))
        sb.append("\nFrom SharedPreferences:\n\n")
        val prefs = getSharedPreferences("firebase_logs", MODE_PRIVATE)
        val logs = prefs.getString("last_logs", "")
        if (logs.isNullOrEmpty()) {
            sb.append("No logs in SharedPreferences yet.\n")
            sb.append("\nThis means background tasks haven't run yet.\n")
            sb.append("Wait for the configured interval or trigger manually.")
        } else {
            sb.append(logs)
        }

        tvStatus.text = sb.toString()
        toast("Showing full logs")
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return "Never"
        val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US)
        return sdf.format(Date(timestamp))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager(this).applyTheme()
    }
}

