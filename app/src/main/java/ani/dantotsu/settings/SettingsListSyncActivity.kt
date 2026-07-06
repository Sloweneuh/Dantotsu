package ani.dantotsu.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.databinding.ActivitySettingsListSyncBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager

class SettingsListSyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsListSyncBinding

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

        val settingsList = arrayListOf(
            Settings(
                type = 2,
                name = getString(R.string.mal_list_sync),
                desc = getString(R.string.mal_list_sync_desc),
                icon = R.drawable.ic_myanimelist,
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
                icon = R.drawable.ic_round_mangabaka_24,
                isChecked = PrefManager.getVal(PrefName.MangaBakaListSyncEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.MangaBakaListSyncEnabled, isChecked)
                },
                isVisible = MangaBaka.token != null,
            ),
        )

        binding.listSyncRecyclerView.adapter = SettingsAdapter(settingsList)
        binding.listSyncRecyclerView.layoutManager = LinearLayoutManager(this)
    }
}
