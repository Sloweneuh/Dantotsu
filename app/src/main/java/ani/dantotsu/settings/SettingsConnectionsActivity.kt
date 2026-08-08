package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.databinding.ActivitySettingsConnectionsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.InfoTabContext
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin

class SettingsConnectionsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsConnectionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.connectionsRecyclerView)

        binding.settingsConnectionsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        binding.connectionsSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val settingsList = arrayListOf(
            Settings(
                type = 2,
                name = getString(R.string.disable_mal),
                desc = getString(R.string.disable_mal_desc),
                icon = R.drawable.ic_myanimelist,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MalEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MalEnabled, isChecked) },
                attachToSwitch = { b ->
                    b.settingsExtraIcon.visibility = View.VISIBLE
                    b.settingsExtraIcon.setImageDrawable(
                        ContextCompat.getDrawable(this, R.drawable.ic_round_help_24)
                    )
                    b.settingsExtraIcon.setOnClickListener {
                        CustomBottomDialog.newInstance().apply {
                            setTitleText(this@SettingsConnectionsActivity.getString(R.string.mal_connections_help))
                            addView(
                                TextView(it.context).apply {
                                    val markWon = Markwon.builder(it.context)
                                        .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                                    markWon.setMarkdown(this, this@SettingsConnectionsActivity.getString(R.string.full_mal_connections_help))
                                }
                            )
                        }.show(supportFragmentManager, "mal_help")
                    }
                }
            ),
            Settings(
                type = 2,
                name = getString(R.string.disable_mangabaka),
                desc = getString(R.string.disable_mangabaka_desc),
                icon = R.drawable.ic_round_mangabaka_24,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MangaBakaInfoEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MangaBakaInfoEnabled, isChecked) },
                attachToSwitch = { b ->
                    b.settingsExtraIcon.visibility = View.VISIBLE
                    b.settingsExtraIcon.setImageDrawable(
                        ContextCompat.getDrawable(this, R.drawable.ic_round_help_24)
                    )
                    b.settingsExtraIcon.setOnClickListener {
                        CustomBottomDialog.newInstance().apply {
                            setTitleText(this@SettingsConnectionsActivity.getString(R.string.mangabaka_connections_help))
                            addView(
                                TextView(it.context).apply {
                                    val markWon = Markwon.builder(it.context)
                                        .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                                    markWon.setMarkdown(this, this@SettingsConnectionsActivity.getString(R.string.full_mangabaka_connections_help))
                                }
                            )
                        }.show(supportFragmentManager, "mangabaka_help")
                    }
                }
            ),
            Settings(
                type = 2,
                name = getString(R.string.disable_comick),
                desc = getString(R.string.disable_comick_desc),
                icon = R.drawable.ic_round_comick_24,
                isChecked = PrefManager.getVal<Boolean>(PrefName.ComickEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.ComickEnabled, isChecked) },
                attachToSwitch = { b ->
                    b.settingsExtraIcon.visibility = View.VISIBLE
                    b.settingsExtraIcon.setImageDrawable(
                        ContextCompat.getDrawable(this, R.drawable.ic_round_help_24)
                    )
                    b.settingsExtraIcon.setOnClickListener {
                        CustomBottomDialog.newInstance().apply {
                            setTitleText(this@SettingsConnectionsActivity.getString(R.string.comick_connections_help))
                            addView(
                                TextView(it.context).apply {
                                    val markWon = Markwon.builder(it.context)
                                        .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                                    markWon.setMarkdown(this, this@SettingsConnectionsActivity.getString(R.string.full_comick_connections_help))
                                }
                            )
                        }.show(supportFragmentManager, "comick_help")
                    }
                }
            ),
            // MangaUpdates used to own a settings screen of its own, holding these three rows and
            // nothing else. Its first row wrote PrefName.MangaUpdatesEnabled — the same preference
            // the other four services set from here — while calling itself "MangaUpdates tab", so
            // the one switch appeared as a tab-visibility setting there and as a data-fetching one
            // on the backup screen. Worse, that screen was only listed while signed in to
            // MangaUpdates, which hid the master switch for info fetching, something that needs no
            // account at all.
            Settings(
                type = 2,
                name = getString(R.string.disable_mangaupdates),
                desc = getString(R.string.disable_mangaupdates_desc),
                icon = R.drawable.ic_round_mangaupdates_24,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MangaUpdatesEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MangaUpdatesEnabled, isChecked) },
            ),
            Settings(
                type = 2,
                name = getString(R.string.disable_malsync),
                desc = getString(R.string.disable_malsync_desc),
                icon = R.drawable.ic_malsync,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MalSyncInfoEnabled, isChecked) },
                attachToSwitch = { b ->
                    // Show a small settings icon to configure whether MALSync checks manga, anime or both.
                    // Long-click the icon to show the MALSync help dialog.
                    b.settingsExtraIcon.visibility = View.VISIBLE
                    b.settingsExtraIcon.setImageDrawable(
                        ContextCompat.getDrawable(this, R.drawable.ic_round_settings_24)
                    )
                    b.settingsExtraIcon.setOnLongClickListener {
                        CustomBottomDialog.newInstance().apply {
                            setTitleText(this@SettingsConnectionsActivity.getString(R.string.malsync_connections_help))
                            addView(
                                TextView(it.context).apply {
                                    val markWon = Markwon.builder(it.context)
                                        .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                                    markWon.setMarkdown(this, this@SettingsConnectionsActivity.getString(R.string.full_malsync_connections_help))
                                }
                            )
                        }.show(supportFragmentManager, "malsync_help")
                        true
                    }
                    // Update description to reflect current mode
                    val mode = PrefManager.getVal<String>(PrefName.MalSyncCheckMode)
                    val modeText = when (mode) {
                        "manga" -> getString(R.string.malsync_checks_option_manga)
                        "anime" -> getString(R.string.malsync_checks_option_anime)
                        else -> getString(R.string.malsync_checks_option_both)
                    }
                    b.settingsDesc.text = getString(R.string.malsync_checks_desc, modeText)

                    b.settingsExtraIcon.setOnClickListener {
                        val modeOptions = arrayOf(
                            getString(R.string.malsync_checks_option_manga),
                            getString(R.string.malsync_checks_option_anime),
                            getString(R.string.malsync_checks_option_both)
                        )
                        // What MALSync is asked about, and how the answers are ordered on the home
                        // row. Two dropdowns rather than the single-choice list this used to be:
                        // the list form has room for exactly one question.
                        val sortOptions = arrayOf(
                            getString(R.string.unread_sort_option_unread),
                            getString(R.string.unread_sort_option_recent)
                        )
                        val dialogView = layoutInflater.inflate(R.layout.dialog_malsync_checks, null)
                        val modeDropdown =
                            dialogView.findViewById<AutoCompleteTextView>(R.id.malSyncModeDropdown)
                        val sortDropdown =
                            dialogView.findViewById<AutoCompleteTextView>(R.id.unreadSortDropdown)
                        modeDropdown.setAdapter(
                            ArrayAdapter(this@SettingsConnectionsActivity, R.layout.item_dropdown, modeOptions)
                        )
                        sortDropdown.setAdapter(
                            ArrayAdapter(this@SettingsConnectionsActivity, R.layout.item_dropdown, sortOptions)
                        )
                        val currentIndex = when (PrefManager.getVal<String>(PrefName.MalSyncCheckMode)) {
                            "manga" -> 0
                            "anime" -> 1
                            else -> 2
                        }
                        modeDropdown.setText(modeOptions[currentIndex], false)
                        sortDropdown.setText(
                            sortOptions[
                                if (PrefManager.getVal<String>(PrefName.UnreadChaptersSort) == "recent") 1 else 0
                            ],
                            false
                        )
                        // Applied as they are picked, so the dialog needs no confirm button and
                        // dismissing it can't lose a choice.
                        modeDropdown.setOnItemClickListener { _, _, i, _ ->
                            PrefManager.setVal(
                                PrefName.MalSyncCheckMode,
                                when (i) {
                                    0 -> "manga"
                                    1 -> "anime"
                                    else -> "both"
                                }
                            )
                            b.settingsDesc.text =
                                getString(R.string.malsync_checks_desc, modeOptions[i])
                        }
                        sortDropdown.setOnItemClickListener { _, _, i, _ ->
                            PrefManager.setVal(
                                PrefName.UnreadChaptersSort,
                                if (i == 1) "recent" else "unread"
                            )
                        }

                        this@SettingsConnectionsActivity.customAlertDialog().apply {
                            setTitle(R.string.malsync_checks_dialog_title)
                            setCustomView(dialogView)
                            setPosButton(R.string.close) {}
                            setNeutralButton("?") {
                                CustomBottomDialog.newInstance().apply {
                                    setTitleText(this@SettingsConnectionsActivity.getString(R.string.malsync_connections_help))
                                    addView(TextView(this@SettingsConnectionsActivity).apply {
                                        val markWon = Markwon.builder(this@SettingsConnectionsActivity)
                                            .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                                        markWon.setMarkdown(this, this@SettingsConnectionsActivity.getString(R.string.full_malsync_connections_help))
                                    })
                                }.show(supportFragmentManager, "malsync_help")
                            }
                            show()
                        }
                    }
                }
            ),
            // The list rows do need the account, so they keep the visibility condition the old
            // screen's entry point carried.
            Settings(
                type = 2,
                name = getString(R.string.mu_list_fetch_enabled),
                desc = getString(R.string.mu_list_fetch_enabled_desc),
                icon = R.drawable.ic_round_mangaupdates_list_24,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MangaUpdatesListEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MangaUpdatesListEnabled, isChecked) },
                isVisible = MangaUpdates.token != null,
            ),
            Settings(
                type = 1,
                name = getString(R.string.mu_custom_list_mapping),
                desc = getString(R.string.mu_custom_list_mapping_desc),
                icon = R.drawable.ic_round_mangaupdates_mapping_24,
                onClick = {
                    startActivity(Intent(this, MUCustomListMappingActivity::class.java))
                },
                isActivity = true,
                isVisible = MangaUpdates.token != null,
            ),
            Settings(
                type = 1,
                name = getString(R.string.malsync_exclude_manage),
                desc = getString(R.string.malsync_exclude_manage_desc),
                icon = R.drawable.ic_round_malsync_exclude_24,
                onClick = { showMalSyncExcludeDialog() },
            ),
            Settings(
                type = 1,
                name = getString(R.string.customize_info_tabs),
                desc = getString(R.string.customize_info_tabs_desc),
                icon = R.drawable.ic_round_view_array_24,
                onClick = { openInfoTabOrderDialog() },
            ),
        )

        binding.connectionsRecyclerView.adapter = SettingsAdapter(settingsList)
        binding.connectionsRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun showMalSyncExcludeDialog() {
        MediaExcludeBottomDialog.newInstance(
            PrefName.MalSyncExcludeList,
            getString(R.string.malsync_exclude_manage)
        ).show(supportFragmentManager, "malSyncExclude")
    }

    /**
     * Opens one dialog covering all three [InfoTabContext]s (AniList anime, AniList manga,
     * MangaUpdates manga) from a single button. A [TabLayout] selector switches which context's
     * list is shown; each is a full-width, vertically drag-to-reorder list (same row style as
     * [UserInterfaceSettingsActivity]'s home-layout reorder) so touch targets stay comfortable
     * regardless of how many tabs a context has. All three lists are built up front so switching
     * the selector doesn't lose in-progress edits in the other sections; everything is committed
     * together on OK.
     *
     * The checkbox only controls whether the tab appears - it does not affect whether the
     * underlying connection's data fetching runs (see [ani.dantotsu.media.InfoTabType.fetchEnabled]).
     * Connections switched off above are listed but inert, which is the only place the two controls
     * meet: both have to agree before a tab shows, so a disabled connection has to explain itself
     * here rather than leave a tick that does nothing.
     */
    private fun openInfoTabOrderDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_info_tab_order, null)
        val tabLayout = dialogView.findViewById<com.google.android.material.tabs.TabLayout>(R.id.infoTabContextTabs)
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.infoTabRecycler)
        recycler.layoutManager = LinearLayoutManager(this)

        val sections = listOf(
            InfoTabContext.ANILIST_ANIME to getString(R.string.anime),
            InfoTabContext.ANILIST_MANGA to getString(R.string.manga),
            InfoTabContext.MANGAUPDATES_MANGA to getString(R.string.mangaupdates),
        )
        val adapters = sections.associate { (tabContext, _) -> tabContext to buildInfoTabAdapter(tabContext) }

        var touchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null
        fun showSection(tabContext: InfoTabContext) {
            touchHelper?.attachToRecyclerView(null)
            val adapter = adapters.getValue(tabContext)
            recycler.adapter = adapter
            touchHelper = attachReorderTouchHelper(recycler, adapter)
        }

        sections.forEach { (_, label) -> tabLayout.addTab(tabLayout.newTab().setText(label)) }
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                showSection(sections[tab.position].first)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
        showSection(sections.first().first)

        customAlertDialog().apply {
            setTitle(R.string.customize_info_tabs)
            setCustomView(dialogView)
            setPosButton(R.string.ok) {
                adapters.forEach { (tabContext, adapter) -> saveInfoTabOrder(tabContext, adapter) }
            }
            setNegButton(R.string.cancel, null)
            show()
        }
    }

    /**
     * Builds one [InfoTabContext]'s tabs, in saved order, as an [InfoTabOrderAdapter].
     *
     * Tabs whose connection switch is off are listed too, sorted to the bottom and drawn inert. They
     * used to be dropped, which read as the list being incomplete — the one tab you came here to
     * find simply absent, with the switch that removed it two screens away and no hint of the
     * connection. It also lost their saved visibility: [saveInfoTabOrder] defaults anything missing
     * from the adapter back to shown, so disabling a connection quietly un-hid its tab for whenever
     * it was switched back on.
     */
    private fun buildInfoTabAdapter(tabContext: InfoTabContext): InfoTabOrderAdapter {
        val tabs = tabContext.tabs
        val visibility = tabContext.savedVisibility()

        val items = tabContext.savedOrder()
            .sortedBy { !tabs[it].fetchEnabled }
            .map { originalIndex ->
                InfoTabOrderItem(
                    originalIndex,
                    getString(tabs[originalIndex].labelRes),
                    tabs[originalIndex].iconRes,
                    visibility.getOrNull(originalIndex) == true,
                    tabs[originalIndex].fetchEnabled
                )
            }.toMutableList()

        return InfoTabOrderAdapter(items)
    }

    /** Wires up/down drag-to-reorder for [adapter] on [recycler]; returns the helper so it can be detached later. */
    private fun attachReorderTouchHelper(
        recycler: androidx.recyclerview.widget.RecyclerView,
        adapter: InfoTabOrderAdapter
    ): androidx.recyclerview.widget.ItemTouchHelper {
        val callback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
            0
        ) {
            // Inert rows sit at the bottom and stay there: dragging one would put a tab that cannot
            // appear ahead of tabs that can, and the order it landed in would be saved.
            override fun getMovementFlags(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int = if (adapter.isActionable(vh.bindingAdapterPosition)) {
                super.getMovementFlags(rv, vh)
            } else 0

            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                if (!adapter.isActionable(target.bindingAdapterPosition)) return false
                adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
        }
        return androidx.recyclerview.widget.ItemTouchHelper(callback).apply { attachToRecyclerView(recycler) }
    }

    /**
     * Persists [tabContext]'s order/visibility from the dialog's final state. Fetch-disabled tabs
     * were never shown in [adapter], so they're appended after the reordered visible ones in their
     * previous relative order - their position doesn't matter since they're filtered out of
     * [InfoTabContext.visibleOrderedTabs] regardless.
     */
    private fun saveInfoTabOrder(tabContext: InfoTabContext, adapter: InfoTabOrderAdapter) {
        val tabs = tabContext.tabs
        val finalItems = adapter.getItems()
        val visibleIds = finalItems.map { it.id }
        val hiddenIds = tabContext.savedOrder().filterNot { it in visibleIds }
        val newOrder = visibleIds + hiddenIds
        val newVisibility = MutableList(tabs.size) { i ->
            finalItems.find { it.id == i }?.visible ?: true
        }
        PrefManager.setVal(tabContext.orderPref, newOrder)
        PrefManager.setVal(tabContext.visibilityPref, newVisibility)
    }
}
