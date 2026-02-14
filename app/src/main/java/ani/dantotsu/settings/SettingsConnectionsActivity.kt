package ani.dantotsu.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsConnectionsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog

class SettingsConnectionsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsConnectionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.ComickEnabled, isChecked) }
            ),
            Settings(
                type = 2,
                name = getString(R.string.disable_mal),
                desc = getString(R.string.disable_mal_desc),
                icon = R.drawable.ic_myanimelist,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MalEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MalEnabled, isChecked) }
            ),
            Settings(
                type = 2,
                name = getString(R.string.disable_mangaupdates),
                desc = getString(R.string.disable_mangaupdates_desc),
                icon = R.drawable.ic_round_mangaupdates_24,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MangaUpdatesEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MangaUpdatesEnabled, isChecked) }
            ),
            Settings(
                type = 2,
                name = getString(R.string.disable_malsync),
                desc = getString(R.string.disable_malsync_desc),
                icon = R.drawable.ic_malsync,
                isChecked = PrefManager.getVal<Boolean>(PrefName.MalSyncInfoEnabled),
                switch = { isChecked, _ -> PrefManager.setVal(PrefName.MalSyncInfoEnabled, isChecked) },
                attachToSwitch = { b ->
                    // Show a small settings icon to configure whether MALSync checks manga, anime or both
                    b.settingsExtraIcon.visibility = View.VISIBLE
                    b.settingsExtraIcon.setImageDrawable(
                        ContextCompat.getDrawable(this, R.drawable.ic_round_settings_24)
                    )
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
                            show()
                        }
                    }
                }
            )
        )

        binding.connectionsRecyclerView.adapter = SettingsAdapter(settingsList)
        binding.connectionsRecyclerView.layoutManager = LinearLayoutManager(this)
    }
}
