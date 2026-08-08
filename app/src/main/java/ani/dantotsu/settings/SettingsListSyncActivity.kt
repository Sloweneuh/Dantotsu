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
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
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
    private fun render() {
        val autoInterval = PrefManager.getVal<Long>(PrefName.AutoListSyncInterval)
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
                },
                isVisible = MAL.token != null,
            ),
            Settings(
                type = 2,
                name = getString(R.string.mangabaka_list_sync),
                desc = getString(R.string.mangabaka_list_sync_desc),
                icon = R.drawable.ic_round_mangabaka_sync_24,
                isChecked = PrefManager.getVal(PrefName.MangaBakaListSyncEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.MangaBakaListSyncEnabled, isChecked)
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
                isVisible = MAL.token != null || MangaBaka.token != null,
            ),
            Settings(
                type = 1,
                name = getString(R.string.auto_list_sync),
                desc = autoSyncDesc(autoInterval),
                icon = R.drawable.ic_round_compare_schedule_24,
                onClick = { view -> showIntervalDropdown(view.root) },
                isVisible = MAL.token != null || MangaBaka.token != null,
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
                isVisible = autoInterval > 0L && (MAL.token != null || MangaBaka.token != null),
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
