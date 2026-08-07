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
import androidx.core.view.updatePadding
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
import ani.dantotsu.stripSpansOnPaste
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.tabs.TabLayoutMediator
import eu.kanade.domain.source.service.SourcePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding
    private var hasUpdates = false
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private var tabLayoutMediator: TabLayoutMediator? = null
    companion object {
        const val EXTRA_OPEN_SOURCE_ID = "open_source_id"
        const val EXTRA_OPEN_SOURCE_TYPE = "open_source_type"
    }

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

        binding.extensionBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        viewPager.offscreenPageLimit = 1

        // Check if there are any extension updates
        val preferences: SourcePreferences = Injekt.get()
        hasUpdates = preferences.animeExtensionUpdatesCount().get() > 0 ||
                     preferences.mangaExtensionUpdatesCount().get() > 0

        setupExtensionsPager()

        val searchView: AutoCompleteTextView = findViewById(R.id.searchViewText)
        searchView.stripSpansOnPaste()

        tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    searchView.setText("")
                    searchView.clearFocus()
                    tabLayout.clearFocus()

                    applyHeaderForTab(tab)

                    viewPager.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
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

        // Set initial tab if provided in intent
        val initialTab = intent.getIntExtra("tab", -1)
        val maxTab = if (hasUpdates) 6 else 5
        if (initialTab in 0..maxTab) {
            viewPager.setCurrentItem(initialTab, false)
        } else if (initialTab == 0 && !hasUpdates) {
            // If Updates tab was requested but no updates exist, go to first tab
            viewPager.setCurrentItem(0, false)
        }

        // The listener above only ever hears about *changes*, and the tab this opens on was
        // selected back in setupExtensionsPager — before there was a listener to hear it. So the
        // landing tab is the one tab whose header never got set up: see [applyHeaderForTab].
        syncHeaderForCurrentTab()

        // If requested, open a source preferences fragment directly
        val openId = intent.getLongExtra(EXTRA_OPEN_SOURCE_ID, -1L)
        val openType = intent.getStringExtra(EXTRA_OPEN_SOURCE_TYPE)
        if (openId != -1L && !openType.isNullOrEmpty()) {
            binding.fragmentExtensionsContainer.updatePadding(top = statusBarHeight)
            val changeUIVisibility: (Boolean) -> Unit = { show ->
                findViewById<ViewPager2>(R.id.viewPager).isVisible = show
                findViewById<TabLayout>(R.id.tabLayout).isVisible = show
                findViewById<TextInputLayout>(R.id.searchView).isVisible = show
                findViewById<TextView>(R.id.extensions).text = if (show) getString(R.string.extensions) else ""
                findViewById<FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
                // Coming back from source preferences restores the header the tab wants, not
                // everything: this opens on an Installed tab, which has no language picker.
                if (show) syncHeaderForCurrentTab()
                else binding.languageselect.isVisible = false
            }

            if (openType == "manga") {
                val tabIndex = if (hasUpdates) 3 else 2
                viewPager.setCurrentItem(tabIndex, false)
                val fragment = ani.dantotsu.settings.extensionprefs.MangaSourcePreferencesFragment().getInstance(openId) {
                    changeUIVisibility(true)
                }
                changeUIVisibility(false)
                supportFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_up, R.anim.slide_down, R.anim.slide_up, R.anim.slide_down)
                    .replace(R.id.fragmentExtensionsContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            } else if (openType == "anime") {
                val tabIndex = if (hasUpdates) 1 else 0
                viewPager.setCurrentItem(tabIndex, false)
                val fragment = ani.dantotsu.settings.extensionprefs.AnimeSourcePreferencesFragment().getInstance(openId) {
                    changeUIVisibility(true)
                }
                changeUIVisibility(false)
                supportFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_up, R.anim.slide_down, R.anim.slide_up, R.anim.slide_down)
                    .replace(R.id.fragmentExtensionsContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            }
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

            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
            val dp = resources.displayMetrics.density
            val onBgColor = com.google.android.material.color.MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorOnBackground
            )

            val scrollView = androidx.core.widget.NestedScrollView(this)
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bottom_sheet_background)
                val h = (24 * dp).toInt()
                setPadding(h, (20 * dp).toInt(), h, navBarHeight + (16 * dp).toInt())
            }

            container.addView(androidx.appcompat.widget.AppCompatTextView(this).apply {
                text = getString(R.string.language)
                textSize = 18f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@ExtensionsActivity, R.font.poppins_bold)
                setTextColor(onBgColor)
                setPadding(0, 0, 0, (12 * dp).toInt())
            })

            container.addView(View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.bottomMargin = (12 * dp).toInt() }
                alpha = 0.12f
                setBackgroundColor(onBgColor)
            })

            val radioGroup = android.widget.RadioGroup(this).apply {
                orientation = android.widget.RadioGroup.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            languageOptions.forEachIndexed { i, name ->
                com.google.android.material.radiobutton.MaterialRadioButton(this@ExtensionsActivity).apply {
                    id = i
                    text = name
                    textSize = 15f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(this@ExtensionsActivity, R.font.poppins_semi_bold)
                    isChecked = i == index
                    minHeight = (48 * dp).toInt()
                    layoutParams = android.widget.RadioGroup.LayoutParams(
                        android.widget.RadioGroup.LayoutParams.MATCH_PARENT,
                        android.widget.RadioGroup.LayoutParams.WRAP_CONTENT
                    )
                    radioGroup.addView(this)
                }
            }
            radioGroup.setOnCheckedChangeListener { _, which ->
                if (which >= 0 && which != index) {
                    PrefManager.setVal(
                        PrefName.LangSort,
                        LanguageMapper.Companion.Language.entries[which].code
                    )
                    val currentFragment =
                        supportFragmentManager.findFragmentByTag("f${viewPager.currentItem}")
                    if (currentFragment is SearchQueryHandler) {
                        currentFragment.notifyDataChanged()
                    }
                }
                sheet.dismiss()
            }

            container.addView(radioGroup)
            scrollView.addView(container)
            sheet.setContentView(scrollView)
            sheet.show()
        }
        binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
    }

    /**
     * Puts the header in the state the given tab wants: language and repository apply to browsing
     * an available-extensions list, and mean nothing on Updates or an Installed list.
     *
     * A function rather than the body of the tab listener because the listener is only told about
     * changes. Nothing had ever applied this to the tab the screen opens on, which left it showing
     * a language picker and a repository button — the latter without even a click listener, since
     * that is wired here too.
     */
    private fun applyHeaderForTab(tab: TabLayout.Tab?) {
        val label = tab?.text ?: return
        val browsing = !label.contains("Updates") && !label.contains("Installed")
        binding.languageselect.isVisible = browsing
        binding.openSettingsButton.isVisible = browsing
        when {
            label.contains("Anime") -> generateRepositoryButton(MediaType.ANIME)
            label.contains("Manga") -> generateRepositoryButton(MediaType.MANGA)
            label.contains("Novels") -> generateRepositoryButton(MediaType.NOVEL)
        }
    }

    /** Applies [applyHeaderForTab] to whichever tab is selected right now. Idempotent. */
    private fun syncHeaderForCurrentTab() =
        applyHeaderForTab(tabLayout.getTabAt(tabLayout.selectedTabPosition))

    private fun setupExtensionsPager() {
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

        tabLayoutMediator?.detach()
        tabLayoutMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
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
        }
        tabLayoutMediator?.attach()
    }

    fun onExtensionUpdatesFinished() {
        val preferences: SourcePreferences = Injekt.get()
        val stillHasUpdates = preferences.animeExtensionUpdatesCount().get() > 0 ||
                preferences.mangaExtensionUpdatesCount().get() > 0

        if (hasUpdates != stillHasUpdates) {
            hasUpdates = stillHasUpdates
            setupExtensionsPager()
        }

        val installedAnimeIndex = if (hasUpdates) 1 else 0
        viewPager.setCurrentItem(installedAnimeIndex, false)
        // Losing the Updates tab shifts every other tab down one, so the selected *position* can
        // stay put while the tab sitting there becomes a different one — and a position that
        // didn't change tells the listener nothing.
        syncHeaderForCurrentTab()
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
