package ani.dantotsu.settings

import ani.dantotsu.util.capContentWidth
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityExtensionsBinding
import ani.dantotsu.dismissKeyboard
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.LanguageMapper
import ani.dantotsu.parsers.ParserTestActivity
import ani.dantotsu.parsers.novel.lnreader.LNReaderPluginManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.choiceBottomSheet
import ani.dantotsu.statusBarHeight
import ani.dantotsu.stripSpansOnPaste
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.useSharedAxisTransitions
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import ani.dantotsu.util.PagerTabsMediator
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ani.dantotsu.snackString
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class ExtensionsActivity : AppCompatActivity() {
    lateinit var binding: ActivityExtensionsBinding
    private var hasUpdates = false
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private var tabLayoutMediator: PagerTabsMediator? = null
    companion object {
        const val EXTRA_OPEN_SOURCE_ID = "open_source_id"
        const val EXTRA_OPEN_SOURCE_TYPE = "open_source_type"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityExtensionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        capContentWidth()
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
        viewPager.useSharedAxisTransitions()
        viewPager.offscreenPageLimit = 1

        // From the list the Updates tab itself shows, not the stored counts: those could be stale,
        // and counted light-novel plugin updates the tab has no place for, so it opened empty.
        hasUpdates = ExtensionUpdateRunner.pendingUpdates().isNotEmpty()

        setupExtensionsPager()
        watchForUpdates()

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
        // Positions as callers know them, counting the Updates tab: shifted down one without it.
        val initialTab = intent.getIntExtra("tab", -1)
        if (initialTab in 0..Page.entries.lastIndex) {
            viewPager.setCurrentItem(pages.indexOf(Page.entries[initialTab]).coerceAtLeast(0), false)
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
                // Hiding the field behind the preferences fragment has to take the query and the
                // keyboard with it, or the extension list stays filtered by a search bar that
                // isn't on screen any more.
                if (!show) {
                    searchView.setText("")
                    searchView.dismissKeyboard()
                }
                findViewById<TextInputLayout>(R.id.searchView).isVisible = show
                findViewById<TextView>(R.id.extensions).text = if (show) getString(R.string.extensions) else ""
                findViewById<FrameLayout>(R.id.fragmentExtensionsContainer).isGone = show
                // Coming back from source preferences restores the header the tab wants, not
                // everything: this opens on an Installed tab, which has no language picker.
                if (show) syncHeaderForCurrentTab()
                else binding.languageselect.isVisible = false
            }

            if (openType == "manga") {
                viewPager.setCurrentItem(pages.indexOf(Page.INSTALLED_MANGA), false)
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
                viewPager.setCurrentItem(pages.indexOf(Page.INSTALLED_ANIME), false)
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
                val currentFragment = currentPageFragment()
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
                }
            val listOrder: String = PrefManager.getVal(PrefName.LangSort)
            val index = LanguageMapper.Companion.Language.entries.toTypedArray()
                .indexOfFirst { it.code == listOrder }

            choiceBottomSheet(getString(R.string.language), languageOptions, index) { which ->
                PrefManager.setVal(
                    PrefName.LangSort,
                    LanguageMapper.Companion.Language.entries[which].code
                )
                val currentFragment = currentPageFragment()
                if (currentFragment is SearchQueryHandler) {
                    currentFragment.notifyDataChanged()
                }
            }
        }
        binding.checkUpdatesButton.setOnClickListener { checkForUpdates() }
        binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
    }

    /**
     * Asks every repository again for what's new, rather than waiting for the next scheduled
     * check, and says what it found. The Updates tab follows by itself ([watchForUpdates]).
     */
    private fun checkForUpdates() {
        binding.checkUpdatesButton.isEnabled = false
        snackString(getString(R.string.checking_extension_updates))
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { coroutineScope {
                listOf(
                    async { runCatching { Injekt.get<eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager>().findAvailableExtensions() }.isSuccess },
                    async { runCatching { Injekt.get<eu.kanade.tachiyomi.extension.manga.MangaExtensionManager>().findAvailableExtensions() }.isSuccess },
                    async { runCatching { Injekt.get<ani.dantotsu.parsers.novel.NovelExtensionManager>().findAvailableExtensions() }.isSuccess },
                    async { runCatching { Injekt.get<LNReaderPluginManager>().findAvailablePlugins() }.isSuccess },
                ).map { it.await() }.all { it }
            } }
            binding.checkUpdatesButton.isEnabled = true
            val count = ExtensionUpdateRunner.pendingUpdates().size
            snackString(
                when {
                    count > 0 -> resources.getQuantityString(R.plurals.extension_updates_found, count, count)
                    ok -> getString(R.string.extensions_up_to_date)
                    else -> getString(R.string.update_check_failed)
                }
            )
        }
    }

    /**
     * The Updates tab comes and goes as updates do — a repository check finishing while the screen
     * is open, or the last update installed — following the very list it shows.
     */
    private fun watchForUpdates() {
        val flows = listOf(
            Injekt.get<eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager>().installedExtensionsFlow,
            Injekt.get<eu.kanade.tachiyomi.extension.manga.MangaExtensionManager>().installedExtensionsFlow,
            Injekt.get<ani.dantotsu.parsers.novel.NovelExtensionManager>().installedExtensionsFlow,
        )
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(flows) { ExtensionUpdateRunner.pendingUpdates().isNotEmpty() }
                .collect { now -> if (now != hasUpdates) showUpdatesTab(now) }
        }
    }

    /** Adds or drops the Updates tab, staying on the page that was showing. */
    private fun showUpdatesTab(show: Boolean) {
        val showing = pages.getOrNull(viewPager.currentItem)
        hasUpdates = show
        setupExtensionsPager()
        val at = showing?.let { pages.indexOf(it) }?.takeIf { it >= 0 } ?: pages.indexOf(Page.INSTALLED_ANIME)
        viewPager.setCurrentItem(at, false)
        syncHeaderForCurrentTab()
    }

    /**
     * The pages, Updates only while there are updates. Each keeps its own id whatever its position,
     * so the pager never hands one page's list to another: it identified them by position, and
     * with the Updates tab gone (or the screen recreated without it) position 0 brought the
     * remembered Updates page back under "Installed Anime", and the other way round.
     */
    private enum class Page { UPDATES, INSTALLED_ANIME, ANIME, INSTALLED_MANGA, MANGA, INSTALLED_NOVELS, NOVELS }

    private val pages: List<Page>
        get() = if (hasUpdates) Page.entries else Page.entries - Page.UPDATES

    /** The fragment of the page showing: tagged "f" and its id by the pager. */
    private fun currentPageFragment() =
        pages.getOrNull(viewPager.currentItem)?.let { supportFragmentManager.findFragmentByTag("f${it.ordinal}") }

    /**
     * Puts the header in the state the given tab wants: language and repository apply to browsing
     * an available-extensions list, and mean nothing on Updates or an Installed list; checking for
     * updates is the other way round.
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
        // Updates are about what's installed; on a browsing tab its button only crowds the title
        // into wrapping next to the language and repository buttons.
        binding.checkUpdatesButton.isVisible = !browsing
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
            override fun getItemCount(): Int = pages.size

            override fun getItemId(position: Int): Long = pages[position].ordinal.toLong()

            override fun containsItem(itemId: Long): Boolean = pages.any { it.ordinal.toLong() == itemId }

            override fun createFragment(position: Int): Fragment = when (pages[position]) {
                Page.UPDATES -> ExtensionUpdatesFragment()
                Page.INSTALLED_ANIME -> InstalledAnimeExtensionsFragment()
                Page.ANIME -> AnimeExtensionsFragment()
                Page.INSTALLED_MANGA -> InstalledMangaExtensionsFragment()
                Page.MANGA -> MangaExtensionsFragment()
                Page.INSTALLED_NOVELS -> InstalledNovelExtensionsFragment()
                Page.NOVELS -> NovelExtensionsFragment()
            }
        }

        tabLayoutMediator?.detach()
        tabLayoutMediator = PagerTabsMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (pages.getOrNull(position)) {
                Page.UPDATES -> "Updates"
                Page.INSTALLED_ANIME -> "Installed Anime"
                Page.ANIME -> "Available Anime"
                Page.INSTALLED_MANGA -> "Installed Manga"
                Page.MANGA -> "Available Manga"
                Page.INSTALLED_NOVELS -> "Installed Novels"
                Page.NOVELS -> "Available Novels"
                null -> null
            }
        }
        tabLayoutMediator?.attach()
    }

    fun onExtensionUpdatesFinished() {
        val stillHasUpdates = ExtensionUpdateRunner.pendingUpdates().isNotEmpty()
        if (hasUpdates != stillHasUpdates) {
            hasUpdates = stillHasUpdates
            setupExtensionsPager()
        }

        viewPager.setCurrentItem(pages.indexOf(Page.INSTALLED_ANIME), false)
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

                // Novel sources come in two kinds and the user should not have to know which a
                // URL is, so both lists are shown together and [addNovelRepo] sorts it out.
                MediaType.NOVEL -> {
                    PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos) +
                        PrefManager.getVal<Set<String>>(PrefName.LNReaderRepos)
                }
            }
            val onAdd: (String, MediaType) -> Unit =
                if (type == MediaType.NOVEL) { url, _ -> addNovelRepo(url) }
                else AddRepositoryBottomSheet::addRepo
            val onRemove: (String, MediaType) -> Unit =
                if (type == MediaType.NOVEL) { url, _ -> removeNovelRepo(url) }
                else AddRepositoryBottomSheet::removeRepo

            AddRepositoryBottomSheet.newInstance(
                type,
                repos.toList(),
                onAdd,
                onRemove,
            ).show(supportFragmentManager, "add_repo")
        }
    }

    /**
     * Files a novel repository under whichever kind it turns out to be.
     *
     * An LNReader index is a JSON array of plugin records; an extension repository index is an
     * object. Probing is worth the request: the two are indistinguishable by URL, and filing one
     * under the wrong list means it silently lists nothing.
     */
    private fun addNovelRepo(url: String) {
        lifecycleScope.launch {
            val manager = Injekt.get<LNReaderPluginManager>()
            val isPluginIndex = manager.looksLikePluginIndex(url)
            if (isPluginIndex) {
                val repos: Set<String> = PrefManager.getVal(PrefName.LNReaderRepos)
                PrefManager.setVal(PrefName.LNReaderRepos, repos + url)
                manager.findAvailablePlugins()
            } else {
                AddRepositoryBottomSheet.addRepo(url, MediaType.NOVEL)
            }
        }
    }

    private fun removeNovelRepo(url: String) {
        val plugins: Set<String> = PrefManager.getVal(PrefName.LNReaderRepos)
        if (url in plugins) {
            PrefManager.setVal(PrefName.LNReaderRepos, plugins - url)
            lifecycleScope.launch { Injekt.get<LNReaderPluginManager>().findAvailablePlugins() }
        } else {
            AddRepositoryBottomSheet.removeRepo(url, MediaType.NOVEL)
        }
    }

}

interface SearchQueryHandler {
    fun updateContentBasedOnQuery(query: String?)
    fun notifyDataChanged()
}
