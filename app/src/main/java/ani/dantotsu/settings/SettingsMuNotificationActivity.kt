package ani.dantotsu.settings

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsMuNotificationsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog

class SettingsMuNotificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsMuNotificationsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsMuNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView)
        binding.settingsMuNotificationsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.muNotificationSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        buildSettingsList()
    }

    private fun buildSettingsList() {
        val context = this
        val unreadInterval = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval)
        val muInterval = PrefManager.getVal<Long>(PrefName.MangaUpdatesNotificationInterval)

        val intervalSetting: Settings = if (unreadInterval > 0L) {
            Settings(
                type = 1,
                name = getString(R.string.mu_notification_interval_linked, formatInterval(unreadInterval)),
                desc = getString(R.string.mu_notification_interval_linked_desc),
                icon = R.drawable.ic_round_notifications_none_24,
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
                    if (index == customIndex) "Custom: ${formatInterval(it)}"
                    else formatInterval(it)
                } else getString(R.string.do_not_update)
            }.toMutableList()
            items.add(getString(R.string.custom))
            val currentIndex = intervals.indexOf(muInterval).let { if (it == -1) 0 else it }

            Settings(
                type = 1,
                name = getString(R.string.mu_notification_interval, items[currentIndex]),
                desc = getString(R.string.mu_notification_interval_desc),
                icon = R.drawable.ic_round_notifications_none_24,
                onClick = {
                    context.customAlertDialog().apply {
                        setTitle(R.string.mu_notification_interval_title)
                        singleChoiceItems(items.toTypedArray(), currentIndex) { i ->
                            if (i == items.size - 1) {
                                showCustomIntervalDialog(context, it)
                            } else {
                                PrefManager.setVal(PrefName.MangaUpdatesNotificationInterval, intervals[i])
                                it.settingsTitle.text = getString(R.string.mu_notification_interval, items[i])
                                TaskScheduler.create(
                                    context, PrefManager.getVal(PrefName.UseAlarmManager)
                                ).scheduleAllTasks(context)
                            }
                        }
                        show()
                    }
                }
            )
        }

        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 2,
                    name = getString(R.string.mu_notifications_enabled),
                    desc = getString(R.string.mu_notifications_enabled_desc),
                    icon = R.drawable.ic_round_mangaupdates_24,
                    isChecked = PrefManager.getVal<Boolean>(PrefName.MangaUpdatesNotificationsEnabled),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.MangaUpdatesNotificationsEnabled, isChecked)
                    }
                ),
                intervalSetting,
            )
        )
        binding.settingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SettingsMuNotificationActivity, LinearLayoutManager.VERTICAL, false)
            setHasFixedSize(true)
        }
    }

    private fun showCustomIntervalDialog(context: Context, itemBinding: ani.dantotsu.databinding.ItemSettingsBinding) {
        val layout = android.widget.LinearLayout(context)
        layout.orientation = android.widget.LinearLayout.HORIZONTAL
        layout.setPadding(60, 20, 60, 20)

        val input = android.widget.EditText(context)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val current = PrefManager.getVal<Long>(PrefName.MangaUpdatesNotificationInterval)
        val (defaultValue, defaultUnit) = if (current > 0) {
            if (current % 60 == 0L) Pair((current / 60).toString(), 1)
            else Pair(current.toString(), 0)
        } else Pair("60", 0)

        input.setText(defaultValue)
        input.setSelection(input.text.length)
        input.layoutParams = android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )

        val unitSpinner = android.widget.Spinner(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_item, arrayOf("Minutes", "Hours"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        unitSpinner.adapter = adapter
        unitSpinner.setSelection(defaultUnit)
        (unitSpinner.layoutParams as? android.widget.LinearLayout.LayoutParams)?.marginStart = 20

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
                    val maxValue = if (isHours) 24L else 1440L
                    if (customMinutes in 1..1440 && value <= maxValue) {
                        PrefManager.setVal(PrefName.MangaUpdatesNotificationInterval, customMinutes)
                        itemBinding.settingsTitle.text = getString(
                            R.string.mu_notification_interval,
                            "Custom: ${formatInterval(customMinutes)}"
                        )
                        TaskScheduler.create(
                            context, PrefManager.getVal(PrefName.UseAlarmManager)
                        ).scheduleAllTasks(context)
                    } else {
                        val unit = if (isHours) "hours (max 24)" else "minutes (max 1440)"
                        android.widget.Toast.makeText(context, "Please enter a valid value in $unit", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val unit = if (unitSpinner.selectedItemPosition == 1) "hours (max 24)" else "minutes (max 1440)"
                    android.widget.Toast.makeText(context, "Please enter a valid value in $unit", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    override fun onResume() {
        super.onResume()
        buildSettingsList()
    }

    private fun formatInterval(minutes: Long): String {
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return "${if (h > 0) "$h hrs " else ""}${if (m > 0) "$m mins" else ""}".trim()
    }
}
