package ani.dantotsu.connections.mangaupdates

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.ZoomOutPageTransformer
import ani.dantotsu.blurImage
import ani.dantotsu.getThemeColor
import ani.dantotsu.databinding.ActivityMediaBinding
import ani.dantotsu.initActivity
import ani.dantotsu.hideSystemBars
import ani.dantotsu.showSystemBars
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.media.manga.MangaReadFragment
import ani.dantotsu.media.novel.NovelReadFragment
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.getSerialized
import ani.dantotsu.Mapper
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import androidx.lifecycle.MutableLiveData
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import com.google.android.material.appbar.AppBarLayout
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickComic
import ani.dantotsu.media.MediaDetailsActivity
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import nl.joery.animatedbottombar.AnimatedBottomBar
import kotlin.math.abs
import java.util.Locale


class MUMediaDetailsActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {

    private lateinit var binding: ActivityMediaBinding
    private val model: MediaDetailsViewModel by viewModels()
    private var selected = 0
    private lateinit var navBar: AnimatedBottomBar
    internal lateinit var muMedia: MUMedia

    private var currentChapter: Int? = null
    private var detectedAniListId: Int? = null
    private var quickSearchTitles: List<String> = emptyList()
    private var detectedComickComic: ComickComic? = null
    private var useNovelReader: Boolean = false

    // Track last AniList suggestion shown to avoid repeat dialogs
    private var lastSuggestedAniListId: Int? = null

    private val muStatusNames = listOf("Reading", "Planning", "Completed", "Dropped", "Paused")

    private fun getListDisplayName(listId: Int): String? {
        if (listId in 0..4) return muStatusNames[listId]
        val titlesJson = PrefManager.getVal<String>(PrefName.MuCustomListTitles)
        if (titlesJson.isBlank()) return null
        return try {
            Mapper.json.decodeFromString<Map<String, String>>(titlesJson)[listId.toString()]
        } catch (_: Exception) { null }
    }

    private fun isNovelType(type: String?): Boolean {
        return type?.contains("novel", ignoreCase = true) == true
    }

    private fun progress() {
        val statusName = getListDisplayName(muMedia.listId)
        binding.mediaAddToList.text = statusName ?: getString(R.string.add_list)

        val white = getThemeColor(com.google.android.material.R.attr.colorOnBackground)
        val colorSecondary = getThemeColor(com.google.android.material.R.attr.colorSecondary)
        binding.mediaTotal.text = SpannableStringBuilder().apply {
            if (statusName != null) {
                append(getString(R.string.read_num))
                bold { color(colorSecondary) { append("${currentChapter ?: 0}") } }
                append(getString(R.string.chapters_out_of))
            } else {
                append(getString(R.string.chapters_total_of))
            }
            // latest_chapter from MU is the newest released chapter, not the series total.
            // Only show it when it is >= user progress (i.e. there are still chapters to read).
            val progress = currentChapter ?: 0
            val latest = muMedia.latestChapter
            if (latest != null && latest >= progress) {
                bold { color(white) { append("$latest") } }
                append(" / ")
            }
            bold { color(white) { append("??") } }
        }
        binding.mediaTotal.visibility = View.VISIBLE
    }

    private fun collectQuickSearchTitles(comickComic: ComickComic? = detectedComickComic): List<String> {
        val series = model.mangaUpdatesSeries.value
        val muSynonyms = MangaUpdates.synonymsCache[muMedia.id].orEmpty()
        val titles = mutableListOf<String>()

        fun addTitle(value: String?) {
            val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            titles.add(normalized)
        }

        addTitle(muMedia.title)
        addTitle(series?.title)
        series?.associated?.forEach { addTitle(it.title) }
        muSynonyms.forEach { addTitle(it) }

        addTitle(comickComic?.title)
        comickComic?.md_titles?.forEach { addTitle(it.title) }

        return titles.distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun launchAniListQuickSearch() {
        val candidates = quickSearchTitles.ifEmpty { collectQuickSearchTitles() }
        if (candidates.isEmpty()) return
        AniListQuickSearchDialogFragment
            .newInstance(ArrayList(candidates))
            .show(supportFragmentManager, "mu_anilist_quick_results")
    }

    private fun updateAniListButtonState(anilistId: Int?) {
        detectedAniListId = anilistId
        quickSearchTitles = collectQuickSearchTitles()
        binding.mediaAniList?.visibility = View.VISIBLE
        binding.mediaAniList?.setImageResource(
            if (anilistId != null) R.drawable.ic_anilist else R.drawable.ic_anilist_search_24
        )
        binding.mediaAniList?.setOnClickListener {
            val id = detectedAniListId
            if (id != null) {
                startActivity(
                    Intent(this, MediaDetailsActivity::class.java).apply {
                        putExtra("mediaId", id)
                    }
                )
            } else {
                launchAniListQuickSearch()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {

        // Apply the selected theme before inflating the layout
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)

        Log.d("MUMediaDetailsActivity", "onCreate called with intent: $intent")
        // Set up binding and content view first
        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle deep link: https://www.mangaupdates.com/series/{slugOrId}
        val action = intent?.action
        val data = intent?.data
        Log.d("MUMediaDetailsActivity", "Intent action: $action, data: $data")
        if (Intent.ACTION_VIEW == action && data != null && data.host == "www.mangaupdates.com" && data.pathSegments?.firstOrNull() == "series") {
            val slugOrId = data.pathSegments.getOrNull(1)
            Log.d("MUMediaDetailsActivity", "Deep link detected, slugOrId: $slugOrId, pathSegments: ${data.pathSegments}")
            // Show loading overlay, hide main content
            binding.loadingOverlay?.visibility = View.VISIBLE
            // Ensure loading bar uses colorPrimary
            binding.loadingProgressBar?.indeterminateTintList = android.content.res.ColorStateList.valueOf(getThemeColor(com.google.android.material.R.attr.colorPrimary))
            binding.mediaAppBar?.visibility = View.INVISIBLE
            binding.mediaViewPagerContainer?.visibility = View.INVISIBLE
            binding.mediaBottomBar?.visibility = View.INVISIBLE
            binding.mediaClose?.visibility = View.INVISIBLE
            binding.mediaCover?.visibility = View.INVISIBLE
            binding.commentMessageContainer?.visibility = View.INVISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                // Ensure we are logged in and have a token before fetching user lists
                MangaUpdates.getSavedToken()
                val details = MangaUpdates.getSeriesFromUrl(slugOrId)
                var muMedia: MUMedia? = null
                if (details != null) {
                    // Try to find user list entry for this series
                    val allUserLists = MangaUpdates.getAllUserLists()
                    val userEntry = allUserLists.values.flatten().find { it.id == details.seriesId }
                    muMedia = if (userEntry != null) {
                        // Use the user's list entry (with progress, listId, etc.)
                        userEntry.copy(
                            title = details.title,
                            url = "https://www.mangaupdates.com/series/$slugOrId",
                            coverUrl = details.image?.url?.original ?: details.image?.url?.thumb,
                            latestChapter = details.latest_chapter?.toInt(),
                                bayesianRating = details.bayesian_rating?.toDoubleOrNull(),
                                format = details.type
                        )
                    } else {
                        // Not in user list, use details only
                        MUMedia(
                            id = details.seriesId,
                            title = details.title,
                            url = "https://www.mangaupdates.com/series/$slugOrId",
                            coverUrl = details.image?.url?.original ?: details.image?.url?.thumb,
                            listId = -1,
                            userChapter = null,
                            userVolume = null,
                            latestChapter = details.latest_chapter?.toInt(),
                            bayesianRating = details.bayesian_rating?.toDoubleOrNull(),
                                priority = null,
                                format = details.type
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    if (muMedia != null) {
                        Log.d("MUMediaDetailsActivity", "MangaUpdates.getSeriesFromUrl returned details: $details, userEntry: ${muMedia.listId}")
                        // Hide loading, show content
                        binding.loadingOverlay?.visibility = View.GONE
                        binding.mediaAppBar?.visibility = View.VISIBLE
                        binding.mediaViewPagerContainer?.visibility = View.VISIBLE
                        binding.mediaBottomBar?.visibility = View.VISIBLE
                        binding.mediaClose?.visibility = View.VISIBLE
                        binding.mediaCover?.visibility = View.VISIBLE
                        binding.commentMessageContainer?.visibility = View.VISIBLE
                        launchMediaDetails(muMedia)
                    } else {
                        Log.e("MUMediaDetailsActivity", "No details found for slugOrId: $slugOrId, finishing activity.")
                        Toast.makeText(this@MUMediaDetailsActivity, "No details found for this MangaUpdates link.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            return
        } else {
            Log.w("MUMediaDetailsActivity", "Intent did not match deep link, attempting to getSerialized(muMedia)")
            muMedia = intent?.getSerialized("muMedia") ?: run {
                Log.e("MUMediaDetailsActivity", "muMedia is null, finishing activity.")
                Toast.makeText(this@MUMediaDetailsActivity, "No media data found, closing.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            // Show content immediately for normal launches
            binding.loadingOverlay?.visibility = View.GONE
            binding.mediaAppBar?.visibility = View.VISIBLE
            binding.mediaViewPagerContainer?.visibility = View.VISIBLE
            binding.mediaBottomBar?.visibility = View.VISIBLE
            binding.mediaClose?.visibility = View.VISIBLE
            binding.mediaCover?.visibility = View.VISIBLE
            binding.commentMessageContainer?.visibility = View.VISIBLE
            launchMediaDetails(muMedia)
        }
    }

    private fun launchMediaDetails(muMedia: MUMedia) {
        this.muMedia = muMedia
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        screenWidth = resources.displayMetrics.widthPixels.toFloat()
        navBar = binding.mediaBottomBar
        // Ensure the side rail is offset from system navigation insets and brought to front
        val rootViewForInsets = window.decorView.findViewById(android.R.id.content) as View
        ViewCompat.setOnApplyWindowInsetsListener(rootViewForInsets) { _, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val rightInset = if (isLandscape) navInsets.right else 0
            navBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                rightMargin = rightInset
                if (!isLandscape) bottomMargin = 0
            }
            val navZ = 8f * resources.displayMetrics.density
            navBar.translationZ = navZ

            if (!isLandscape) {
                // Portrait: extend nav bar height and add bottom padding to cover system nav
                val baseDp = 72f
                val basePx = (baseDp * resources.displayMetrics.density).toInt()
                val extra = navInsets.bottom
                navBar.updateLayoutParams {
                    height = basePx + extra
                }
                navBar.setPadding(0, 0, 0, extra)
                if (extra > 0) {
                    // Nav bar covers the system inset; avoid showing spacer
                    binding.mediaBottomInset.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = 0
                    }
                    binding.mediaBottomInset.visibility = View.GONE
                } else {
                    binding.mediaBottomInset.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = navBarHeight
                    }
                    binding.mediaBottomInset.visibility = View.VISIBLE
                }
            } else {
                val wideDp = 56
                val widePx = (wideDp * resources.displayMetrics.density).toInt()
                navBar.updateLayoutParams {
                    width = widePx
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                navBar.setPadding(0, 0, 0, 0)
                binding.mediaBottomInset.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = 0
                }
                binding.mediaBottomInset.visibility = View.GONE

                fun rotateTextIn(view: View) {
                    if (view is android.widget.TextView) {
                        view.rotation = -90f
                        view.pivotX = (view.width / 2).toFloat()
                        view.pivotY = (view.height / 2).toFloat()
                    } else if (view is ViewGroup) {
                        for (i in 0 until view.childCount) rotateTextIn(view.getChildAt(i))
                    }
                }
                for (i in 0 until navBar.childCount) {
                    rotateTextIn(navBar.getChildAt(i))
                }
            }
            // mediaBottomInset already handled above depending on extra inset; no-op here
            insets
        }
        useNovelReader = isNovelType(muMedia.format) || isNovelType(MUDetailsCache.get(muMedia.id)?.type)

        // Build a minimal Media object so MangaReadFragment can search for chapters.
        // We use the MU series ID (truncated to Int) as a synthetic Anilist-like ID.
        val media = Media(
            id = (muMedia.id and 0x7FFFFFFF).toInt(),
            name = muMedia.title,
            nameRomaji = muMedia.title ?: "",
            userPreferredName = muMedia.title ?: "",
            cover = muMedia.coverUrl,
            banner = muMedia.coverUrl,
            isAdult = false,
            manga = Manga(),
            format = if (useNovelReader) "NOVEL" else "MANGA",
            userProgress = muMedia.userChapter,
            muSeriesId = muMedia.id,
            muListId = muMedia.listId,
        )
        // Add MU URL to external links so ComickInfoFragment can validate Comick entries by MU link
        val muUrl = muMedia.url ?: "https://www.mangaupdates.com/series/${muMedia.id.toString(36)}"
        media.externalLinks.add(arrayListOf(null, muUrl))
        media.selected = model.loadSelected(media)
        model.setMedia(media)

        // Fetch full MU series data in background for the info tab
        val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
        val banner = if (bannerAnimations) binding.mediaBanner else binding.mediaBannerNoKen
        lifecycleScope.launch(Dispatchers.IO) {
            val details = MangaUpdates.getSeriesDetails(muMedia.id)
            if (details != null) {
                model.mangaUpdatesSeries.postValue(details)
                val detailsIndicateNovel = isNovelType(details.type)
                // Update the cover/banner if the list item didn't have a cover URL
                val coverUrl = details.image?.url?.original ?: details.image?.url?.thumb
                if (!coverUrl.isNullOrBlank() && muMedia.coverUrl == null) {
                    // Persist the cover on the Media object so MangaReadAdapter can use it
                    media.cover = coverUrl
                    media.banner = coverUrl
                    model.setMedia(media)
                    withContext(Dispatchers.Main) {
                        binding.mediaCoverImage.loadImage(coverUrl)
                        blurImage(banner, coverUrl)
                    }
                }
                if (detailsIndicateNovel && !useNovelReader) {
                    useNovelReader = true
                    media.format = "NOVEL"
                    media.selected = model.loadSelected(media)
                    model.setMedia(media)
                    withContext(Dispatchers.Main) {
                        val currentItem = binding.mediaViewPager.currentItem
                        binding.mediaViewPager.adapter = ViewPagerAdapter(supportFragmentManager, lifecycle, useNovelReader)
                        binding.mediaViewPager.setCurrentItem(currentItem, false)
                    }
                }
            }
        }

        // ViewPager bottom margin
        binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        val oldMargin = binding.mediaViewPager.marginBottom
        val showBottomInset =
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        AndroidBug5497Workaround.assistActivity(this) {
            if (it) {
                binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = 0
                }
                navBar.visibility = View.GONE
                binding.mediaBottomInset.visibility = View.GONE
            } else {
                binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = oldMargin
                }
                navBar.visibility = View.VISIBLE
                binding.mediaBottomInset.visibility = if (showBottomInset) View.VISIBLE else View.GONE
            }
        }

        // Use the actual navigation inset on the right in landscape
        val navRightInset = ViewCompat.getRootWindowInsets(rootViewForInsets)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            ?.right
            ?: navBarHeight
        navBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            rightMargin = if (showBottomInset) 0 else navRightInset
            bottomMargin = 0
        }
        val rootInsetsNow = ViewCompat.getRootWindowInsets(rootViewForInsets)
        val bottomNow = rootInsetsNow?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: navBarHeight
        binding.mediaBottomInset.updateLayoutParams<ViewGroup.LayoutParams> {
            height = if (showBottomInset && bottomNow == 0) navBarHeight else 0
        }
        binding.mediaBottomInset.visibility = if (showBottomInset && bottomNow == 0) View.VISIBLE else View.GONE

        // System bar insets
        binding.mediaBanner.updateLayoutParams { height += statusBarHeight }
        binding.mediaBannerNoKen.updateLayoutParams { height += statusBarHeight }
        binding.mediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.incognito.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaCollapsing.minimumHeight = statusBarHeight

        mMaxScrollSize = binding.mediaAppBar.totalScrollRange
        binding.mediaAppBar.addOnOffsetChangedListener(this)

        // Banner
        if (bannerAnimations) {
            val adi = AccelerateDecelerateInterpolator()
            val generator = RandomTransitionGenerator(
                (10000 + 15000 * (PrefManager.getVal(PrefName.AnimationSpeed) as Float)).toLong(),
                adi
            )
            binding.mediaBanner.setTransitionGenerator(generator)
        }
        blurImage(banner, muMedia.coverUrl)
        binding.mediaCoverImage.loadImage(muMedia.coverUrl)

        // Title
        binding.mediaTitle.translationX = -screenWidth
        binding.mediaTitle.text = muMedia.title ?: ""
        binding.mediaTitleCollapse.text = muMedia.title ?: ""
        binding.mediaStatus.text = ""

        // Progress state tracked locally so we can optimistically update UI
        currentChapter = muMedia.userChapter
        progress()
        binding.mediaAddToList.visibility = View.VISIBLE

        binding.mediaAddToList.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag("muListEditor") == null) {
                // Use the latest values from model.getMedia() if available
                val currentMedia = model.getMedia().value
                val updatedMuMedia = if (currentMedia != null && currentMedia.muSeriesId == muMedia.id) {
                    muMedia.copy(
                        listId = currentMedia.muListId ?: muMedia.listId,
                        userChapter = currentMedia.userProgress ?: muMedia.userChapter
                    )
                } else muMedia
                MUListEditorFragment.newInstance(updatedMuMedia).show(supportFragmentManager, "muListEditor")
            }
        }
        binding.mediaAddToList.setOnLongClickListener {
            val mediaId = (muMedia.id and 0x7FFFFFFF).toInt()
            PrefManager.setCustomVal("${mediaId}_progressDialog", true)
            snackString(getString(R.string.auto_update_reset))
            true
        }

        // Hide other Anilist-specific UI elements
        binding.mediaFav.visibility = View.GONE
        binding.incognito.visibility = View.GONE
        binding.commentInputLayout.visibility = View.GONE
        binding.mediaLanguageButton.visibility = View.GONE
        binding.mediaUnreadSource?.visibility = View.GONE

        binding.mediaAniList?.visibility = View.GONE

        val initialTitleCandidates = collectQuickSearchTitles().ifEmpty {
            listOfNotNull(muMedia.title).filter { it.isNotBlank() }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val slug = ComickApi.searchAndMatchComicByMuId(initialTitleCandidates, muMedia.id)
                val comickData = slug?.let { ComickApi.getComicDetails(it) }
                val comickComic = comickData?.comic
                val anilistId = comickComic?.links?.al?.toIntOrNull()
                withContext(Dispatchers.Main) {
                    detectedComickComic = comickComic
                    updateAniListButtonState(anilistId)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    updateAniListButtonState(null)
                }
            }
        }

        // Share button — shows a dialog with available links (MangaUpdates, Comick)
        binding.mediaShare.setOnClickListener {
            val linkOptions = mutableListOf<Triple<String, String, Int>>()

            val muUrl = muMedia.url ?: "https://www.mangaupdates.com/series/${muMedia.id.toString(36)}"
            linkOptions.add(Triple("MangaUpdates", muUrl, R.drawable.ic_round_mangaupdates_24))

            val comickSlug = model.comickSlug.value
            if (comickSlug != null) {
                linkOptions.add(Triple("Comick", "https://comick.io/comic/$comickSlug", R.drawable.ic_round_comick_24))
            }

            val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.MyPopup).create()
            val titleView = android.widget.TextView(this).apply {
                setText(R.string.share)
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                setPadding(32, 32, 32, 16)
                setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            }
            dialog.setCustomTitle(titleView)

            val iconLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(32, 16, 32, 32)
            }

            linkOptions.forEach { (label, link, iconRes) ->
                val iconButton = ImageView(this).apply {
                    setImageResource(iconRes)
                    val size = (56 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(16, 0, 16, 0) }
                    setPadding(12, 12, 12, 12)
                    setColorFilter(
                        ContextCompat.getColor(context, R.color.bg_opp),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    contentDescription = label
                    setOnClickListener {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                            putExtra(Intent.EXTRA_SUBJECT, "${muMedia.title ?: ""} - $label")
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share $label link"))
                        dialog.dismiss()
                    }
                }
                iconLayout.addView(iconButton)
            }

            dialog.setView(iconLayout)
            dialog.show()
        }

        binding.mediaClose.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // ViewPager
        val viewPager = binding.mediaViewPager
        viewPager.isUserInputEnabled = false
        viewPager.setPageTransformer(ZoomOutPageTransformer())
        viewPager.adapter = ViewPagerAdapter(supportFragmentManager, lifecycle, useNovelReader)

        // Bottom tab bar: Info + Read
        val infoTab = navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info)
        val readTab = navBar.createTab(
            if (useNovelReader) R.drawable.ic_round_book_24 else R.drawable.ic_round_import_contacts_24,
            R.string.read,
            R.id.read
        )
        navBar.addTab(infoTab)
        navBar.addTab(readTab)
        navBar.selectTabAt(selected)
        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                selected = newIndex
                viewPager.setCurrentItem(selected, true)
            }
        })

        // Observe Refresh.all() so that reader progress updates refresh the progress button
        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(false) }
        live.observe(this) { shouldRefresh ->
            if (shouldRefresh) {
                // Pick up the updated userProgress and muListId written by UpdateProgress.kt
                // or by MUListEditorFragment via model.setMedia()
                val currentMedia = model.getMedia().value
                if (currentMedia != null) {
                    currentMedia.userProgress?.let { currentChapter = it }
                    currentMedia.muListId?.let { this@MUMediaDetailsActivity.muMedia = muMedia.copy(listId = it) }
                    // Re-post so MangaReadFragment (which observes getMedia) re-renders
                    model.setMedia(currentMedia)
                }
                progress()
                live.postValue(false)
            }
        }

        // Observe Comick slug changes and suggest switching to AniList media if the Comick
        // entry contains an AniList link and the current media is a MangaUpdates entry.
        val model: MediaDetailsViewModel by viewModels()
        model.comickSlug.observe(this) { slug ->
            if (slug.isNullOrBlank()) return@observe
            // Avoid duplicate suggestions for the same AniList ID
            lifecycleScope.launch {
                try {
                    val comickData = withContext(Dispatchers.IO) { ComickApi.getComicDetails(slug) }
                    detectedComickComic = comickData?.comic
                    val anilistId = comickData?.comic?.links?.al?.toIntOrNull()
                    if (detectedAniListId == null) {
                        updateAniListButtonState(anilistId)
                    }
                    val currentAnilistId = model.getMedia().value?.id
                    val isMuMedia = model.getMedia().value?.muSeriesId != null
                    if (anilistId != null && isMuMedia && anilistId != currentAnilistId && lastSuggestedAniListId != anilistId) {
                        lastSuggestedAniListId = anilistId
                        AlertDialog.Builder(this@MUMediaDetailsActivity, R.style.MyPopup)
                            .setTitle(getString(R.string.switch_to_anilist_title).takeIf { resources.getIdentifier("switch_to_anilist_title", "string", this@MUMediaDetailsActivity.packageName) != 0 } ?: "Switch to AniList media?")
                            .setMessage(getString(R.string.switch_to_anilist_message).takeIf { resources.getIdentifier("switch_to_anilist_message", "string", this@MUMediaDetailsActivity.packageName) != 0 } ?: "This Comick entry is linked to an AniList media. Would you like to open the AniList media instead?")
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val intent = android.content.Intent(this@MUMediaDetailsActivity, ani.dantotsu.media.MediaDetailsActivity::class.java)
                                intent.putExtra("mediaId", anilistId)
                                startActivity(intent)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                } catch (_: Exception) {
                    // ignore errors silently
                }
            }
        }
    }

    override fun onDestroy() {
        Refresh.activity.remove(this.hashCode())
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::navBar.isInitialized) navBar.selectTabAt(selected)

        // Re-apply activity-level UI settings and insets
        initActivity(this)
        val rootView = window.decorView.findViewById(android.R.id.content) as View
        ViewCompat.requestApplyInsets(rootView)

        val windowInsets = ViewCompat.getRootWindowInsets(rootView)
        val navInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rightInset = if (isLandscape) navInsets?.right ?: 0 else 0
        if (::navBar.isInitialized) {
            navBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                rightMargin = rightInset
                if (!isLandscape) bottomMargin = 0
            }
        }

        val showBottomInset = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val bottomNow = navInsets?.bottom ?: 0
        if (showBottomInset) {
            if (bottomNow > 0) {
                // navBar already extends into the system inset; don't display extra spacer
                binding.mediaBottomInset.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = 0
                }
                binding.mediaBottomInset.visibility = View.GONE
            } else {
                binding.mediaBottomInset.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = navBarHeight
                }
                binding.mediaBottomInset.visibility = View.VISIBLE
            }
            binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarHeight
            }
        } else {
            binding.mediaBottomInset.updateLayoutParams<ViewGroup.LayoutParams> {
                height = 0
            }
            binding.mediaBottomInset.visibility = View.GONE
            binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = 0
            }
        }

        if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBarsUI)) this.hideSystemBars() else this.showSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val rootView = window.decorView.findViewById(android.R.id.content) as View
            ViewCompat.requestApplyInsets(rootView)
            if (!PrefManager.getVal<Boolean>(PrefName.ShowSystemBarsUI)) this.hideSystemBars() else this.showSystemBars()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val params = navBar.layoutParams as ViewGroup.MarginLayoutParams
        val showBottomInset = newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        val rootViewForInsets = window.decorView.findViewById(android.R.id.content) as View
        val navRightInset = ViewCompat.getRootWindowInsets(rootViewForInsets)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            ?.right
            ?: navBarHeight
        params.updateMargins(right = if (showBottomInset) 0 else navRightInset, bottom = 0)
        val rootInsetsNow = ViewCompat.getRootWindowInsets(rootViewForInsets)
        val bottomNow = rootInsetsNow?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: navBarHeight
        binding.mediaBottomInset.updateLayoutParams<ViewGroup.LayoutParams> {
            height = if (showBottomInset && bottomNow == 0) navBarHeight else 0
        }
        binding.mediaBottomInset.visibility = if (showBottomInset && bottomNow == 0) View.VISIBLE else View.GONE
    }

    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val useNovelReader: Boolean
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> MUMediaInfoContainerFragment()
            else -> if (useNovelReader) NovelReadFragment() else MangaReadFragment()
        }
    }

    // Collapsing toolbar state
    private var isCollapsed = false
    private val percent = 45
    private var mMaxScrollSize = 0
    private var screenWidth: Float = 0f

    override fun onOffsetChanged(appBar: AppBarLayout, i: Int) {
        if (mMaxScrollSize == 0) mMaxScrollSize = appBar.totalScrollRange
        val percentage = abs(i) * 100 / mMaxScrollSize

        binding.mediaCover.visibility =
            if (binding.mediaCover.scaleX == 0f) View.GONE else View.VISIBLE
        val duration = (200 * (PrefManager.getVal(PrefName.AnimationSpeed) as Float)).toLong()

        if (percentage >= percent && !isCollapsed) {
            isCollapsed = true
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", 0f).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", screenWidth).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", screenWidth).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", screenWidth).setDuration(duration).start()
            binding.mediaBanner.pause()
        }
        if (percentage <= percent && isCollapsed) {
            isCollapsed = false
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", -screenWidth).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", 0f).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", 0f).setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", 0f).setDuration(duration).start()
            if (PrefManager.getVal(PrefName.BannerAnimations)) binding.mediaBanner.resume()
        }
        if (percentage == 1 && model.scrolledToTop.value != false) model.scrolledToTop.postValue(false)
        if (percentage == 0 && model.scrolledToTop.value != true) model.scrolledToTop.postValue(true)
    }
}
