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
import ani.dantotsu.loadImage
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsViewModel
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.media.manga.MangaReadFragment
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
import nl.joery.animatedbottombar.AnimatedBottomBar
import kotlin.math.abs


class MUMediaDetailsActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {

    private lateinit var binding: ActivityMediaBinding
    private val model: MediaDetailsViewModel by viewModels()
    private var selected = 0
    private lateinit var navBar: AnimatedBottomBar
    internal lateinit var muMedia: MUMedia

    private var currentChapter: Int? = null

    private val muStatusNames = listOf("Reading", "Planning", "Completed", "Dropped", "Paused")

    private fun getListDisplayName(listId: Int): String? {
        if (listId in 0..4) return muStatusNames[listId]
        val titlesJson = PrefManager.getVal<String>(PrefName.MuCustomListTitles)
        if (titlesJson.isBlank()) return null
        return try {
            Mapper.json.decodeFromString<Map<String, String>>(titlesJson)[listId.toString()]
        } catch (_: Exception) { null }
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
                            bayesianRating = details.bayesian_rating?.toDoubleOrNull()
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
                            priority = null
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
            format = "MANGA",
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
            }
        }

        // ViewPager bottom margin
        binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        val oldMargin = binding.mediaViewPager.marginBottom
        AndroidBug5497Workaround.assistActivity(this) {
            if (it) {
                binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = 0
                }
                navBar.visibility = View.GONE
            } else {
                binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = oldMargin
                }
                navBar.visibility = View.VISIBLE
            }
        }

        // NavBar padding adjustments for landscape
        val navBarRightMargin =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) navBarHeight else 0
        val navBarBottomMargin =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else navBarHeight
        navBar.setPadding(
            navBar.paddingLeft,
            navBar.paddingTop,
            navBar.paddingRight + navBarRightMargin,
            navBar.paddingBottom
        )
        navBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarBottomMargin }

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
        viewPager.adapter = ViewPagerAdapter(supportFragmentManager, lifecycle)

        // Bottom tab bar: Info + Read
        val infoTab = navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info)
        val readTab = navBar.createTab(
            R.drawable.ic_round_import_contacts_24,
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
    }

    override fun onDestroy() {
        Refresh.activity.remove(this.hashCode())
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::navBar.isInitialized) navBar.selectTabAt(selected)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rightMargin =
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) navBarHeight else 0
        val bottomMargin =
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 0 else navBarHeight
        val params = navBar.layoutParams as ViewGroup.MarginLayoutParams
        params.updateMargins(right = rightMargin, bottom = bottomMargin)
    }

    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> MUMediaInfoContainerFragment()
            else -> MangaReadFragment()
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
