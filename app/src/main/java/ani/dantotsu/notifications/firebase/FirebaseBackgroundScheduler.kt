package ani.dantotsu.notifications.firebase

import android.content.Context
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Firebase-based background scheduler for periodic notification checks.
 * Uses FCM topics with time-based triggers to ensure notifications
 * work even when the app is completely closed.
 */
object FirebaseBackgroundScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val UNREAD_CHAPTERS_TOPIC = "unread_chapters_check"
    private const val SUBSCRIPTIONS_TOPIC = "subscriptions_check"

    /**
     * Initialize Firebase and subscribe to relevant topics based on user preferences
     */
    fun initialize(context: Context) {
        PrefManager.init(context)

        // Get FCM token and log it
        DantotsuFirebaseMessagingService.getCurrentToken(context) { token ->
            if (token != null) {
                Logger.log("Firebase: Initialized with token")

                // Subscribe to topics based on user preferences
                subscribeToTopicsBasedOnPreferences()
            } else {
                Logger.log("Firebase: Failed to get token during initialization")
            }
        }

        // Start a background check scheduler as a fallback
        startFallbackScheduler(context)
    }

    /**
     * Subscribe to notification topics based on user preferences
     */
    private fun subscribeToTopicsBasedOnPreferences() {
        try {
            // Check if unread chapter notifications are enabled
            val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
            if (unreadInterval > 0) {
                DantotsuFirebaseMessagingService.subscribeToTopic(UNREAD_CHAPTERS_TOPIC)
                Logger.log("Firebase: Subscribed to unread chapters topic")
            } else {
                DantotsuFirebaseMessagingService.unsubscribeFromTopic(UNREAD_CHAPTERS_TOPIC)
                Logger.log("Firebase: Unsubscribed from unread chapters topic")
            }

            // Check if subscription notifications are enabled
            val subscriptionInterval = PrefManager.getVal<Int>(PrefName.SubscriptionNotificationInterval)
            if (subscriptionInterval > 0) {
                DantotsuFirebaseMessagingService.subscribeToTopic(SUBSCRIPTIONS_TOPIC)
                Logger.log("Firebase: Subscribed to subscriptions topic")
            } else {
                DantotsuFirebaseMessagingService.unsubscribeFromTopic(SUBSCRIPTIONS_TOPIC)
                Logger.log("Firebase: Unsubscribed from subscriptions topic")
            }
        } catch (e: Exception) {
            Logger.log("Firebase: Error subscribing to topics: ${e.message}")
        }
    }

    /**
     * Update subscriptions when settings change
     */
    fun updateSubscriptions() {
        subscribeToTopicsBasedOnPreferences()
    }

    /**
     * Fallback scheduler that runs periodic checks even without server-side FCM
     * This uses local scheduling as a backup mechanism
     */
    private fun startFallbackScheduler(context: Context) {
        scope.launch {
            while (isActive) {
                try {
                    // Check every hour if we should run notification checks
                    delay(TimeUnit.HOURS.toMillis(1))

                    PrefManager.init(context)
                    val lastCheck = PrefManager.getVal<Long>(PrefName.LastFirebaseBackgroundCheck)
                    val now = System.currentTimeMillis()

                    // Check unread chapters
                    val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
                    if (unreadInterval > 0) {
                        val unreadLastCheck = PrefManager.getVal<Long>(PrefName.LastUnreadChapterCheck)
                        if (now - unreadLastCheck >= TimeUnit.MINUTES.toMillis(unreadInterval)) {
                            Logger.log("Firebase Fallback: Triggering unread chapters check")
                            triggerUnreadChaptersCheck(context)
                            PrefManager.setVal(PrefName.LastUnreadChapterCheck, now)
                        }
                    }

                    // Check subscriptions
                    val subscriptionInterval = PrefManager.getVal<Long>(PrefName.SubscriptionNotificationIntervalMinutes)
                    if (subscriptionInterval > 0) {
                        val subscriptionLastCheck = PrefManager.getVal<Long>(PrefName.LastSubscriptionCheck)
                        val intervalMillis = TimeUnit.MINUTES.toMillis(subscriptionInterval)
                        if (now - subscriptionLastCheck >= intervalMillis) {
                            Logger.log("Firebase Fallback: Triggering subscription check")
                            triggerSubscriptionCheck(context)
                            PrefManager.setVal(PrefName.LastSubscriptionCheck, now)
                        }
                    }

                    PrefManager.setVal(PrefName.LastFirebaseBackgroundCheck, now)
                } catch (e: Exception) {
                    Logger.log("Firebase Fallback: Error in scheduler: ${e.message}")
                }
            }
        }
    }

    /**
     * Manually trigger an unread chapters check
     */
    fun triggerUnreadChaptersCheck(context: Context) {
        scope.launch {
            try {
                ani.dantotsu.notifications.unread.UnreadChapterNotificationTask().execute(context)
            } catch (e: Exception) {
                Logger.log("Firebase: Error triggering unread chapters check: ${e.message}")
            }
        }
    }

    /**
     * Manually trigger a subscription check
     */
    fun triggerSubscriptionCheck(context: Context) {
        scope.launch {
            try {
                ani.dantotsu.notifications.subscription.SubscriptionNotificationTask().execute(context)
            } catch (e: Exception) {
                Logger.log("Firebase: Error triggering subscription check: ${e.message}")
            }
        }
    }
}
