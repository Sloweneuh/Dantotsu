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
import ani.dantotsu.media.InfoTabContext
import ani.dantotsu.media.InfoTabEntry
import ani.dantotsu.media.InfoTabType
import ani.dantotsu.media.applyInfoTabRules
import ani.dantotsu.media.createTabFragment
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
    }

    private data class TabInfo(val entry: InfoTabEntry, val fragment: Fragment) {
        val iconRes get() = entry.iconRes

        /** The source's key; a custom tab, having no single source, has none. */
        val type get() = (entry as? InfoTabEntry.Builtin)?.type?.key
    }
    private var tabs: List<TabInfo> = emptyList()

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
        fun isMangaUpdates(entry: InfoTabEntry) =
            (entry as? InfoTabEntry.Builtin)?.type == InfoTabType.MANGAUPDATES
        tabs = tabContext.visibleEntries()
            .filter { !offline || isMangaUpdates(it) }
            .ifEmpty { tabContext.orderedEntries().filter { isMangaUpdates(it) } }
            .map { entry -> TabInfo(entry, tabContext.createTabFragment(entry)) }
        childFragmentManager.applyInfoTabRules(tabContext)

        val adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(
            childFragmentManager, viewLifecycleOwner.lifecycle
        ) {
            override fun getItemCount() = tabs.size
            override fun createFragment(position: Int): Fragment = tabs[position].fragment
        }

        binding.mediaInfoViewPager.adapter = adapter
        binding.mediaInfoViewPager.isUserInputEnabled = false
        // Only load adjacent pages to reduce unnecessary API calls
        binding.mediaInfoViewPager.offscreenPageLimit = 1

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

        if (tabs.size <= 1) {
            binding.mediaInfoTabLayout.visibility = View.GONE
            return
        }

        binding.mediaInfoTabLayout.visibility = View.VISIBLE

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

    private fun applyTabAlpha(model: MediaDetailsViewModel) {
        tabs.forEachIndexed { index, info ->
            val tab = binding.mediaInfoTabLayout.getTabAt(index) ?: return@forEachIndexed
            tab.view.alpha = when (info.type) {
                "comick" -> when {
                    model.comickSlug.value != null -> 1.0f
                    model.comickLoaded.value == true -> 0.4f
                    else -> 0.6f
                }
                "mangabaka" -> when {
                    (model.mangaBakaId.value ?: 0L) > 0L -> 1.0f
                    model.mangaBakaLoaded.value == true -> 0.4f
                    else -> 0.6f
                }
                "mal" -> when {
                    model.muMalId.value != null -> 1.0f
                    model.muMalLoaded.value == true -> 0.4f
                    else -> 0.6f
                }
                "kitsu" -> when {
                    !model.kitsuId.value.isNullOrBlank() -> 1.0f
                    model.kitsuLoaded.value == true -> 0.4f
                    else -> 0.6f
                }
                else -> 1.0f
            }
        }
    }
}
