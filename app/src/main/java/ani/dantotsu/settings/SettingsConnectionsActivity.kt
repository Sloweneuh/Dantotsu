package ani.dantotsu.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
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
            Settings(
                type = 2,
                name = getString(R.string.disable_mangabaka),
                desc = getString(R.string.disable_mangabaka_desc),
                icon = R.drawable.ic_round_mangabaka_24,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MangaBakaInfoEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MangaBakaInfoEnabled, isChecked) },
            ),
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
                        // Show a single-choice dialog to pick mode
                        val options = arrayOf(
                            getString(R.string.malsync_checks_option_manga),
                            getString(R.string.malsync_checks_option_anime),
                            getString(R.string.malsync_checks_option_both)
                        )
                        val currentIndex = when (PrefManager.getVal<String>(PrefName.MalSyncCheckMode)) {
                            "manga" -> 0
                            "anime" -> 1
                            else -> 2
                        }

                        this@SettingsConnectionsActivity.customAlertDialog().apply {
                            setTitle(R.string.malsync_checks_dialog_title)
                            singleChoiceItems(options, currentIndex) { i ->
                                val newVal = when (i) {
                                    0 -> "manga"
                                    1 -> "anime"
                                    else -> "both"
                                }
                                PrefManager.setVal(PrefName.MalSyncCheckMode, newVal)
                                // Update UI text
                                b.settingsDesc.text = getString(
                                    R.string.malsync_checks_desc,
                                    options[i]
                                )
                            }
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
            )
            ,
            Settings(
                type = 1,
                name = getString(R.string.malsync_exclude_manage),
                desc = getString(R.string.malsync_exclude_manage_desc),
                icon = R.drawable.ic_malsync,
                onClick = { showMalSyncExcludeDialog() },
            ),
            Settings(
                type = 1,
                name = getString(R.string.customize_info_tabs),
                desc = getString(R.string.customize_info_tabs_desc),
                icon = R.drawable.ic_round_equal_24,
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
     * Connections disabled via their switch above are left out of the list entirely, since there's
     * nothing to show or reorder for them.
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
     * Builds one [InfoTabContext]'s fetch-enabled tabs, in saved order, as an [InfoTabOrderAdapter].
     * Tabs whose connection switch is off are left out entirely - there's nothing to show or
     * reorder for a connection that never fetches data.
     */
    private fun buildInfoTabAdapter(tabContext: InfoTabContext): InfoTabOrderAdapter {
        val tabs = tabContext.tabs
        val order = tabContext.savedOrder()
        val visibility = tabContext.savedVisibility()

        val items = order
            .filter { tabs[it].fetchEnabled }
            .map { originalIndex ->
                InfoTabOrderItem(
                    originalIndex,
                    getString(tabs[originalIndex].labelRes),
                    tabs[originalIndex].iconRes,
                    visibility.getOrNull(originalIndex) == true
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
            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
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
