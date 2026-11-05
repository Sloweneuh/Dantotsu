package ani.dantotsu.connections.mangaupdates

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.databinding.FragmentMediaInfoContainerBinding
import ani.dantotsu.media.InfoTabAlpha
import ani.dantotsu.media.InfoTabContext
import ani.dantotsu.media.InfoTabEntry
import ani.dantotsu.media.InfoTabPreview
import ani.dantotsu.media.InfoTabStore
import ani.dantotsu.media.InfoTabType
import ani.dantotsu.media.InfoTabsPagerAdapter
import ani.dantotsu.media.applyInfoTabRules
import ani.dantotsu.media.createTabFragment
import ani.dantotsu.media.customTabAlpha
import ani.dantotsu.media.customTabIsEmpty
import ani.dantotsu.media.initialTabKey
import ani.dantotsu.media.layoutSignature
import ani.dantotsu.media.longPressSource
import ani.dantotsu.media.runCustomTabLongPress
import ani.dantotsu.media.orderedEntries
import ani.dantotsu.media.visibleEntries
import ani.dantotsu.isOnline
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import ani.dantotsu.util.useSharedAxisTransitions
import ani.dantotsu.util.PagerTabsMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Container fragment for the Info bottom tab in [MUMediaDetailsActivity].
 * Mirrors the structure of [ani.dantotsu.media.MediaInfoFragment]:
 *  - First inner tab: MangaUpdates info ([MUMediaInfoFragment]) – always shown
 *  - Then the other sources and custom tabs the user enabled, in their order
 * If only one tab would appear the TabLayout is hidden.
 */
class MUMediaInfoContainerFragment : Fragment() {

    private var _binding: FragmentMediaInfoContainerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaInfoContainerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        pagerAdapter = null
    }

    private data class TabInfo(val entry: InfoTabEntry) {
        val iconRes get() = entry.iconRes

        /** The source's key; a custom tab, having no single source, has none. */
        val type get() = (entry as? InfoTabEntry.Builtin)?.type?.key
    }
    private var tabs: List<TabInfo> = emptyList()

    /** [tabs] with the custom tabs that turned out to have nothing for this title too. */
    private var allTabs: List<TabInfo> = emptyList()
    private var tabContext: InfoTabContext? = null
    private var pagerAdapter: InfoTabsPagerAdapter? = null
    private var builtFrom: String? = null

    /** The tabs as the settings have them now, offline leaving only what can still show. */
    private fun buildTabs(context: InfoTabContext) {
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) ||
                !isOnline(requireContext())
        fun isMangaUpdates(entry: InfoTabEntry) =
            (entry as? InfoTabEntry.Builtin)?.type == InfoTabType.MANGAUPDATES
        allTabs = context.visibleEntries()
            .filter { !offline || isMangaUpdates(it) }
            .ifEmpty { context.orderedEntries().filter { isMangaUpdates(it) } }
            .map { TabInfo(it) }
        tabs = allTabs
        builtFrom = context.layoutSignature()
    }

    /** Catches up with tabs edited in the settings, opened from this very page or anywhere else. */
    override fun onResume() {
        super.onResume()
        val context = tabContext ?: return
        val adapter = pagerAdapter ?: return
        if (context.layoutSignature() == builtFrom) return
        val shown = tabs.getOrNull(binding.mediaInfoViewPager.currentItem)?.entry?.key
        buildTabs(context)
        adapter.rebuild()
        binding.mediaInfoTabLayout.visibility = if (tabs.size <= 1) View.GONE else View.VISIBLE
        binding.mediaInfoViewPager.setCurrentItem(
            tabs.indexOfFirst { it.entry.key == shown }.coerceAtLeast(0), false
        )
        val model: MediaDetailsViewModel by activityViewModels()
        applyTabAlpha(model)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val model: MediaDetailsViewModel by activityViewModels()

        // Comick and MangaBaka tabs are pure network lookups, so offline show only the
        // MangaUpdates tab (which renders the downloaded basics).
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) ||
                !isOnline(requireContext())
        // The synthetic media the activity builds carries the format it decided on, which is what
        // picks between the manga and novel tab sets.
        val isNovel = model.getMedia().value?.let { InfoTabContext.isNovel(it) } ?: false
        val tabContext = InfoTabContext.forMangaUpdates(isNovel)
        this.tabContext = tabContext
        buildTabs(tabContext)
        childFragmentManager.applyInfoTabRules(tabContext)

        val adapter = InfoTabsPagerAdapter(
            this, { tabs.size }, { tabs[it].entry.key },
            { tabContext.createTabFragment(tabs[it].entry) },
        )
        pagerAdapter = adapter
        binding.mediaInfoViewPager.adapter = adapter
        binding.mediaInfoViewPager.isUserInputEnabled = false
        // Only load adjacent pages to reduce unnecessary API calls
        binding.mediaInfoViewPager.offscreenPageLimit = 1
        // Before anything else looks at the tabs: the first state leaves out those with nothing to show.
        if (savedInstanceState == null) {
            val start = tabs.indexOfFirst { it.entry.key == tabContext.initialTabKey(this) }
            if (start > 0) binding.mediaInfoViewPager.setCurrentItem(start, false)
        }
        if (InfoTabStore.preview == null) model.getMedia().observe(viewLifecycleOwner) { media ->
            media?.muSeriesId?.let { InfoTabPreview.remember(tabContext, it) }
        }

        // preloadExternalData isn't run in the MangaUpdates activity, so fetch the MangaBaka series
        // here (once, as soon as the media is available). The source route embeds the full object,
        // so this both drives tab dimming *before the tab is opened* and is reused by the info
        // fragment — observing (rather than reading .value once) avoids missing a media set later.
        // Gated only on the connection being enabled, not on whether the MangaBaka tab is
        // actually visible (checked further down, before the early return below): an AniList
        // equivalent must still be found and offered even when the user has hidden the tab.
        if (InfoTabType.MANGABAKA.fetchEnabled && !offline) {
            var mbStarted = false
            model.getMedia().observe(viewLifecycleOwner) { media ->
                if (media != null && !mbStarted && model.mangaBakaLoaded.value != true) {
                    mbStarted = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        val series = withContext(Dispatchers.IO) {
                            MangaBakaApi.getSeriesForMedia(media.muSeriesId, media.id, media.idMAL)
                        }
                        model.mangaBakaSeries.postValue(series)
                        model.mangaBakaId.postValue(series?.id)
                        model.mangaBakaLoaded.postValue(true)

                        val anilistId = series?.source?.anilist?.id
                        val mangaBakaTabVisible = tabs.any { it.type == InfoTabType.MANGABAKA.key }
                        if (anilistId != null && anilistId > 0 && !mangaBakaTabVisible &&
                            !model.mangaBakaEquivalentPromptShown
                        ) {
                            model.mangaBakaEquivalentPromptShown = true
                            showAnilistEquivalentDialog(anilistId)
                        }
                    }
                }
            }
        }

        // MangaUpdates series have no MAL id, so the MAL tab can't load anything until one is
        // resolved from a cross-source mapping. Kick that off as soon as the media is available so
        // the tab is usually ready by the time it's opened, and so its icon can be dimmed while it
        // isn't. Not needed offline — the tab list above drops everything but MangaUpdates then.
        if (InfoTabType.MAL.fetchEnabled && !offline) {
            var malStarted = false
            model.getMedia().observe(viewLifecycleOwner) { media ->
                val m = media ?: return@observe
                val muSeriesId = m.muSeriesId ?: return@observe
                if (!malStarted && model.muMalLoaded.value != true) {
                    malStarted = true
                    val titles = muMalSearchTitles(m)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val malId = withContext(Dispatchers.IO) {
                            resolveMuMalId(muSeriesId, titles, model.comickSlug.value)
                        }
                        model.muMalId.postValue(malId)
                        model.muMalLoaded.postValue(true)
                    }
                }
            }
        }

        // Set up even for a single tab, as an edit in the settings can bring the others back.
        binding.mediaInfoTabLayout.visibility = if (tabs.size <= 1) View.GONE else View.VISIBLE

        binding.mediaInfoViewPager.useSharedAxisTransitions()
        PagerTabsMediator(binding.mediaInfoTabLayout, binding.mediaInfoViewPager) { tab, position ->
            tab.setIcon(tabs[position].iconRes)

            // Long-press the tab icon to open the corresponding external page in browser
            tab.view.setOnLongClickListener {
                val media = model.getMedia().value
                val encoded = java.net.URLEncoder.encode(media?.userPreferredName ?: "", "utf-8").replace("+", "%20")
                val custom = (tabs.getOrNull(position)?.entry as? InfoTabEntry.Custom)?.tab
                if (custom != null && custom.longPressSource == null) {
                    return@setOnLongClickListener runCustomTabLongPress(tabContext, custom)
                }
                val url = when (custom?.longPressSource?.key ?: tabs.getOrNull(position)?.type) {
                    "mangaupdates" -> {
                        val muLink = model.mangaUpdatesLink.value ?: run {
                            media?.externalLinks?.firstOrNull { entry ->
                                entry.getOrNull(1)?.contains("mangaupdates", ignoreCase = true) == true ||
                                        entry.getOrNull(0)?.contains("mangaupdates", ignoreCase = true) == true
                            }?.getOrNull(1)
                        }
                        if (muLink != null && muLink.contains("mangaupdates", ignoreCase = true)) muLink
                        else "https://www.mangaupdates.com/series?search=$encoded"
                    }
                    "comick" -> {
                        val comickSlug = model.comickSlug.value
                        if (!comickSlug.isNullOrBlank()) "https://comick.dev/comic/$comickSlug"
                        else "https://comick.dev/search?q=$encoded"
                    }
                    "mal" -> {
                        val malId = model.muMalId.value
                        if (malId != null) "https://myanimelist.net/manga/$malId"
                        else "https://myanimelist.net/manga.php?q=$encoded&cat=manga"
                    }
                    "mangabaka" -> {
                        val id = model.mangaBakaId.value
                        if (id != null && id > 0) "https://mangabaka.org/$id"
                        else "https://mangabaka.org/search?q=$encoded"
                    }
                    "kitsu" -> {
                        val id = model.kitsuId.value
                        if (!id.isNullOrBlank()) "https://kitsu.io/manga/$id"
                        else "https://kitsu.io/search?query=$encoded"
                    }
                    else -> return@setOnLongClickListener false
                }
                // The browser, explicitly: a bare ACTION_VIEW would be caught by the app's own
                // deep-link filters and land right back on the page the user is already on.
                openLinkInBrowser(url)
                true
            }
        }.attach()

        // Dim the MAL / Comick / MangaBaka tab icons until their data is confirmed
        model.comickSlug.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.comickLoaded.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.mangaBakaId.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.mangaBakaLoaded.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.muMalId.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.muMalLoaded.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.kitsuId.observe(viewLifecycleOwner) { applyTabAlpha(model) }
        model.kitsuLoaded.observe(viewLifecycleOwner) { applyTabAlpha(model) }
    }

    private fun showAnilistEquivalentDialog(anilistId: Int) {
        val ctx = context ?: return
        ctx.customAlertDialog().apply {
            setTitle(getString(R.string.anilist_equivalent_found_title))
            setMessage(getString(R.string.anilist_equivalent_found_desc))
            setPosButton(R.string.view_on_anilist) {
                val intent = android.content.Intent(ctx, ani.dantotsu.media.MediaDetailsActivity::class.java)
                intent.putExtra("mediaId", anilistId)
                startActivity(intent)
            }
            setNegButton(R.string.cancel, null)
            show()
        }
    }

    /** How far [type] got with this title. */
    private fun sourceAlpha(type: InfoTabType, model: MediaDetailsViewModel): Float = when (type) {
        InfoTabType.COMICK -> when {
            model.comickSlug.value != null -> InfoTabAlpha.READY
            model.comickLoaded.value == true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
        InfoTabType.MANGABAKA -> when {
            (model.mangaBakaId.value ?: 0L) > 0L -> InfoTabAlpha.READY
            model.mangaBakaLoaded.value == true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
        InfoTabType.MAL -> when {
            model.muMalId.value != null -> InfoTabAlpha.READY
            model.muMalLoaded.value == true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
        InfoTabType.KITSU -> when {
            !model.kitsuId.value.isNullOrBlank() -> InfoTabAlpha.READY
            model.kitsuLoaded.value == true -> InfoTabAlpha.EMPTY
            else -> InfoTabAlpha.LOADING
        }
        else -> InfoTabAlpha.READY
    }

    /** See [ani.dantotsu.media.MediaInfoFragment]'s: the same, for this page's sources. */
    private fun hideEmptyTabs(model: MediaDetailsViewModel) {
        val adapter = pagerAdapter ?: return
        val context = tabContext ?: return
        val shown = tabs.getOrNull(binding.mediaInfoViewPager.currentItem)?.entry?.key
        val kept = allTabs.filter { info ->
            val custom = (info.entry as? InfoTabEntry.Custom)?.tab ?: return@filter true
            info.entry.key == shown || !context.customTabIsEmpty(custom) { sourceAlpha(it, model) }
        }
        if (kept.map { it.entry.key } == tabs.map { it.entry.key }) return
        tabs = kept
        adapter.removed()
        binding.mediaInfoTabLayout.visibility = if (tabs.size <= 1) View.GONE else View.VISIBLE
        binding.mediaInfoViewPager.setCurrentItem(
            tabs.indexOfFirst { it.entry.key == shown }.coerceAtLeast(0), false
        )
    }

    private fun applyTabAlpha(model: MediaDetailsViewModel) {
        hideEmptyTabs(model)
        val context = tabContext ?: return
        tabs.forEachIndexed { index, info ->
            val tab = binding.mediaInfoTabLayout.getTabAt(index) ?: return@forEachIndexed
            tab.view.alpha = when (val entry = info.entry) {
                is InfoTabEntry.Custom -> context.customTabAlpha(entry.tab) { sourceAlpha(it, model) }
                is InfoTabEntry.Builtin -> sourceAlpha(entry.type, model)
            }
        }
    }
}
