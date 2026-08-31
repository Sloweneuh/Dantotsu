package ani.dantotsu.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.ConcatAdapter
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

/**
 * Every notification setting on one screen.
 *
 * Previously this was a menu of five links, each opening an Activity that held between one and six
 * settings. The sources are now collapsible groups, so the whole schedule is legible at a glance —
 * each card's header shows that source's current interval without being opened.
 *
 * The intervals themselves stay per-source. Each hits a different backend at a different cost and
 * the presets encode that (AniList allows 30 minutes, comments floor at 8 hours, subscriptions fan
 * out one request per subscribed media), so no preference changed in this merge.
 */
class SettingsNotificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsNotificationsBinding
    private lateinit var sectionAdapter: SettingsSectionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsNotificationsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.notificationSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Changing one source's interval can change what another reads: MangaUpdates is folded into
        // the MALSync task whenever that one is running, so its card has to re-summarise.
        val onChanged: () -> Unit = { sectionAdapter.refreshSummaries() }

        sectionAdapter = SettingsSectionAdapter(
            listOf(
                SettingsSection(
                    key = NotificationSection.SUBSCRIPTIONS,
                    title = getString(R.string.subscription_notifications),
                    icon = R.drawable.ic_round_notif_subscriptions_24,
                    summary = { subscriptionsSummary() },
                    rows = { subscriptionRows(onChanged) },
                ),
                SettingsSection(
                    key = NotificationSection.MALSYNC,
                    title = getString(R.string.unread_chapter_notifications),
                    icon = R.drawable.ic_round_malsync_notifications_24,
                    summary = { malSyncSummary() },
                    rows = { malSyncRows(onChanged) },
                ),
                SettingsSection(
                    key = NotificationSection.ANILIST,
                    title = getString(R.string.anilist_notifications),
                    icon = R.drawable.ic_round_notif_anilist_24,
                    summary = { anilistSummary() },
                    rows = { anilistRows(onChanged) },
                ),
                SettingsSection(
                    key = NotificationSection.COMMENTS,
                    title = getString(R.string.comment_notifications),
                    icon = R.drawable.ic_round_notif_comments_24,
                    summary = { commentsSummary() },
                    rows = { commentRows(onChanged) },
                ),
                SettingsSection(
                    key = NotificationSection.MANGAUPDATES,
                    title = getString(R.string.mu_notifications),
                    icon = R.drawable.ic_round_notif_mangaupdates_24,
                    summary = { mangaUpdatesSummary() },
                    rows = { mangaUpdatesRows(onChanged) },
                ),
            ),
            stateKey = SettingsSectionAdapter.STATE_NOTIFICATIONS,
            keepExpanded = SettingsRouter.hasAnchor(this),
        )

        // The one genuinely global control, which used to sit loose below the five links.
        val globalAdapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 2,
                    name = getString(R.string.use_alarm_manager_reliable),
                    desc = getString(R.string.use_alarm_manager_reliable_desc),
                    icon = R.drawable.ic_round_alarm_24,
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
                                            startActivity(Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM"))
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
                            TaskScheduler.create(context, false).scheduleAllTasks(context)
                        }
                    },
                ),
            )
        )

        binding.settingsRecyclerView.apply {
            adapter = ConcatAdapter(sectionAdapter, globalAdapter)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        }

        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView)
        SettingsRouter.handleSectionAnchor(this, sectionAdapter)
    }

    override fun onResume() {
        super.onResume()
        // MALSync's own switch lives on its Accounts card, so its group can be turned on or off
        // while this screen is in the back stack.
        if (::sectionAdapter.isInitialized) sectionAdapter.refreshSummaries()
    }
}
