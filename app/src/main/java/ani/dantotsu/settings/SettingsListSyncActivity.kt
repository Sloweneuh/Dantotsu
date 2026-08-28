package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.kitsu.Kitsu
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.simkl.Simkl
import ani.dantotsu.databinding.ActivitySettingsListSyncBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.notifications.TaskScheduler
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.durationLabel
import java.text.DateFormat
import java.util.Date

class SettingsListSyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsListSyncBinding

    /**
     * Interval choices for the automatic comparison, in minutes; 0 is off.
     *
     * A run is a full fetch of every list on both sides of every comparison, which is why the
     * shortest offered is half a day and the longest a week — the range this is useful over.
     */
    private val autoIntervals = listOf(0L, 720L, 1440L, 2880L, 4320L, 7200L, 10080L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsListSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.listSyncRecyclerView)

        binding.settingsListSyncLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        binding.listSyncSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.listSyncRecyclerView.layoutManager = LinearLayoutManager(this)
        render()
    }

    /**
     * (Re)builds the settings list. Rebuilt rather than patched in place because the removals switch
     * only exists while the automatic comparison is switched on, so picking an interval changes
     * which rows are on screen.
     */
    /**
     * Whether anything is left for the automatic comparison to write to: a tracker has to be both
     * signed in and switched on for sync, since [ani.dantotsu.connections.sync.AutoListSyncTask]
     * skips every section whose switch is off. With none of them on it would wake on schedule, find
     * nothing it is allowed to touch and go back to sleep, so the interval isn't offered at all.
     *
     * The manual comparison screen is deliberately not gated this way — pressing sync there *is*
     * the permission the switches stand in for.
     */
    private fun hasSyncTargets(): Boolean =
        (MAL.token != null && PrefManager.getVal(PrefName.MalListSyncEnabled)) ||
            (Kitsu.token != null && PrefManager.getVal(PrefName.KitsuListSyncEnabled)) ||
            (Simkl.token != null && PrefManager.getVal(PrefName.SimklListSyncEnabled)) ||
            (MangaBaka.token != null && PrefManager.getVal(PrefName.MangaBakaListSyncEnabled))

    /**
     * Applies a change to one of the per-tracker switches and redraws around it.
     *
     * Turning the last one off also turns the automatic comparison off rather than leaving it
     * scheduled with nothing to do — otherwise the row would vanish while a task went on waking up
     * every twelve hours to do nothing, with no way left on screen to stop it.
     */
    private fun onTargetToggled() {
        if (!hasSyncTargets() && PrefManager.getVal<Long>(PrefName.AutoListSyncInterval) > 0L) {
            applyInterval(0L)
        } else {
            render()
        }
    }

    private fun render() {
        val autoInterval = PrefManager.getVal<Long>(PrefName.AutoListSyncInterval)
        val canAutoSync = hasSyncTargets()
        val settingsList = arrayListOf(
            // Which trackers may be written to at all comes first: the comparison rows below are
            // scoped by these, and the automatic pass skips a tracker whose switch is off.
            Settings(
                type = 2,
                name = getString(R.string.mal_list_sync),
                desc = getString(R.string.mal_list_sync_desc),
                icon = R.drawable.ic_round_mal_sync_24,
                isChecked = PrefManager.getVal(PrefName.MalListSyncEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.MalListSyncEnabled, isChecked)
                    onTargetToggled()
                },
                isVisible = MAL.token != null,
            ),
            Settings(
                type = 2,
                name = getString(R.string.kitsu_list_sync),
                desc = getString(R.string.kitsu_list_sync_desc),
                icon = R.drawable.ic_kitsu,
                isChecked = PrefManager.getVal(PrefName.KitsuListSyncEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.KitsuListSyncEnabled, isChecked)
                    onTargetToggled()
                },
                isVisible = Kitsu.token != null,
            ),
            Settings(
                type = 2,
                name = getString(R.string.simkl_list_sync),
                desc = getString(R.string.simkl_list_sync_desc),
                icon = R.drawable.ic_simkl,
                isChecked = PrefManager.getVal(PrefName.SimklListSyncEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.SimklListSyncEnabled, isChecked)
                    onTargetToggled()
                },
                isVisible = Simkl.token != null,
            ),
            Settings(
                type = 2,
                name = getString(R.string.mangabaka_list_sync),
                desc = getString(R.string.mangabaka_list_sync_desc),
                icon = R.drawable.ic_round_mangabaka_sync_24,
                isChecked = PrefManager.getVal(PrefName.MangaBakaListSyncEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.MangaBakaListSyncEnabled, isChecked)
                    onTargetToggled()
                },
                isVisible = MangaBaka.token != null,
            ),
            Settings(
                type = 1,
                name = getString(R.string.compare_lists),
                desc = getString(R.string.compare_lists_desc),
                icon = R.drawable.ic_round_compare_arrows_24,
                onClick = {
                    startActivity(Intent(this, ListSyncCompareActivity::class.java))
                },
                isActivity = true,
                isVisible = MAL.token != null || Kitsu.token != null ||
                    Simkl.token != null || MangaBaka.token != null,
            ),
            Settings(
                type = 1,
                name = getString(R.string.auto_list_sync),
                desc = autoSyncDesc(autoInterval),
                icon = R.drawable.ic_round_compare_schedule_24,
                onClick = { view -> showIntervalDropdown(view.root) },
                isVisible = canAutoSync,
            ),
            Settings(
                type = 2,
                name = getString(R.string.auto_list_sync_removals),
                desc = getString(R.string.auto_list_sync_removals_desc),
                icon = R.drawable.ic_round_delete_sweep_24,
                isChecked = PrefManager.getVal(PrefName.AutoListSyncRemovals),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.AutoListSyncRemovals, isChecked)
                },
                isVisible = autoInterval > 0L && canAutoSync,
            ),
        )

        binding.listSyncRecyclerView.adapter = SettingsAdapter(settingsList)
    }

    /** Stores the interval, reschedules the task and redraws the screen around it. */
    private fun applyInterval(minutes: Long) {
        PrefManager.setVal(PrefName.AutoListSyncInterval, minutes)
        TaskScheduler.create(this, PrefManager.getVal(PrefName.UseAlarmManager))
            .scheduleAllTasks(this)
        render()
    }

    /** Interval picker: a dropdown of the fixed choices, anchored to the row it belongs to. */
    private fun showIntervalDropdown(anchor: View) {
        val labels = autoIntervals.map { intervalLabel(it) }
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, labels))
        popup.isModal = true
        popup.width = anchor.width
        popup.setBackgroundDrawable(
            ContextCompat.getDrawable(this, R.drawable.dropdown_background)
        )
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            applyInterval(autoIntervals[position])
        }
        popup.show()
    }

    private fun intervalLabel(minutes: Long): String =
        if (minutes <= 0L) getString(R.string.do_not_update) else durationLabel(minutes)

    /**
     * The setting's subtitle: what it does, then — once it's on — how often it runs and what the
     * last run did. The frequency lives here rather than in the title so the title stays the plain
     * string the settings search anchors on (see [SettingsSearch]).
     */
    private fun autoSyncDesc(interval: Long): String {
        val base = getString(R.string.auto_list_sync_desc)
        if (interval <= 0L) return base
        val every = getString(R.string.auto_list_sync_every, intervalLabel(interval))
        val lastRun = PrefManager.getVal<Long>(PrefName.AutoListSyncLastRun)
        if (lastRun <= 0L) return base + "\n" + getString(R.string.auto_list_sync_never_run, every)
        val ranAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(lastRun))
        val synced = PrefManager.getVal<Int>(PrefName.AutoListSyncLastSynced)
        val failed = PrefManager.getVal<Int>(PrefName.AutoListSyncLastFailed)
        val summary = if (failed > 0) {
            getString(R.string.auto_list_sync_last_run_failures, every, ranAt, synced, failed)
        } else {
            getString(R.string.auto_list_sync_last_run, every, ranAt, synced)
        }
        return "$base\n$summary"
    }
}
