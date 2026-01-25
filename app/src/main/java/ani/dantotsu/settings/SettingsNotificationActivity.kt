package ani.dantotsu.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsNotificationsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog

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

            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    // === SUBSCRIPTION CHECKS SECTION ===
                    Settings(
                        type = 1,
                        name = getString(R.string.subscription_notifications),
                        desc = getString(R.string.subscription_notifications_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            startActivity(Intent(context, SettingsSubscriptionNotificationActivity::class.java))
                        }
                    ),

                    // === UNREAD CHAPTER CHECKS SECTION ===
                    Settings(
                        type = 1,
                        name = getString(R.string.unread_chapter_notifications),
                        desc = getString(R.string.unread_chapter_notifications_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            startActivity(Intent(context, SettingsUnreadChapterNotificationActivity::class.java))
                        }
                    ),

                    // === ANILIST NOTIFICATIONS SECTION ===
                    Settings(
                        type = 1,
                        name = getString(R.string.anilist_notifications),
                        desc = getString(R.string.anilist_notifications_desc),
                        icon = R.drawable.ic_anilist,
                        onClick = {
                            startActivity(Intent(context, SettingsAnilistNotificationActivity::class.java))
                        }
                    ),

                    // === COMMENT NOTIFICATIONS SECTION ===
                    Settings(
                        type = 1,
                        name = getString(R.string.comment_notifications),
                        desc = getString(R.string.comment_notifications_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            startActivity(Intent(context, SettingsCommentNotificationActivity::class.java))
                        }
                    ),

                    // === GENERAL SETTINGS ===
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
}
