package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivitySettingsMangaupdatesBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager

class MangaUpdatesSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsMangaupdatesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsMangaupdatesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsMangaUpdatesLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        binding.mangaUpdatesSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.mangaUpdatesRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 2,
                    name = getString(R.string.mu_tab_enabled),
                    desc = getString(R.string.mu_tab_enabled_desc),
                    icon = R.drawable.ic_round_mangaupdates_24,
                    isChecked = PrefManager.getVal<Boolean>(PrefName.MangaUpdatesEnabled),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.MangaUpdatesEnabled, isChecked)
                    }
                ),
                Settings(
                    type = 2,
                    name = getString(R.string.mu_list_fetch_enabled),
                    desc = getString(R.string.mu_list_fetch_enabled_desc),
                    icon = R.drawable.ic_round_mangaupdates_24,
                    isChecked = PrefManager.getVal<Boolean>(PrefName.MangaUpdatesListEnabled),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.MangaUpdatesListEnabled, isChecked)
                    }
                ),
                Settings(
                    type = 1,
                    name = getString(R.string.mu_custom_list_mapping),
                    desc = getString(R.string.mu_custom_list_mapping_desc),
                    icon = R.drawable.ic_round_mangaupdates_24,
                    onClick = {
                        startActivity(Intent(this, MUCustomListMappingActivity::class.java))
                    },
                    isActivity = true
                ),
            )
        )
        binding.mangaUpdatesRecyclerView.layoutManager = LinearLayoutManager(this)
    }
}
