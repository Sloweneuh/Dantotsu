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
import ani.dantotsu.media.MangaBakaTagWeights
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
                type = 1,
                name = getString(R.string.mangabaka_tag_weight),
                desc = tagWeightDesc(),
                icon = R.drawable.ic_label_24,
                // The description carries the current choice, so it has to be re-read on every
                // bind - the `desc` above is fixed at build time and would come back stale once the
                // row is recycled. Hiding attachView again is deliberate: the adapter shows that
                // container for any row with an attach hook, and this row adds nothing to it.
                attach = { b ->
                    b.settingsDesc.text = tagWeightDesc()
                    b.attachView.visibility = View.GONE
                },
                onClick = { b ->
                    customAlertDialog().apply {
                        setTitle(getString(R.string.mangabaka_tag_weight))
                        singleChoiceItems(
                            MangaBakaTagWeights.choiceAdapter(this@SettingsConnectionsActivity),
                            MangaBakaTagWeights.defaultIndex(),
                        ) { which ->
                            PrefManager.setVal(PrefName.MangaBakaTagWeightFilter, which)
                            b.settingsDesc.text = tagWeightDesc()
                        }
                        show()
                    }
                },
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
                name = getString(R.string.disable_kitsu),
                desc = getString(R.string.disable_kitsu_desc),
                icon = R.drawable.ic_kitsu,
                isChecked = PrefManager.getVal<Boolean>(PrefName.KitsuInfoEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.KitsuInfoEnabled, isChecked) },
            ),
            Settings(
                type = 2,
                name = getString(R.string.disable_simkl),
                desc = getString(R.string.disable_simkl_desc),
                icon = R.drawable.ic_simkl,
                isChecked = PrefManager.getVal<Boolean>(PrefName.SimklInfoEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.SimklInfoEnabled, isChecked) },
            ),
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
                onClick = {
                    InfoTabOrderBottomSheet.newInstance()
                        .show(supportFragmentManager, InfoTabOrderBottomSheet.TAG)
                },
            ),
        )

        binding.connectionsRecyclerView.adapter = SettingsAdapter(settingsList)
        binding.connectionsRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    /** "MangaBaka tag lists start filtered to: <the option currently picked>". */
    private fun tagWeightDesc(): String = getString(
        R.string.mangabaka_tag_weight_desc,
        getString(MangaBakaTagWeights.options[MangaBakaTagWeights.defaultIndex()].label)
    )

    private fun showMalSyncExcludeDialog() {
        MediaExcludeBottomDialog.newInstance(
            PrefName.MalSyncExcludeList,
            getString(R.string.malsync_exclude_manage)
        ).show(supportFragmentManager, "malSyncExclude")
    }

}
