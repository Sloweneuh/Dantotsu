package ani.dantotsu.settings

import android.content.Context
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.firebase.FirebaseBackgroundScheduler
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.notifications.unread.UnreadChapterStore
import ani.dantotsu.openSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import java.util.Locale

/**
 * The rows behind each notification source group on [SettingsNotificationActivity].
 *
 * These were five separate Activities. Their logic is unchanged — every preference, dialog and
 * interval preset is the one that screen already used — but each now builds a `List<Settings>` that
 * the merged screen renders inside a collapsible card, so the whole notification schedule is
 * visible at once instead of behind five round trips.
 *
 * Intervals stay deliberately per-source. Each hits a different backend at a very different cost,
 * and the presets encode that: AniList allows 30 minutes, comments floor at 8 hours, subscriptions
 * fan out one source request per subscribed media. A single shared value cannot serve all three.
 */

/** Section keys, also the search anchors — see [SettingsSection.key]. */
object NotificationSection {
    const val SUBSCRIPTIONS = "notif_subscriptions"
    const val MALSYNC = "notif_malsync"
    const val ANILIST = "notif_anilist"
    const val COMMENTS = "notif_comments"
    const val MANGAUPDATES = "notif_mangaupdates"
}

/**
 * Opens the notification settings with [section] expanded, when one is named.
 *
 * The five per-source screens each used to be their own destination, so anything that wanted to
 * send a user "to the AniList notification settings" started that Activity and landed them on the
 * settings themselves. There is one screen now and the group takes the place of the destination —
 * so it opens, rather than leaving a collapsed card the user still has to tap. That is the whole
 * difference between this and a search hit on the same group, which only points at it.
 */
fun notificationSettingsIntent(context: Context, section: String? = null): android.content.Intent =
    android.content.Intent(context, SettingsNotificationActivity::class.java).apply {
        if (section != null) {
            putExtra(SettingsRouter.EXTRA_ANCHOR_SECTION, section)
            putExtra(SettingsRouter.EXTRA_ANCHOR_SECTION_EXPANDED, true)
        }
    }

/** "2 hrs", "90 mins", "1 hrs 30 mins" — the format every one of these screens already used. */
internal fun formatIntervalMinutes(minutes: Long): String {
    val h = (minutes / 60).toInt()
    val m = (minutes % 60).toInt()
    return "${if (h > 0) "$h hrs " else ""}${if (m > 0) "$m mins" else ""}".trim()
}

private fun Context.intervalLabel(minutes: Long): String =
    if (minutes > 0L) formatIntervalMinutes(minutes) else getString(R.string.do_not_update)

private fun AppCompatActivity.rescheduleAll() {
    TaskScheduler.create(this, PrefManager.getVal(PrefName.UseAlarmManager)).scheduleAllTasks(this)
}

/** Google Play builds mirror the enabled/disabled state onto the FCM topics. */
private fun updateFirebaseTopics() {
    if (BuildConfig.FLAVOR == "google") {
        try {
            FirebaseBackgroundScheduler.updateSubscriptions()
        } catch (e: Exception) {
            Logger.log("Failed to update Firebase subscriptions: ${e.message}")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Summaries — what each card reads as while collapsed.
// ---------------------------------------------------------------------------------------------

fun AppCompatActivity.subscriptionsSummary(): String =
    intervalLabel(PrefManager.getVal(PrefName.SubscriptionNotificationIntervalMinutes))

fun AppCompatActivity.malSyncSummary(): String =
    if (!PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled)) getString(R.string.disable_malsync)
    else intervalLabel(PrefManager.getVal(PrefName.UnreadChapterNotificationInterval))

fun AppCompatActivity.anilistSummary(): String =
    intervalLabel(AnilistNotificationWorker.checkIntervals.getOrElse(
        PrefManager.getVal<Int>(PrefName.AnilistNotificationInterval)
    ) { 0L })

fun AppCompatActivity.commentsSummary(): String =
    intervalLabel(CommentNotificationWorker.checkIntervals.getOrElse(
        PrefManager.getVal<Int>(PrefName.CommentNotificationInterval)
    ) { 0L })

fun AppCompatActivity.mangaUpdatesSummary(): String {
    val unread = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
    // Checked inside the MALSync task while that one is running, so its own interval is moot.
    if (unread > 0L) return getString(R.string.mu_notification_interval_linked, formatIntervalMinutes(unread))
    return intervalLabel(PrefManager.getVal(PrefName.MangaUpdatesNotificationInterval))
}

// ---------------------------------------------------------------------------------------------
// Subscriptions
// ---------------------------------------------------------------------------------------------

fun AppCompatActivity.subscriptionRows(onChanged: () -> Unit): List<Settings> {
    val context = this
    val sIntervals = mutableListOf(0L, 60L, 120L, 180L, 360L, 480L, 720L, 1440L)
    val current = PrefManager.getVal<Long>(PrefName.SubscriptionNotificationIntervalMinutes)

    var customIndex = -1
    if (current > 0L && !sIntervals.contains(current)) {
        customIndex = sIntervals.size
        sIntervals.add(current)
    }

    val sItems = sIntervals.mapIndexed { index, it ->
        if (it > 0L) {
            if (index == customIndex) "Custom: ${formatIntervalMinutes(it)}" else formatIntervalMinutes(it)
        } else getString(R.string.do_not_update)
    }.toMutableList()
    sItems.add(getString(R.string.custom))

    val currentIndex = sIntervals.indexOf(current).let { if (it == -1) 5 else it }

    return listOf(
        Settings(
            type = 1,
            name = getString(R.string.subscriptions_checking_time_s, sItems[currentIndex]),
            desc = getString(R.string.subscriptions_info),
            icon = R.drawable.ic_round_notif_schedule_24,
            compact = true,
            anchorKey = "sub_interval",
            onClick = {
                context.customAlertDialog().apply {
                    setTitle(R.string.subscriptions_checking_time)
                    singleChoiceItems(sItems.toTypedArray(), currentIndex) { i ->
                        if (i == sItems.size - 1) {
                            showCustomIntervalDialog(
                                PrefName.SubscriptionNotificationIntervalMinutes, it,
                                R.string.subscriptions_checking_time_s, onChanged
                            )
                        } else {
                            PrefManager.setVal(PrefName.SubscriptionNotificationIntervalMinutes, sIntervals[i])
                            it.settingsTitle.text =
                                getString(R.string.subscriptions_checking_time_s, sItems[i])
                            rescheduleAll()
                            updateFirebaseTopics()
                            onChanged()
                        }
                    }
                    show()
                }
            },
            onLongClick = { rescheduleAll() }
        ),
        Settings(
            type = 2,
            name = getString(R.string.notification_for_checking_subscriptions),
            desc = getString(R.string.notification_for_checking_subscriptions_desc),
            icon = R.drawable.ic_round_notif_progress_24,
            compact = true,
            anchorKey = "sub_checking_notif",
            isChecked = PrefManager.getVal(PrefName.SubscriptionCheckingNotifications),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.SubscriptionCheckingNotifications, isChecked)
            },
            onLongClick = { openSettings(context, null) }
        ),
        Settings(
            type = 1,
            name = getString(R.string.view_subscriptions),
            desc = getString(R.string.view_subscriptions_desc),
            icon = R.drawable.ic_round_subscriptions_24,
            compact = true,
            anchorKey = "sub_view",
            onClick = {
                val subscriptions = SubscriptionHelper.getSubscriptions()
                SubscriptionsBottomDialog.newInstance(subscriptions)
                    .show((context as FragmentActivity).supportFragmentManager, "subscriptions")
            }
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// MALSync (unread chapters / episodes)
// ---------------------------------------------------------------------------------------------

fun AppCompatActivity.malSyncRows(onChanged: () -> Unit): List<Settings> {
    val context = this

    // MALSync's own switch lives on its Accounts card; with the info source off there is nothing
    // here to configure, so the group explains that and offers the way over instead.
    if (!PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled)) {
        return listOf(
            Settings(
                type = 1,
                name = getString(R.string.disable_malsync),
                desc = getString(R.string.disable_malsync_desc),
                icon = R.drawable.ic_malsync,
                compact = true,
                onClick = {
                    startActivity(android.content.Intent(context, SettingsAccountActivity::class.java))
                }
            )
        )
    }

    val uIntervals = mutableListOf(0L, 60L, 120L, 180L, 360L, 720L, 1440L)
    val current = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)

    var customIndex = -1
    if (current > 0L && !uIntervals.contains(current)) {
        customIndex = uIntervals.size
        uIntervals.add(current)
    }

    val uItems = uIntervals.mapIndexed { index, it ->
        if (it > 0L) {
            if (index == customIndex) "Custom: ${formatIntervalMinutes(it)}" else formatIntervalMinutes(it)
        } else getString(R.string.do_not_update)
    }.toMutableList()
    uItems.add(getString(R.string.custom))

    val currentIndex = uIntervals.indexOf(current).let { if (it == -1) 1 else it }

    return listOf(
        Settings(
            type = 1,
            name = getString(R.string.unread_chapter_notification_checking_time, uItems[currentIndex]),
            desc = getString(R.string.unread_chapter_notification_checking_time_desc),
            icon = R.drawable.ic_round_notif_schedule_24,
            compact = true,
            anchorKey = "malsync_interval",
            onClick = {
                context.customAlertDialog().apply {
                    setTitle(R.string.unread_chapter_notification_frequency)
                    singleChoiceItems(uItems.toTypedArray(), currentIndex) { i ->
                        if (i == uItems.size - 1) {
                            showCustomIntervalDialog(
                                PrefName.UnreadChapterNotificationInterval, it,
                                R.string.unread_chapter_notification_checking_time, onChanged
                            )
                        } else {
                            PrefManager.setVal(PrefName.UnreadChapterNotificationInterval, uIntervals[i])
                            it.settingsTitle.text = getString(
                                R.string.unread_chapter_notification_checking_time, uItems[i]
                            )
                            rescheduleAll()
                            updateFirebaseTopics()
                            onChanged()
                        }
                    }
                    show()
                }
            }
        ),
        Settings(
            type = 2,
            name = getString(R.string.unread_manga_notifications),
            desc = getString(R.string.unread_manga_notifications_desc),
            icon = R.drawable.ic_round_import_contacts_24,
            compact = true,
            anchorKey = "malsync_manga",
            isChecked = PrefManager.getVal(PrefName.UnreadMangaNotificationsEnabled),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.UnreadMangaNotificationsEnabled, isChecked)
            },
        ),
        Settings(
            type = 2,
            name = getString(R.string.unread_episode_notifications),
            desc = getString(R.string.unread_episode_notifications_desc),
            icon = R.drawable.ic_round_movie_filter_24,
            compact = true,
            anchorKey = "malsync_episode",
            isChecked = PrefManager.getVal(PrefName.UnreadEpisodeNotificationsEnabled),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.UnreadEpisodeNotificationsEnabled, isChecked)
            },
        ),
        Settings(
            type = 2,
            name = getString(R.string.unread_chapter_check_progress_notification),
            desc = getString(R.string.unread_chapter_check_progress_notification_desc),
            icon = R.drawable.ic_round_notif_progress_24,
            compact = true,
            anchorKey = "malsync_progress",
            isChecked = PrefManager.getVal(PrefName.UnreadChapterCheckingNotifications),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.UnreadChapterCheckingNotifications, isChecked)
            },
            onLongClick = { openSettings(context, null) }
        ),
        Settings(
            type = 1,
            name = getString(R.string.clear_unread_chapter_history),
            desc = getString(R.string.clear_unread_chapter_history_desc),
            icon = R.drawable.ic_round_delete_sweep_24,
            compact = true,
            anchorKey = "malsync_clear",
            onClick = {
                context.customAlertDialog().apply {
                    setTitle(R.string.clear_unread_chapter_history)
                    setMessage(R.string.clear_unread_chapter_history_confirm)
                    setPosButton(R.string.yes) {
                        // The display store, and the ids that stop a chapter notifying twice.
                        PrefManager.setVal(
                            PrefName.UnreadChapterNotificationStore, listOf<UnreadChapterStore>()
                        )
                        context.getSharedPreferences("unread_notifications", Context.MODE_PRIVATE)
                            .edit()
                            .remove("notified_unread_chapters")
                            .remove("notified_unread_episodes")
                            .apply()
                        Toast.makeText(
                            context, R.string.clear_unread_chapter_history_success, Toast.LENGTH_SHORT
                        ).show()
                    }
                    setNegButton(R.string.cancel)
                    show()
                }
            }
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// AniList
// ---------------------------------------------------------------------------------------------

fun AppCompatActivity.anilistRows(onChanged: () -> Unit): List<Settings> {
    val context = this
    val aItems = AnilistNotificationWorker.checkIntervals.map { intervalLabel(it) }

    return listOf(
        Settings(
            type = 1,
            name = getString(
                R.string.anilist_notifications_checking_time,
                aItems.getOrElse(PrefManager.getVal(PrefName.AnilistNotificationInterval)) { aItems[0] }
            ),
            desc = getString(R.string.anilist_notifications_checking_time_desc),
            icon = R.drawable.ic_round_notif_schedule_24,
            compact = true,
            anchorKey = "anilist_interval",
            onClick = {
                context.customAlertDialog().apply {
                    setTitle(R.string.anilist_notification_frequency)
                    singleChoiceItems(
                        aItems.toTypedArray(),
                        PrefManager.getVal<Int>(PrefName.AnilistNotificationInterval)
                    ) { i ->
                        PrefManager.setVal(PrefName.AnilistNotificationInterval, i)
                        it.settingsTitle.text =
                            getString(R.string.anilist_notifications_checking_time, aItems[i])
                        rescheduleAll()
                        onChanged()
                    }
                    show()
                }
            }
        ),
        Settings(
            type = 1,
            name = getString(R.string.anilist_notification_filters),
            desc = getString(R.string.anilist_notification_filters_desc),
            icon = R.drawable.ic_anilist,
            compact = true,
            anchorKey = "anilist_filters",
            onClick = {
                val types = NotificationType.entries.map { it.name }
                val filteredTypes =
                    PrefManager.getVal<Set<String>>(PrefName.AnilistFilteredTypes).toMutableSet()
                val selected = types.map { filteredTypes.contains(it) }.toBooleanArray()
                context.customAlertDialog().apply {
                    setTitle(R.string.anilist_notification_filters)
                    multiChoiceItems(
                        types.map { name ->
                            name.replace("_", " ").lowercase().replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                            }
                        }.toTypedArray(),
                        selected
                    ) { updatedSelected ->
                        types.forEachIndexed { index, type ->
                            if (updatedSelected[index]) filteredTypes.add(type)
                            else filteredTypes.remove(type)
                        }
                        PrefManager.setVal(PrefName.AnilistFilteredTypes, filteredTypes)
                    }
                    show()
                }
            }
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// Comments
// ---------------------------------------------------------------------------------------------

fun AppCompatActivity.commentRows(onChanged: () -> Unit): List<Settings> {
    val context = this
    val cItems = CommentNotificationWorker.checkIntervals.map { intervalLabel(it) }

    return listOf(
        Settings(
            type = 1,
            name = getString(
                R.string.comment_notification_checking_time,
                cItems.getOrElse(PrefManager.getVal(PrefName.CommentNotificationInterval)) { cItems[0] }
            ),
            desc = getString(R.string.comment_notification_checking_time_desc),
            icon = R.drawable.ic_round_notif_schedule_24,
            compact = true,
            anchorKey = "comment_interval",
            onClick = {
                context.customAlertDialog().apply {
                    setTitle(R.string.comment_notification_frequency)
                    singleChoiceItems(
                        cItems.toTypedArray(),
                        PrefManager.getVal<Int>(PrefName.CommentNotificationInterval)
                    ) { i ->
                        PrefManager.setVal(PrefName.CommentNotificationInterval, i)
                        it.settingsTitle.text =
                            getString(R.string.comment_notification_checking_time, cItems[i])
                        rescheduleAll()
                        onChanged()
                    }
                    show()
                }
            }
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// MangaUpdates
// ---------------------------------------------------------------------------------------------

fun AppCompatActivity.mangaUpdatesRows(onChanged: () -> Unit): List<Settings> {
    val context = this
    val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
    val muInterval = PrefManager.getVal<Long>(PrefName.MangaUpdatesNotificationInterval)

    val intervalRow: Settings = if (unreadInterval > 0L) {
        // MangaUpdates is checked inside the MALSync task while that one runs, so its standalone
        // schedule is cancelled and its own interval would be misleading.
        Settings(
            type = 1,
            name = getString(R.string.mu_notification_interval_linked, formatIntervalMinutes(unreadInterval)),
            desc = getString(R.string.mu_notification_interval_linked_desc),
            icon = R.drawable.ic_round_notif_schedule_24,
            compact = true,
            anchorKey = "mu_interval",
            isEnabled = false,
        )
    } else {
        val intervals = mutableListOf(0L, 60L, 120L, 180L, 360L, 720L, 1440L)
        var customIndex = -1
        if (muInterval > 0L && !intervals.contains(muInterval)) {
            customIndex = intervals.size
            intervals.add(muInterval)
        }
        val items = intervals.mapIndexed { index, it ->
            if (it > 0L) {
                if (index == customIndex) "Custom: ${formatIntervalMinutes(it)}" else formatIntervalMinutes(it)
            } else getString(R.string.do_not_update)
        }.toMutableList()
        items.add(getString(R.string.custom))
        val currentIndex = intervals.indexOf(muInterval).let { if (it == -1) 0 else it }

        Settings(
            type = 1,
            name = getString(R.string.mu_notification_interval, items[currentIndex]),
            desc = getString(R.string.mu_notification_interval_desc),
            icon = R.drawable.ic_round_notif_schedule_24,
            compact = true,
            anchorKey = "mu_interval",
            onClick = {
                context.customAlertDialog().apply {
                    setTitle(R.string.mu_notification_interval_title)
                    singleChoiceItems(items.toTypedArray(), currentIndex) { i ->
                        if (i == items.size - 1) {
                            showCustomIntervalDialog(
                                PrefName.MangaUpdatesNotificationInterval, it,
                                R.string.mu_notification_interval, onChanged
                            )
                        } else {
                            PrefManager.setVal(PrefName.MangaUpdatesNotificationInterval, intervals[i])
                            it.settingsTitle.text =
                                getString(R.string.mu_notification_interval, items[i])
                            rescheduleAll()
                            onChanged()
                        }
                    }
                    show()
                }
            }
        )
    }

    return listOf(
        Settings(
            type = 2,
            name = getString(R.string.mu_notifications_enabled),
            desc = getString(R.string.mu_notifications_enabled_desc),
            icon = R.drawable.ic_round_mangaupdates_24,
            compact = true,
            anchorKey = "mu_enabled",
            isChecked = PrefManager.getVal<Boolean>(PrefName.MangaUpdatesNotificationsEnabled),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.MangaUpdatesNotificationsEnabled, isChecked)
            }
        ),
        intervalRow,
    )
}

// ---------------------------------------------------------------------------------------------
// Shared custom-interval dialog
// ---------------------------------------------------------------------------------------------

/**
 * The "Custom…" branch of an interval picker: a number field plus a Minutes/Hours unit.
 *
 * Was duplicated three times, once per screen that offered a custom interval, each with its own
 * copy of the same bounds and the same validation. One copy now, parameterised by the pref it
 * writes and the format string its row title uses.
 */
private fun AppCompatActivity.showCustomIntervalDialog(
    pref: PrefName,
    itemBinding: ItemSettingsBinding,
    titleFormatRes: Int,
    onChanged: () -> Unit,
) {
    val context = this
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.HORIZONTAL
    layout.setPadding(60, 20, 60, 20)

    val current = PrefManager.getVal<Long>(pref)
    val (defaultValue, defaultUnit) = if (current > 0) {
        if (current % 60 == 0L && current >= 60) Pair((current / 60).toString(), 1)
        else Pair(current.toString(), 0)
    } else Pair("60", 0)

    val input = EditText(context)
    input.inputType = InputType.TYPE_CLASS_NUMBER
    input.setText(defaultValue)
    input.setSelection(input.text.length)
    input.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    val unitSpinner = Spinner(context)
    val adapter = ArrayAdapter(
        context, android.R.layout.simple_spinner_item, arrayOf("Minutes", "Hours")
    )
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    unitSpinner.adapter = adapter
    unitSpinner.setSelection(defaultUnit)
    (unitSpinner.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 20

    layout.addView(input)
    layout.addView(unitSpinner)

    context.customAlertDialog().apply {
        setTitle(R.string.custom_interval_title)
        setMessage(R.string.custom_interval_desc)
        setCustomView(layout)
        setPosButton(R.string.ok) {
            val value = input.text.toString().toLongOrNull()
            val isHours = unitSpinner.selectedItemPosition == 1
            val maxValue = if (isHours) 24L else 1440L
            val customMinutes = if (value != null && isHours) value * 60 else value
            if (value != null && value > 0 && customMinutes != null &&
                customMinutes in 1..1440 && value <= maxValue
            ) {
                PrefManager.setVal(pref, customMinutes)
                itemBinding.settingsTitle.text =
                    getString(titleFormatRes, "Custom: ${formatIntervalMinutes(customMinutes)}")
                rescheduleAll()
                updateFirebaseTopics()
                onChanged()
            } else {
                val unit = if (isHours) "hours (max 24)" else "minutes (max 1440)"
                Toast.makeText(
                    context, "Please enter a valid value in $unit", Toast.LENGTH_SHORT
                ).show()
            }
        }
        setNegButton(R.string.cancel)
        show()
    }
}
