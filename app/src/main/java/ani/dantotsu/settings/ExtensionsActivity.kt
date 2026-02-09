package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityExtensionsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import eu.kanade.domain.source.service.SourcePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding
    private var hasUpdates = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)
        AndroidBug5497Workaround.assistActivity(this) {
            if (it) {
                binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = statusBarHeight
                }
            } else {
                binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = statusBarHeight + navBarHeight
                }
            }
        }

        binding.searchView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = statusBarHeight + navBarHeight
        }

        binding.testButton.setOnClickListener {
            ContextCompat.startActivity(
                this,
                Intent(this, ParserTestActivity::class.java),
                null
            )
        }

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.offscreenPageLimit = 1

        // Check if there are any extension updates
        val preferences: SourcePreferences = Injekt.get()
        hasUpdates = preferences.animeExtensionUpdatesCount().get() > 0 ||
                     preferences.mangaExtensionUpdatesCount().get() > 0

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = if (hasUpdates) 7 else 6

            override fun createFragment(position: Int): Fragment {
                return if (hasUpdates) {
                    when (position) {
                        0 -> ExtensionUpdatesFragment()
                        1 -> InstalledAnimeExtensionsFragment()
                        2 -> AnimeExtensionsFragment()
                        3 -> InstalledMangaExtensionsFragment()
                        4 -> MangaExtensionsFragment()
                        5 -> InstalledNovelExtensionsFragment()
                        6 -> NovelExtensionsFragment()
                        else -> ExtensionUpdatesFragment()
                    }
                } else {
                    when (position) {
                        0 -> InstalledAnimeExtensionsFragment()
                        1 -> AnimeExtensionsFragment()
                        2 -> InstalledMangaExtensionsFragment()
                        3 -> MangaExtensionsFragment()
                        4 -> InstalledNovelExtensionsFragment()
                        5 -> NovelExtensionsFragment()
                        else -> InstalledAnimeExtensionsFragment()
                    }
                }
            }

        }

        val searchView: AutoCompleteTextView = findViewById(R.id.searchViewText)

        tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    searchView.setText("")
                    searchView.clearFocus()
                    tabLayout.clearFocus()

                    // Hide language select and repo button for Updates and Installed tabs
                    if (tab.text?.contains("Updates") == true || tab.text?.contains("Installed") == true) {
                        binding.languageselect.visibility = View.GONE
                        binding.openSettingsButton.visibility = View.GONE
                    } else {
                        binding.languageselect.visibility = View.VISIBLE
                        binding.openSettingsButton.visibility = View.VISIBLE
                    }

                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }

                    if (tab.text?.contains("Anime") == true) {
                        generateRepositoryButton(MediaType.ANIME)
                    }
                    if (tab.text?.contains("Manga") == true) {
                        generateRepositoryButton(MediaType.MANGA)
                    }
                    if (tab.text?.contains("Novels") == true) {
                        generateRepositoryButton(MediaType.NOVEL)
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {
                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    tabLayout.clearFocus()
                }

                override fun onTabReselected(tab: TabLayout.Tab) {
                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    // Do nothing
                }
            }
        )

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (hasUpdates) {
                when (position) {
                    0 -> "Updates"
                    1 -> "Installed Anime"
                    2 -> "Available Anime"
                    3 -> "Installed Manga"
                    4 -> "Available Manga"
                    5 -> "Installed Novels"
                    6 -> "Available Novels"
                    else -> null
                }
            } else {
                when (position) {
                    0 -> "Installed Anime"
                    1 -> "Available Anime"
                    2 -> "Installed Manga"
                    3 -> "Available Manga"
                    4 -> "Installed Novels"
                    5 -> "Available Novels"
                    else -> null
                }
            }
        }.attach()

        // Set initial tab if provided in intent
        val initialTab = intent.getIntExtra("tab", -1)
        val maxTab = if (hasUpdates) 6 else 5
        if (initialTab in 0..maxTab) {
            viewPager.setCurrentItem(initialTab, false)
        } else if (initialTab == 0 && !hasUpdates) {
            // If Updates tab was requested but no updates exist, go to first tab
            viewPager.setCurrentItem(0, false)
        }


        searchView.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentFragment =
                    supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                if (currentFragment is SearchQueryHandler) {
                    currentFragment.updateContentBasedOnQuery(s?.toString()?.trim())
                }
            }
        })

        initActivity(this)
        binding.languageselect.setOnClickListener {
            val languageOptions =
                LanguageMapper.Companion.Language.entries.map { entry ->
                    entry.name.lowercase().replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }.toTypedArray()
            val listOrder: String = PrefManager.getVal(PrefName.LangSort)
            val index = LanguageMapper.Companion.Language.entries.toTypedArray()
                .indexOfFirst { it.code == listOrder }
            customAlertDialog().apply {
                setTitle(getString(R.string.language))
                singleChoiceItems(languageOptions, index) { selected ->
                    PrefManager.setVal(
                        PrefName.LangSort,
                        LanguageMapper.Companion.Language.entries[selected].code
                    )
                    val currentFragment =
                        supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                    if (currentFragment is SearchQueryHandler) {
                        currentFragment.notifyDataChanged()
                    }
                }
                show()
            }
        }
        binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
    }

    private fun generateRepositoryButton(type: MediaType) {
        binding.openSettingsButton.setOnClickListener {
            val repos: Set<String> = when (type) {
                MediaType.ANIME -> {
                    PrefManager.getVal(PrefName.AnimeExtensionRepos)
                }

                MediaType.MANGA -> {
                    PrefManager.getVal(PrefName.MangaExtensionRepos)
                }

                MediaType.NOVEL -> {
                    PrefManager.getVal(PrefName.NovelExtensionRepos)
                }
            }
            AddRepositoryBottomSheet.newInstance(
                type,
                repos.toList(),
                AddRepositoryBottomSheet::addRepo,
                AddRepositoryBottomSheet::removeRepo

            ).show(supportFragmentManager, "add_repo")
        }
    }
}

interface SearchQueryHandler {
    fun updateContentBasedOnQuery(query: String?)
    fun notifyDataChanged()
}
