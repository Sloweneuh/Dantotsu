package ani.dantotsu.settings

import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.databinding.ActivitySettingsNotificationsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionHelper
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.notifications.firebase.FirebaseBackgroundScheduler
import ani.dantotsu.openSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.util.Logger
import java.util.Locale

class SettingsNotificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsNotificationsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apply {
            settingsNotificationsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            notificationSettingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }

            // Subscription notification intervals (in minutes)
            val sIntervals = mutableListOf(0L, 60L, 120L, 180L, 360L, 480L, 720L, 1440L) // Off, 1h, 2h, 3h, 6h, 8h, 12h, 24h
            val currentSInterval = PrefManager.getVal<Long>(PrefName.SubscriptionNotificationIntervalMinutes)

            // Add custom interval if it's not in the predefined list
            var customSIntervalIndex = -1
            if (currentSInterval > 0L && !sIntervals.contains(currentSInterval)) {
                customSIntervalIndex = sIntervals.size
                sIntervals.add(currentSInterval)
            }

            val sItems = sIntervals.mapIndexed { index, it ->
                val mins = (it % 60).toInt()
                val hours = (it / 60).toInt()
                if (it > 0L) {
                    if (index == customSIntervalIndex) {
                        "Custom: ${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                    } else {
                        "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                    }
                } else getString(R.string.do_not_update)
            }.toMutableList()

            // Add "Custom..." option at the end
            sItems.add(getString(R.string.custom))

            val currentSIndex = sIntervals.indexOf(currentSInterval).let { if (it == -1) 5 else it } // Default to 8h if not found
            val aTimeNames = AnilistNotificationWorker.checkIntervals.map { it.toInt() }
            val aItems = aTimeNames.map {
                val mins = it % 60
                val hours = it / 60
                if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                else getString(R.string.do_not_update)
            }
            val cTimeNames = CommentNotificationWorker.checkIntervals.map { it.toInt() }
            val cItems = cTimeNames.map {
                val mins = it % 60
                val hours = it / 60
                if (it > 0) "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                else getString(R.string.do_not_update)
            }

            // Unread chapter notification intervals (in minutes)
            val uIntervals = mutableListOf(0L, 60L, 120L, 180L, 360L, 720L, 1440L) // Off, 1h, 2h, 3h, 6h, 12h, 24h
            val currentUInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)

            // Add custom interval if it's not in the predefined list
            var customIntervalIndex = -1
            if (currentUInterval > 0L && !uIntervals.contains(currentUInterval)) {
                customIntervalIndex = uIntervals.size
                uIntervals.add(currentUInterval)
            }

            val uItems = uIntervals.mapIndexed { index, it ->
                val mins = (it % 60).toInt()
                val hours = (it / 60).toInt()
                if (it > 0L) {
                    if (index == customIntervalIndex) {
                        "Custom: ${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                    } else {
                        "${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"
                    }
                } else getString(R.string.do_not_update)
            }.toMutableList()

            // Add "Custom..." option at the end
            uItems.add(getString(R.string.custom))

            val currentUIndex = uIntervals.indexOf(currentUInterval).let { if (it == -1) 1 else it }

            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = getString(
                            R.string.subscriptions_checking_time_s,
                            sItems[currentSIndex]
                        ),
                        desc = getString(R.string.subscriptions_info),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                setTitle(R.string.subscriptions_checking_time)
                                singleChoiceItems(
                                    sItems.toTypedArray(),
                                    currentSIndex
                                ) { i ->
                                    if (i == sItems.size - 1) {
                                        // Custom option selected
                                        showCustomSubscriptionIntervalDialog(context, it)
                                    } else {
                                        PrefManager.setVal(PrefName.SubscriptionNotificationIntervalMinutes, sIntervals[i])
                                        it.settingsTitle.text = getString(
                                            R.string.subscriptions_checking_time_s,
                                            sItems[i]
                                        )
                                        TaskScheduler.create(
                                            context, PrefManager.getVal(PrefName.UseAlarmManager)
                                        ).scheduleAllTasks(context)
                                        // Update Firebase subscriptions (Google Play only)
                                        if (ani.dantotsu.BuildConfig.FLAVOR == "google") {
                                            try {
                                                FirebaseBackgroundScheduler.updateSubscriptions()
                                            } catch (e: Exception) {
                                                Logger.log("Failed to update Firebase subscriptions: ${e.message}")
                                            }
                                        }
                                    }
                                }
                                show()
                            }
                        },
                        onLongClick = {
                            TaskScheduler.create(
                                context, PrefManager.getVal(PrefName.UseAlarmManager)
                            ).scheduleAllTasks(context)
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.view_subscriptions),
                        desc = getString(R.string.view_subscriptions_desc),
                        icon = R.drawable.ic_round_search_24,
                        onClick = {
                            val subscriptions = SubscriptionHelper.getSubscriptions()
                            SubscriptionsBottomDialog.newInstance(subscriptions).show(
                                supportFragmentManager,
                                "subscriptions"
                            )
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.anilist_notification_filters),
                        desc = getString(R.string.anilist_notification_filters_desc),
                        icon = R.drawable.ic_anilist,
                        onClick = {
                            val types = NotificationType.entries.map { it.name }
                            val filteredTypes =
                                PrefManager.getVal<Set<String>>(PrefName.AnilistFilteredTypes)
                                    .toMutableSet()
                            val selected = types.map { filteredTypes.contains(it) }.toBooleanArray()
                            context.customAlertDialog().apply {
                                 setTitle(R.string.anilist_notification_filters)
                                 multiChoiceItems(
                                    types.map { name ->
                                        name.replace("_", " ").lowercase().replaceFirstChar {
                                        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                                    } }.toTypedArray(),
                                    selected
                                ) { updatedSelected ->
                                types.forEachIndexed { index, type ->
                                    if (updatedSelected[index]) {
                                        filteredTypes.add(type)
                                    } else {
                                        filteredTypes.remove(type)
                                    }
                                }
                                    PrefManager.setVal(PrefName.AnilistFilteredTypes, filteredTypes)
                                }
                                show()
                            }
                        }

                    ),
                    Settings(
                        type = 1,
                        name = getString(
                            R.string.anilist_notifications_checking_time,
                            aItems[PrefManager.getVal(PrefName.AnilistNotificationInterval)]
                        ),
                        desc = getString(R.string.anilist_notifications_checking_time_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                 setTitle(R.string.subscriptions_checking_time)
                                 singleChoiceItems(
                                    aItems.toTypedArray(),
                                    PrefManager.getVal<Int>(PrefName.AnilistNotificationInterval)
                                ) { i ->
                                    PrefManager.setVal(PrefName.AnilistNotificationInterval, i)
                                    it.settingsTitle.text =
                                        getString(
                                            R.string.anilist_notifications_checking_time,
                                            aItems[i]
                                        )
                                    TaskScheduler.create(
                                        context, PrefManager.getVal(PrefName.UseAlarmManager)
                                    ).scheduleAllTasks(context)
                                }
                                show()
                            }
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(
                            R.string.comment_notification_checking_time,
                            cItems[PrefManager.getVal(PrefName.CommentNotificationInterval)]
                        ),
                        desc = getString(R.string.comment_notification_checking_time_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                 setTitle(R.string.subscriptions_checking_time)
                                 singleChoiceItems(
                                    cItems.toTypedArray(),
                                    PrefManager.getVal<Int>(PrefName.CommentNotificationInterval)
                                ) {  i ->
                                    PrefManager.setVal(PrefName.CommentNotificationInterval, i)
                                    it.settingsTitle.text =
                                        getString(
                                            R.string.comment_notification_checking_time,
                                            cItems[i]
                                        )
                                    TaskScheduler.create(
                                        context, PrefManager.getVal(PrefName.UseAlarmManager)
                                    ).scheduleAllTasks(context)
                                }
                                show()
                            }
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(
                            R.string.unread_chapter_notification_checking_time,
                            uItems[currentUIndex]
                        ),
                        desc = getString(R.string.unread_chapter_notification_checking_time_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                setTitle(R.string.subscriptions_checking_time)
                                singleChoiceItems(
                                    uItems.toTypedArray(),
                                    currentUIndex
                                ) { i ->
                                    if (i == uItems.size - 1) {
                                        // Custom option selected
                                        showCustomIntervalDialog(context, it)
                                    } else {
                                        PrefManager.setVal(PrefName.UnreadChapterNotificationInterval, uIntervals[i])
                                        it.settingsTitle.text = getString(
                                            R.string.unread_chapter_notification_checking_time,
                                            uItems[i]
                                        )
                                        TaskScheduler.create(
                                            context, PrefManager.getVal(PrefName.UseAlarmManager)
                                        ).scheduleAllTasks(context)
                                        // Update Firebase subscriptions (Google Play only)
                                        if (ani.dantotsu.BuildConfig.FLAVOR == "google") {
                                            try {
                                                FirebaseBackgroundScheduler.updateSubscriptions()
                                            } catch (e: Exception) {
                                                Logger.log("Failed to update Firebase subscriptions: ${e.message}")
                                            }
                                        }
                                    }
                                }
                                show()
                            }
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.notification_for_checking_subscriptions),
                        desc = getString(R.string.notification_for_checking_subscriptions_desc),
                        icon = R.drawable.ic_round_smart_button_24,
                        isChecked = PrefManager.getVal(PrefName.SubscriptionCheckingNotifications),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(
                                PrefName.SubscriptionCheckingNotifications,
                                isChecked
                            )
                        },
                        onLongClick = {
                            openSettings(context, null)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.use_alarm_manager_reliable),
                        desc = getString(R.string.use_alarm_manager_reliable_desc),
                        icon = R.drawable.ic_anilist,
                        isChecked = PrefManager.getVal(PrefName.UseAlarmManager),
                        switch = { isChecked, view ->
                            if (isChecked) {
                                context.customAlertDialog().apply {
                                     setTitle(R.string.use_alarm_manager)
                                     setMessage(R.string.use_alarm_manager_confirm)
                                     setPosButton(R.string.use) {
                                        PrefManager.setVal(PrefName.UseAlarmManager, true)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            if (!(getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()) {
                                                val intent =
                                                    Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                                                startActivity(intent)
                                                view.settingsButton.isChecked = true
                                            }
                                        }
                                    }
                                    setNegButton(R.string.cancel) {
                                        view.settingsButton.isChecked = false
                                        PrefManager.setVal(PrefName.UseAlarmManager, false)
                                    }
                                    show()
                                }
                            } else {
                                PrefManager.setVal(PrefName.UseAlarmManager, false)
                                TaskScheduler.create(context, true).cancelAllTasks()
                                TaskScheduler.create(context, false)
                                    .scheduleAllTasks(context)
                            }
                        },
                    ),
                )
            )
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }
        }
    }

    private fun showCustomIntervalDialog(context: Context, binding: ani.dantotsu.databinding.ItemSettingsBinding) {
        // Create a horizontal layout with EditText and Spinner
        val layout = android.widget.LinearLayout(context)
        layout.orientation = android.widget.LinearLayout.HORIZONTAL
        layout.setPadding(60, 20, 60, 20)

        val input = android.widget.EditText(context)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val currentInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)

        // Convert current interval to appropriate unit and set default value
        val (defaultValue, defaultUnit) = if (currentInterval > 0) {
            if (currentInterval % 60 == 0L && currentInterval >= 60) {
                Pair((currentInterval / 60).toString(), 1) // Hours
            } else {
                Pair(currentInterval.toString(), 0) // Minutes
            }
        } else {
            Pair("60", 0) // Default 60 minutes
        }

        input.setText(defaultValue)
        input.setSelection(input.text.length)
        input.layoutParams = android.widget.LinearLayout.LayoutParams(
            0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )

        // Create spinner for unit selection
        val unitSpinner = android.widget.Spinner(context)
        val units = arrayOf("Minutes", "Hours")
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_item, units)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        unitSpinner.adapter = adapter
        unitSpinner.setSelection(defaultUnit)
        unitSpinner.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        (unitSpinner.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart = 20

        layout.addView(input)
        layout.addView(unitSpinner)

        context.customAlertDialog().apply {
            setTitle(R.string.custom_interval_title)
            setMessage(R.string.custom_interval_desc)
            setCustomView(layout)
            setPosButton(R.string.ok) {
                val value = input.text.toString().toLongOrNull()
                val isHours = unitSpinner.selectedItemPosition == 1

                if (value != null && value > 0) {
                    val customMinutes = if (isHours) value * 60 else value
                    val maxValue = if (isHours) 24 else 1440

                    if (customMinutes > 0 && customMinutes <= 1440 && value <= maxValue) {
                        PrefManager.setVal(PrefName.UnreadChapterNotificationInterval, customMinutes)

                        val mins = (customMinutes % 60).toInt()
                        val hours = (customMinutes / 60).toInt()
                        val displayText = "Custom: ${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"

                        binding.settingsTitle.text = getString(
                            R.string.unread_chapter_notification_checking_time,
                            displayText
                        )
                        TaskScheduler.create(
                            context, PrefManager.getVal(PrefName.UseAlarmManager)
                        ).scheduleAllTasks(context)
                        // Update Firebase subscriptions (Google Play only)
                        if (ani.dantotsu.BuildConfig.FLAVOR == "google") {
                            try {
                                FirebaseBackgroundScheduler.updateSubscriptions()
                            } catch (e: Exception) {
                                Logger.log("Failed to update Firebase subscriptions: ${e.message}")
                            }
                        }
                    } else {
                        // Invalid input, show error
                        val unit = if (isHours) "hours (max 24)" else "minutes (max 1440)"
                        android.widget.Toast.makeText(
                            context,
                            "Please enter a valid value in $unit",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    // Invalid input, show error
                    val unit = if (unitSpinner.selectedItemPosition == 1) "hours (max 24)" else "minutes (max 1440)"
                    android.widget.Toast.makeText(
                        context,
                        "Please enter a valid value in $unit",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun showCustomSubscriptionIntervalDialog(context: Context, binding: ani.dantotsu.databinding.ItemSettingsBinding) {
        // Create a horizontal layout with EditText and Spinner
        val layout = android.widget.LinearLayout(context)
        layout.orientation = android.widget.LinearLayout.HORIZONTAL
        layout.setPadding(60, 20, 60, 20)

        val input = android.widget.EditText(context)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val currentInterval = PrefManager.getVal<Long>(PrefName.SubscriptionNotificationIntervalMinutes)

        // Convert current interval to appropriate unit and set default value
        val (defaultValue, defaultUnit) = if (currentInterval > 0) {
            if (currentInterval % 60 == 0L && currentInterval >= 60) {
                Pair((currentInterval / 60).toString(), 1) // Hours
            } else {
                Pair(currentInterval.toString(), 0) // Minutes
            }
        } else {
            Pair("480", 0) // Default 480 minutes (8 hours)
        }

        input.setText(defaultValue)
        input.setSelection(input.text.length)
        input.layoutParams = android.widget.LinearLayout.LayoutParams(
            0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )

        // Create spinner for unit selection
        val unitSpinner = android.widget.Spinner(context)
        val units = arrayOf("Minutes", "Hours")
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_item, units)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        unitSpinner.adapter = adapter
        unitSpinner.setSelection(defaultUnit)
        unitSpinner.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        (unitSpinner.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart = 20

        layout.addView(input)
        layout.addView(unitSpinner)

        context.customAlertDialog().apply {
            setTitle(R.string.custom_interval_title)
            setMessage(R.string.custom_interval_desc)
            setCustomView(layout)
            setPosButton(R.string.ok) {
                val value = input.text.toString().toLongOrNull()
                val isHours = unitSpinner.selectedItemPosition == 1

                if (value != null && value > 0) {
                    val customMinutes = if (isHours) value * 60 else value
                    val maxValue = if (isHours) 24 else 1440

                    if (customMinutes > 0 && customMinutes <= 1440 && value <= maxValue) {
                        PrefManager.setVal(PrefName.SubscriptionNotificationIntervalMinutes, customMinutes)

                        val mins = (customMinutes % 60).toInt()
                        val hours = (customMinutes / 60).toInt()
                        val displayText = "Custom: ${if (hours > 0) "$hours hrs " else ""}${if (mins > 0) "$mins mins" else ""}"

                        binding.settingsTitle.text = getString(
                            R.string.subscriptions_checking_time_s,
                            displayText
                        )
                        TaskScheduler.create(
                            context, PrefManager.getVal(PrefName.UseAlarmManager)
                        ).scheduleAllTasks(context)
                        // Update Firebase subscriptions (Google Play only)
                        if (ani.dantotsu.BuildConfig.FLAVOR == "google") {
                            try {
                                FirebaseBackgroundScheduler.updateSubscriptions()
                            } catch (e: Exception) {
                                Logger.log("Failed to update Firebase subscriptions: ${e.message}")
                            }
                        }
                    } else {
                        // Invalid input, show error
                        val unit = if (isHours) "hours (max 24)" else "minutes (max 1440)"
                        android.widget.Toast.makeText(
                            context,
                            "Please enter a valid value in $unit",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    // Invalid input, show error
                    val unit = if (unitSpinner.selectedItemPosition == 1) "hours (max 24)" else "minutes (max 1440)"
                    android.widget.Toast.makeText(
                        context,
                        "Please enter a valid value in $unit",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            setNegButton(R.string.cancel)
            show()
        }
    }
}
