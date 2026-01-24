package ani.dantotsu.notifications.firebase

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.unread.UnreadChapterNotificationTask
import ani.dantotsu.notifications.subscription.SubscriptionNotificationTask
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging service for background notifications.
 * This service handles push notifications and background tasks to ensure
 * notifications work even when the app is not running.
 */
class DantotsuFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Logger.log("FCM: Message received from: ${remoteMessage.from}")

        // Check if message contains a data payload
        if (remoteMessage.data.isNotEmpty()) {
            Logger.log("FCM: Message data payload: ${remoteMessage.data}")

            val taskType = remoteMessage.data["task_type"]
            when (taskType) {
                "unread_chapters" -> {
                    handleUnreadChaptersCheck()
                }
                "subscriptions" -> {
                    handleSubscriptionsCheck()
                }
                "schedule_all" -> {
                    handleScheduleAll()
                }
                else -> {
                    Logger.log("FCM: Unknown task type: $taskType")
                }
            }
        }

        // Check if message contains a notification payload
        remoteMessage.notification?.let {
            Logger.log("FCM: Message Notification Body: ${it.body}")
            // The notification is automatically displayed by the system
            // if the app is in the background
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Logger.log("FCM: Refreshed token: $token")

        // Save the token to preferences
        PrefManager.init(applicationContext)
        PrefManager.setVal(PrefName.FirebaseToken, token)

        // Send token to your server if you have one
        sendRegistrationToServer(token)
    }

    private fun handleUnreadChaptersCheck() {
        Logger.log("FCM: Executing unread chapters check")
        serviceScope.launch {
            try {
                UnreadChapterNotificationTask().execute(applicationContext)
            } catch (e: Exception) {
                Logger.log("FCM: Error executing unread chapters check: ${e.message}")
            }
        }
    }

    private fun handleSubscriptionsCheck() {
        Logger.log("FCM: Executing subscriptions check")
        serviceScope.launch {
            try {
                SubscriptionNotificationTask().execute(applicationContext)
            } catch (e: Exception) {
                Logger.log("FCM: Error executing subscriptions check: ${e.message}")
            }
        }
    }

    private fun handleScheduleAll() {
        Logger.log("FCM: Scheduling all tasks")
        serviceScope.launch {
            try {
                PrefManager.init(applicationContext)
                val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
                TaskScheduler.create(applicationContext, useAlarmManager).scheduleAllTasks(applicationContext)
            } catch (e: Exception) {
                Logger.log("FCM: Error scheduling tasks: ${e.message}")
            }
        }
    }

    private fun sendRegistrationToServer(token: String) {
        // TODO: Implement server communication if needed
        // This would be used to send the token to your backend server
        // for targeted push notifications
        Logger.log("FCM: Token saved locally: $token")
    }

    companion object {
        /**
         * Get the current FCM token
         */
        fun getCurrentToken(context: Context, onTokenReceived: (String?) -> Unit) {
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val token = task.result
                            Logger.log("FCM: Current token: $token")
                            onTokenReceived(token)
                        } else {
                            Logger.log("FCM: Failed to get token: ${task.exception}")
                            onTokenReceived(null)
                        }
                    }
            } catch (e: Exception) {
                Logger.log("FCM: Error getting token: ${e.message}")
                onTokenReceived(null)
            }
        }

        /**
         * Subscribe to a topic for receiving notifications
         */
        fun subscribeToTopic(topic: String) {
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic(topic)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Logger.log("FCM: Subscribed to topic: $topic")
                        } else {
                            Logger.log("FCM: Failed to subscribe to topic: ${task.exception}")
                        }
                    }
            } catch (e: Exception) {
                Logger.log("FCM: Error subscribing to topic: ${e.message}")
            }
        }

        /**
         * Unsubscribe from a topic
         */
        fun unsubscribeFromTopic(topic: String) {
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Logger.log("FCM: Unsubscribed from topic: $topic")
                        } else {
                            Logger.log("FCM: Failed to unsubscribe from topic: ${task.exception}")
                        }
                    }
            } catch (e: Exception) {
                Logger.log("FCM: Error unsubscribing from topic: ${e.message}")
            }
        }
    }
}
