package ani.dantotsu.settings

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsUnreadChapterNotificationsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.notifications.firebase.FirebaseBackgroundScheduler
import ani.dantotsu.openSettings
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.util.Logger

class SettingsUnreadChapterNotificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsUnreadChapterNotificationsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsUnreadChapterNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apply {
            settingsUnreadChapterNotificationsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            unreadChapterNotificationSettingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
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
                        name = getString(R.string.unread_chapter_check_progress_notification),
                        desc = getString(R.string.unread_chapter_check_progress_notification_desc),
                        icon = R.drawable.ic_round_smart_button_24,
                        isChecked = PrefManager.getVal(PrefName.UnreadChapterCheckingNotifications),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(
                                PrefName.UnreadChapterCheckingNotifications,
                                isChecked
                            )
                        },
                        onLongClick = {
                            openSettings(context, null)
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.clear_unread_chapter_history),
                        desc = getString(R.string.clear_unread_chapter_history_desc),
                        icon = R.drawable.ic_delete,
                        onClick = {
                            context.customAlertDialog().apply {
                                setTitle(R.string.clear_unread_chapter_history)
                                setMessage(R.string.clear_unread_chapter_history_confirm)
                                setPosButton(R.string.yes) {
                                    // Clear the notification history store (UI display)
                                    PrefManager.setVal(
                                        PrefName.UnreadChapterNotificationStore,
                                        listOf<ani.dantotsu.notifications.unread.UnreadChapterStore>()
                                    )
                                    
                                    // Clear the notified chapters tracking (prevents duplicates)
                                    val prefs = context.getSharedPreferences("unread_notifications", android.content.Context.MODE_PRIVATE)
                                    prefs.edit().remove("notified_unread_chapters").apply()
                                    
                                    android.widget.Toast.makeText(
                                        context,
                                        R.string.clear_unread_chapter_history_success,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                setNegButton(R.string.cancel)
                                show()
                            }
                        }
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
}
