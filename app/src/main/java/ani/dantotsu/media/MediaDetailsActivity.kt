package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.GesturesListener
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.ZoomOutPageTransformer
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMediaBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.isOnline
import ani.dantotsu.loadImage
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.media.comments.CommentsFragment
import ani.dantotsu.media.manga.MangaReadFragment
import ani.dantotsu.media.novel.NovelReadFragment
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.LauncherWrapper
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.joery.animatedbottombar.AnimatedBottomBar

class MediaDetailsActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {
    lateinit var launcher: LauncherWrapper
    lateinit var binding: ActivityMediaBinding
    private val scope = lifecycleScope
    private val model: MediaDetailsViewModel by viewModels()
    var selected = 0
    lateinit var navBar: AnimatedBottomBar
    var anime = true
    private var adult = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        var media: Media = intent.getSerialized("media") ?: mediaSingleton ?: emptyMedia()
        val id = intent.getIntExtra("mediaId", -1)
        if (id != -1) {
            runBlocking {
                withContext(Dispatchers.IO) {
                    media = Anilist.query.getMedia(id, false) ?: emptyMedia()
                }
            }
        }
        if (media.name == "No media found") {
            snackString(media.name)
            onBackPressedDispatcher.onBackPressed()
            return
        }
        val contract = ActivityResultContracts.OpenDocumentTree()
        launcher = LauncherWrapper(this, contract)

        mediaSingleton = null
        ThemeManager(this).applyTheme(MediaSingleton.bitmap)
        initActivity(this)
        MediaSingleton.bitmap = null

        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        screenWidth = resources.displayMetrics.widthPixels.toFloat()
        navBar = binding.mediaBottomBar

        // Ui init

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
        val navBarRightMargin =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        navBarHeight
                else 0
        val navBarBottomMargin =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 0
                else navBarHeight

        navBar.setPadding(
                navBar.paddingLeft,
                navBar.paddingTop,
                navBar.paddingRight + navBarRightMargin,
                navBar.paddingBottom
        )
        navBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarBottomMargin
        }
        binding.mediaBanner.updateLayoutParams { height += statusBarHeight }
        binding.mediaBannerNoKen.updateLayoutParams { height += statusBarHeight }
        binding.mediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin += statusBarHeight
        }
        binding.incognito.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin += statusBarHeight
        }
        binding.mediaCollapsing.minimumHeight = statusBarHeight

        binding.mediaTitle.isSelected = true

        mMaxScrollSize = binding.mediaAppBar.totalScrollRange
        binding.mediaAppBar.addOnOffsetChangedListener(this)

        binding.mediaClose.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
        if (bannerAnimations) {
            val adi = AccelerateDecelerateInterpolator()
            val generator =
                    RandomTransitionGenerator(
                            (10000 +
                                            15000 *
                                                    ((PrefManager.getVal(PrefName.AnimationSpeed) as
                                                            Float)))
                                    .toLong(),
                            adi
                    )
            binding.mediaBanner.setTransitionGenerator(generator)
        }
        val banner = if (bannerAnimations) binding.mediaBanner else binding.mediaBannerNoKen
        val viewPager = binding.mediaViewPager
        viewPager.isUserInputEnabled = false
        viewPager.setPageTransformer(ZoomOutPageTransformer())

        val isDownload = intent.getBooleanExtra("download", false)
        media.selected = model.loadSelected(media, isDownload)

        binding.mediaCoverImage.loadImage(media.cover)
        binding.mediaCoverImage.setOnLongClickListener {
            val coverTitle = getString(R.string.cover, media.userPreferredName)
            ImageViewDialog.newInstance(this, coverTitle, media.cover)
        }

        blurImage(banner, media.banner ?: media.cover)
        val gestureDetector =
                GestureDetector(
                        this,
                        object : GesturesListener() {
                            override fun onDoubleClick(event: MotionEvent) {
                                if (!(PrefManager.getVal(PrefName.BannerAnimations) as Boolean))
                                        snackString(getString(R.string.enable_banner_animations))
                                else {
                                    binding.mediaBanner.restart()
                                    binding.mediaBanner.performClick()
                                }
                            }

                            override fun onLongClick(event: MotionEvent) {
                                val bannerTitle =
                                        getString(R.string.banner, media.userPreferredName)
                                ImageViewDialog.newInstance(
                                        this@MediaDetailsActivity,
                                        bannerTitle,
                                        media.banner ?: media.cover
                                )
                                banner.performClick()
                            }
                        }
                )
        banner.setOnTouchListener { _, motionEvent ->
            gestureDetector.onTouchEvent(motionEvent)
            true
        }
        if (PrefManager.getVal(PrefName.Incognito)) {
            val mediaTitle = "    ${media.userPreferredName}"
            binding.mediaTitle.text = mediaTitle
            binding.incognito.visibility = View.VISIBLE
        } else {
            binding.mediaTitle.text = media.userPreferredName
        }
        binding.mediaTitle.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaTitleCollapse.text = media.userPreferredName
        binding.mediaTitleCollapse.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaStatus.text = media.status ?: ""

        // Fav Button
        val favButton =
                if (Anilist.userid != null) {
                    if (media.isFav)
                            binding.mediaFav.setImageDrawable(
                                    AppCompatResources.getDrawable(
                                            this,
                                            R.drawable.ic_round_favorite_24
                                    )
                            )

                    PopImageButton(
                            scope,
                            binding.mediaFav,
                            R.drawable.ic_round_favorite_24,
                            R.drawable.ic_round_favorite_border_24,
                            R.color.bg_opp,
                            R.color.violet_400,
                            media.isFav
                    ) {
                        media.isFav = it
                        Anilist.mutation.toggleFav(media.anime != null, media.id)
                        Refresh.all()
                    }
                } else {
                    binding.mediaFav.visibility = View.GONE
                    null
                }

        // Share Button
        binding.mediaShare.setOnClickListener { shareMediaLinks(media) }

        // Extension function to convert dp to px
        fun Int.dpToPx(): Int {
            return (this * resources.displayMetrics.density).toInt()
        }

        fun showLanguageDropdown(media: Media) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val availableLanguagesWithEpisodes =
                            ani.dantotsu.connections.malsync.MalSyncApi
                                    .getAvailableLanguagesWithEpisodes(media.idMAL!!)

                    withContext(Dispatchers.Main) {
                        if (availableLanguagesWithEpisodes.isNotEmpty()) {
                            val languageOptions =
                                    ani.dantotsu.connections.malsync.LanguageMapper
                                            .mapLanguagesWithEpisodes(
                                                    availableLanguagesWithEpisodes
                                            )

                            // Sort languages: English (Dub) first, then English (Sub), then the
                            // rest
                            val sortedLanguageOptions =
                                    languageOptions.sortedWith(
                                            compareBy { option ->
                                                when (option.id) {
                                                    "en/dub" -> 0 // English (Dub) first
                                                    "en/sub" -> 1 // English (Sub) second
                                                    else -> 2 // Everything else
                                                }
                                            }
                                    )

                            // Create ListPopupWindow with custom adapter to show icons
                            val listPopup =
                                    androidx.appcompat.widget.ListPopupWindow(
                                            this@MediaDetailsActivity
                                    )
                            listPopup.anchorView = binding.mediaLanguageButton

                            // Use the existing LanguageAdapter which already supports icons
                            val adapter =
                                    ani.dantotsu.connections.malsync.LanguageAdapter(
                                            this@MediaDetailsActivity,
                                            sortedLanguageOptions,
                                            isForListPopup = true // Show episode counts in popup
                                    )
                            listPopup.setAdapter(adapter)

                            // Set proper width to show content
                            listPopup.width =
                                    (300 * resources.displayMetrics.density).toInt() // 300dp

                            // Force dropdown to always appear below the button
                            listPopup.isModal = true

                            // CRITICAL: Set inputMethodMode to NOT_NEEDED to prevent automatic
                            // repositioning
                            listPopup.inputMethodMode =
                                    androidx.appcompat.widget.ListPopupWindow
                                            .INPUT_METHOD_NOT_NEEDED

                            // Wait for anchor to be measured, then show dropdown
                            binding.mediaLanguageButton.post {
                                // Get anchor location on screen after it's been measured
                                val location = IntArray(2)
                                binding.mediaLanguageButton.getLocationOnScreen(location)
                                val anchorY = location[1]
                                val anchorHeight = binding.mediaLanguageButton.height

                                // Calculate available space below the anchor
                                val displayMetrics = resources.displayMetrics
                                val screenHeight = displayMetrics.heightPixels
                                val spaceBelow = screenHeight - (anchorY + anchorHeight)

                                // Add small margin for visual spacing
                                val margin = 4.dpToPx()

                                // Set height based on available space
                                if (spaceBelow < 400.dpToPx()) {
                                    listPopup.height = spaceBelow - margin * 2
                                } else {
                                    listPopup.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                }

                                // Set vertical offset to appear just below the button
                                listPopup.verticalOffset = margin

                                // Use NO flags for gravity - this prevents automatic positioning
                                // and forces it to use our explicit offset
                                listPopup.setDropDownGravity(android.view.Gravity.NO_GRAVITY)

                                // Show the popup - it will now appear below the anchor
                                try {
                                    listPopup.show()
                                } catch (e: Exception) {
                                    // Handle case where show fails
                                    e.printStackTrace()
                                }
                            }

                            listPopup.setOnItemClickListener { _, _, position, _ ->
                                val selectedOption = sortedLanguageOptions[position]
                                ani.dantotsu.connections.malsync.MalSyncLanguageHelper
                                        .setPreferredLanguage(media.id, selectedOption.id)
                                listPopup.dismiss()

                                // Refresh MALSync data with new language
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val malSyncResult =
                                                ani.dantotsu.connections.malsync.MalSyncApi
                                                        .getLastEpisode(
                                                                media.id,
                                                                media.idMAL,
                                                                selectedOption.id
                                                        )
                                        if (malSyncResult != null && malSyncResult.lastEp != null) {
                                            val lastEpisode = malSyncResult.lastEp.total
                                            val userProgress = media.userProgress ?: 0
                                            val anilistTotal = media.anime!!.totalEpisodes
                                            val languageOption =
                                                    ani.dantotsu.connections.malsync.LanguageMapper
                                                            .mapLanguage(malSyncResult.id)

                                            val shouldShowLastEp =
                                                    when {
                                                        lastEpisode < userProgress -> false
                                                        anilistTotal != null &&
                                                                lastEpisode == anilistTotal -> false
                                                        anilistTotal != null &&
                                                                lastEpisode < anilistTotal -> true
                                                        else -> anilistTotal == null
                                                    }

                                            withContext(Dispatchers.Main) {
                                                val updatedText =
                                                        SpannableStringBuilder().apply {
                                                            val white =
                                                                    this@MediaDetailsActivity
                                                                            .getThemeColor(
                                                                                    com.google
                                                                                            .android
                                                                                            .material
                                                                                            .R
                                                                                            .attr
                                                                                            .colorOnBackground
                                                                            )
                                                            if (media.userStatus != null) {
                                                                append(
                                                                        getString(
                                                                                R.string.watched_num
                                                                        )
                                                                )
                                                                val colorSecondary =
                                                                        getThemeColor(
                                                                                com.google
                                                                                        .android
                                                                                        .material
                                                                                        .R
                                                                                        .attr
                                                                                        .colorSecondary
                                                                        )
                                                                bold {
                                                                    color(colorSecondary) {
                                                                        append(
                                                                                "${media.userProgress}"
                                                                        )
                                                                    }
                                                                }
                                                                append(
                                                                        getString(
                                                                                R.string
                                                                                        .episodes_out_of
                                                                        )
                                                                )
                                                            } else {
                                                                append(
                                                                        getString(
                                                                                R.string
                                                                                        .episodes_total_of
                                                                        )
                                                                )
                                                            }

                                                            if (shouldShowLastEp) {
                                                                bold {
                                                                    color(white) {
                                                                        append("$lastEpisode")
                                                                    }
                                                                }
                                                                append(" / ")
                                                            }

                                                            bold {
                                                                color(white) {
                                                                    append(
                                                                            "${anilistTotal ?: "??"}"
                                                                    )
                                                                }
                                                            }
                                                        }
                                                binding.mediaTotal.text = updatedText

                                                // Update button with new language directly
                                                val newLangCodeUpper =
                                                        if (malSyncResult.lang.isNotBlank()) {
                                                            malSyncResult.lang.uppercase()
                                                        } else {
                                                            languageOption
                                                                    .id
                                                                    .split("/")
                                                                    .firstOrNull()
                                                                    ?.uppercase()
                                                                    ?: "EN"
                                                        }
                                                binding.mediaLanguageButton.text = newLangCodeUpper
                                                val newIconDrawable =
                                                        ContextCompat.getDrawable(
                                                                this@MediaDetailsActivity,
                                                                languageOption.iconRes
                                                        )
                                                newIconDrawable?.setTint(
                                                        getThemeColor(
                                                                com.google
                                                                        .android
                                                                        .material
                                                                        .R
                                                                        .attr
                                                                        .colorOnSurface
                                                        )
                                                )
                                                binding.mediaLanguageButton
                                                        .setCompoundDrawablesWithIntrinsicBounds(
                                                                newIconDrawable,
                                                                null,
                                                                null,
                                                                null
                                                        )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        ani.dantotsu.util.Logger.log(
                                                "Error refreshing MALSync data: ${e.message}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    ani.dantotsu.util.Logger.log("Error fetching available languages: ${e.message}")
                }
            }
        }

        @SuppressLint("ResourceType")
        fun setupLanguageButton(
                media: Media,
                currentLanguageOption: ani.dantotsu.connections.malsync.LanguageOption,
                langCode: String
        ) {
            val langCodeUpper =
                    if (langCode.isNotBlank()) {
                        langCode.uppercase()
                    } else {
                        currentLanguageOption.id.split("/").firstOrNull()?.uppercase() ?: "EN"
                    }

            // Set button text (just the language code)
            binding.mediaLanguageButton.text = langCodeUpper

            // Set icon as compound drawable (left side)
            val iconDrawable =
                    ContextCompat.getDrawable(
                            this@MediaDetailsActivity,
                            currentLanguageOption.iconRes
                    )
            iconDrawable?.setTint(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
            binding.mediaLanguageButton.setCompoundDrawablesWithIntrinsicBounds(
                    iconDrawable, // left
                    null, // top
                    null, // right
                    null // bottom
            )

            // Set padding between icon and text
            binding.mediaLanguageButton.compoundDrawablePadding = 4.dpToPx()

            binding.mediaLanguageButton.visibility = View.VISIBLE

            // Setup dropdown on click
            binding.mediaLanguageButton.setOnClickListener {
                if (media.idMAL != null) {
                    showLanguageDropdown(media)
                }
            }
        }

        @SuppressLint("ResourceType")
        fun total() {
            ani.dantotsu.util.Logger.log("MediaDetails: total() function called")
            val text =
                    SpannableStringBuilder().apply {
                        val white =
                                this@MediaDetailsActivity.getThemeColor(
                                        com.google.android.material.R.attr.colorOnBackground
                                )
                        if (media.userStatus != null) {
                            append(
                                    if (media.anime != null) getString(R.string.watched_num)
                                    else getString(R.string.read_num)
                            )
                            val colorSecondary =
                                    getThemeColor(com.google.android.material.R.attr.colorSecondary)
                            bold { color(colorSecondary) { append("${media.userProgress}") } }
                            append(
                                    if (media.anime != null) getString(R.string.episodes_out_of)
                                    else getString(R.string.chapters_out_of)
                            )
                        } else {
                            append(
                                    if (media.anime != null) getString(R.string.episodes_total_of)
                                    else getString(R.string.chapters_total_of)
                            )
                        }
                        if (media.anime != null) {
                            if (media.anime!!.nextAiringEpisode != null) {
                                bold {
                                    color(white) { append("${media.anime!!.nextAiringEpisode}") }
                                }
                                append(" / ")
                            }
                            bold {
                                color(white) { append("${media.anime!!.totalEpisodes ?: "??"}") }
                            }
                        } else {
                            // For manga, initially show just total chapters
                            bold {
                                color(white) { append("${media.manga!!.totalChapters ?: "??"}") }
                            }
                        }
                    }
            binding.mediaTotal.text = text

            // Fetch MALSync data for anime to show latest available episode (replaces
            // nextAiringEpisode display)
            // Only show for anime with MAL ID and not COMPLETED status
            if (media.anime != null &&
                            media.idMAL != null &&
                            isOnline(this) &&
                            media.userStatus != "COMPLETED"
            ) {
                ani.dantotsu.util.Logger.log(
                        "MediaDetails: Starting MALSync API call for anime mediaId=${media.id}, malId=${media.idMAL}"
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val preferredLanguage =
                                ani.dantotsu.connections.malsync.MalSyncLanguageHelper
                                        .getPreferredLanguage(media.id)
                        val malSyncResult =
                                ani.dantotsu.connections.malsync.MalSyncApi.getLastEpisode(
                                        media.id,
                                        media.idMAL,
                                        preferredLanguage
                                )
                        ani.dantotsu.util.Logger.log(
                                "MediaDetails: MalSync anime result: $malSyncResult"
                        )

                        if (malSyncResult != null && malSyncResult.lastEp != null) {
                            val lastEpisode = malSyncResult.lastEp.total
                            val userProgress = media.userProgress ?: 0
                            val anilistTotal = media.anime!!.totalEpisodes
                            val languageOption =
                                    ani.dantotsu.connections.malsync.LanguageMapper.mapLanguage(
                                            malSyncResult.id
                                    )
                            ani.dantotsu.util.Logger.log(
                                    "MediaDetails: lastEpisode=$lastEpisode, userProgress=$userProgress, anilistTotal=$anilistTotal, language=${languageOption.displayName}"
                            )

                            // Determine if we should show lastEp number
                            val shouldShowLastEp =
                                    when {
                                        lastEpisode < userProgress -> false
                                        anilistTotal != null && lastEpisode == anilistTotal -> false
                                        anilistTotal != null && lastEpisode < anilistTotal -> true
                                        else -> anilistTotal == null // Show if no AniList total
                                    }

                            ani.dantotsu.util.Logger.log(
                                    "MediaDetails: shouldShowLastEp=$shouldShowLastEp"
                            )

                            withContext(Dispatchers.Main) {
                                // Update episode count display to show MALSync data
                                // Language info shown in button, not inline
                                val updatedText =
                                        SpannableStringBuilder().apply {
                                            val white =
                                                    this@MediaDetailsActivity.getThemeColor(
                                                            com.google
                                                                    .android
                                                                    .material
                                                                    .R
                                                                    .attr
                                                                    .colorOnBackground
                                                    )
                                            if (media.userStatus != null) {
                                                append(getString(R.string.watched_num))
                                                val colorSecondary =
                                                        getThemeColor(
                                                                com.google
                                                                        .android
                                                                        .material
                                                                        .R
                                                                        .attr
                                                                        .colorSecondary
                                                        )
                                                bold {
                                                    color(colorSecondary) {
                                                        append("${media.userProgress}")
                                                    }
                                                }
                                                append(getString(R.string.episodes_out_of))
                                            } else {
                                                append(getString(R.string.episodes_total_of))
                                            }

                                            // Show lastEp only if conditions are met
                                            if (shouldShowLastEp) {
                                                bold { color(white) { append("$lastEpisode") } }
                                                append(" / ")
                                            }

                                            bold {
                                                color(white) { append("${anilistTotal ?: "??"}") }
                                            }
                                        }
                                binding.mediaTotal.text = updatedText

                                // Show language button with icon and lang code
                                setupLanguageButton(media, languageOption, malSyncResult.lang)
                            }
                        }
                    } catch (e: Exception) {
                        ani.dantotsu.util.Logger.log(
                                "Error fetching MalSync anime data: ${e.message}"
                        )
                    }
                }
            }

            // Check if source and lastChapter passed from intent (e.g., from unread chapters list
            // or notification)
            val passedSource = intent.getStringExtra("source")
            val passedLastChapter = intent.getIntExtra("lastChapter", -1)

            // Handle passed unread info
            if (media.manga != null) {
                val userProgress = media.userProgress ?: 0

                // If we have both source and lastChapter, show the full info
                if (!passedSource.isNullOrBlank() &&
                                passedLastChapter > 0 &&
                                passedLastChapter > userProgress
                ) {
                    val updatedText =
                            SpannableStringBuilder().apply {
                                val white =
                                        this@MediaDetailsActivity.getThemeColor(
                                                com.google.android.material.R.attr.colorOnBackground
                                        )
                                if (media.userStatus != null) {
                                    append(getString(R.string.read_num))
                                    val colorSecondary =
                                            getThemeColor(
                                                    com.google
                                                            .android
                                                            .material
                                                            .R
                                                            .attr
                                                            .colorSecondary
                                            )
                                    bold {
                                        color(colorSecondary) { append("${media.userProgress}") }
                                    }
                                    append(getString(R.string.chapters_out_of))
                                } else {
                                    append(getString(R.string.chapters_total_of))
                                }
                                // Show latest available chapter (similar to nextAiringEpisode for
                                // anime)
                                bold { color(white) { append("$passedLastChapter") } }
                                append(" / ")
                                bold {
                                    color(white) {
                                        append("${media.manga!!.totalChapters ?: "??"}")
                                    }
                                }
                            }
                    binding.mediaTotal.text = updatedText

                    binding.mediaUnreadSource?.text =
                            getString(R.string.notification_source_subtext, passedSource)
                    binding.mediaUnreadSource?.visibility = View.VISIBLE
                } else if (!passedSource.isNullOrBlank()) {
                    // Show source only (from notification or when we have source but no
                    // lastChapter)
                    binding.mediaUnreadSource?.text =
                            getString(R.string.notification_source_subtext, passedSource)
                    binding.mediaUnreadSource?.visibility = View.VISIBLE
                }
            }

            // Fetch MalSync data for manga to show latest available chapter (or update cached data)
            // Don't show for manga marked as COMPLETED
            ani.dantotsu.util.Logger.log("MediaDetails: total() called for ${media.mainName()}")
            ani.dantotsu.util.Logger.log(
                    "MediaDetails: manga=${media.manga != null}, online=${isOnline(this)}, status=${media.userStatus}"
            )
            ani.dantotsu.util.Logger.log(
                    "MediaDetails: passedSource=$passedSource, passedLastChapter=$passedLastChapter"
            )

            if (media.manga != null && isOnline(this) && media.userStatus != "COMPLETED") {
                ani.dantotsu.util.Logger.log(
                        "MediaDetails: Starting MalSync API call for mediaId=${media.id}"
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val malSyncResult =
                                ani.dantotsu.connections.malsync.MalSyncApi.getLastChapter(
                                        media.id,
                                        media.idMAL
                                )
                        ani.dantotsu.util.Logger.log("MediaDetails: MalSync result: $malSyncResult")
                        if (malSyncResult != null && malSyncResult.lastEp != null) {
                            val lastChapter = malSyncResult.lastEp.total
                            val userProgress = media.userProgress ?: 0
                            ani.dantotsu.util.Logger.log(
                                    "MediaDetails: lastChapter=$lastChapter, userProgress=$userProgress, source=${malSyncResult.source}"
                            )

                            withContext(Dispatchers.Main) {
                                // Check if there are unread chapters
                                val hasUnreadChapters = lastChapter > userProgress
                                ani.dantotsu.util.Logger.log(
                                        "MediaDetails: hasUnreadChapters=$hasUnreadChapters"
                                )

                                if (hasUnreadChapters) {
                                    // Update chapter count display
                                    val updatedText =
                                            SpannableStringBuilder().apply {
                                                val white =
                                                        this@MediaDetailsActivity.getThemeColor(
                                                                com.google
                                                                        .android
                                                                        .material
                                                                        .R
                                                                        .attr
                                                                        .colorOnBackground
                                                        )
                                                if (media.userStatus != null) {
                                                    append(getString(R.string.read_num))
                                                    val colorSecondary =
                                                            getThemeColor(
                                                                    com.google
                                                                            .android
                                                                            .material
                                                                            .R
                                                                            .attr
                                                                            .colorSecondary
                                                            )
                                                    bold {
                                                        color(colorSecondary) {
                                                            append("${media.userProgress}")
                                                        }
                                                    }
                                                    append(getString(R.string.chapters_out_of))
                                                } else {
                                                    append(getString(R.string.chapters_total_of))
                                                }
                                                // Show latest available chapter (similar to
                                                // nextAiringEpisode for anime)
                                                bold { color(white) { append("$lastChapter") } }
                                                append(" / ")
                                                bold {
                                                    color(white) {
                                                        append(
                                                                "${media.manga!!.totalChapters ?: "??"}"
                                                        )
                                                    }
                                                }
                                            }
                                    binding.mediaTotal.text = updatedText
                                }

                                // Show source from MalSync result (prefer MalSync source, fallback
                                // to passed source)
                                val malSource = malSyncResult.source
                                val sourceToShow =
                                        if (!malSource.isNullOrBlank()) {
                                            malSource
                                        } else if (!passedSource.isNullOrBlank()) {
                                            passedSource
                                        } else if (hasUnreadChapters) {
                                            // MalSync reports unread but no source - show unknown
                                            getString(R.string.notification_unknown_source)
                                        } else {
                                            null
                                        }

                                ani.dantotsu.util.Logger.log(
                                        "MediaDetails: malSource='$malSource', sourceToShow='$sourceToShow'"
                                )
                                ani.dantotsu.util.Logger.log(
                                        "MediaDetails: Condition check: sourceNotBlank=${!sourceToShow.isNullOrBlank()}, hasUnread=$hasUnreadChapters, hasPassedSource=${!passedSource.isNullOrBlank()}"
                                )

                                if (!sourceToShow.isNullOrBlank() &&
                                                (hasUnreadChapters || !passedSource.isNullOrBlank())
                                ) {
                                    // Show source if: 1) has unread chapters, OR 2) source was
                                    // explicitly passed
                                    ani.dantotsu.util.Logger.log(
                                            "MediaDetails: SHOWING source: $sourceToShow"
                                    )
                                    binding.mediaUnreadSource?.text =
                                            getString(
                                                    R.string.notification_source_subtext,
                                                    sourceToShow
                                            )
                                    binding.mediaUnreadSource?.visibility = View.VISIBLE
                                } else {
                                    ani.dantotsu.util.Logger.log(
                                            "MediaDetails: HIDING source (passedSource=$passedSource)"
                                    )
                                    // Hide source if user is caught up and no explicit passed
                                    // source
                                    if (passedSource.isNullOrBlank()) {
                                        binding.mediaUnreadSource?.visibility = View.GONE
                                    }
                                }
                            }
                        } else {
                            ani.dantotsu.util.Logger.log(
                                    "MediaDetails: MalSync result was null or had no lastEp"
                            )
                        }
                    } catch (e: Exception) {
                        ani.dantotsu.util.Logger.log(
                                "MediaDetails: MalSync API error: ${e.message}"
                        )
                        e.printStackTrace()
                        // Silently fail - just keep showing the original text
                    }
                }
            } else {
                ani.dantotsu.util.Logger.log(
                        "MediaDetails: NOT calling MalSync API - condition failed"
                )
            }
        }

        fun progress() {
            ani.dantotsu.util.Logger.log(
                    "MediaDetails: progress() called, userStatus=${media.userStatus}"
            )
            val statuses: Array<String> = resources.getStringArray(R.array.status)
            val statusStrings =
                    if (media.manga == null) resources.getStringArray(R.array.status_anime)
                    else resources.getStringArray(R.array.status_manga)
            val userStatus =
                    if (media.userStatus != null) statusStrings[statuses.indexOf(media.userStatus)]
                    else statusStrings[0]

            if (media.userStatus != null) {
                binding.mediaTotal.visibility = View.VISIBLE
                binding.mediaAddToList.text = userStatus
            } else {
                binding.mediaAddToList.setText(R.string.add_list)
            }
            total()
            binding.mediaAddToList.setOnClickListener {
                if (Anilist.userid != null) {
                    if (supportFragmentManager.findFragmentByTag("dialog") == null)
                            MediaListDialogFragment().show(supportFragmentManager, "dialog")
                } else snackString(getString(R.string.please_login_anilist))
            }
            binding.mediaAddToList.setOnLongClickListener {
                PrefManager.setCustomVal(
                        "${media.id}_progressDialog",
                        true,
                )
                snackString(getString(R.string.auto_update_reset))
                true
            }
        }
        progress()

        supportFragmentManager.addFragmentOnAttachListener { _, fragment ->
            if (fragment != null) {
                media = fragment as? Media ?: media
                scope.launch { if (media.isFav != favButton?.clicked) favButton?.clicked() }

                binding.mediaCover.setOnClickListener { openLinkInBrowser(media.shareLink) }
                progress()
            }
        }
        adult = media.isAdult
        if (media.anime != null) {
            viewPager.adapter =
                    ViewPagerAdapter(
                            supportFragmentManager,
                            lifecycle,
                            SupportedMedia.ANIME,
                            media,
                            intent.getIntExtra("commentId", -1)
                    )
        } else if (media.manga != null) {
            viewPager.adapter =
                    ViewPagerAdapter(
                            supportFragmentManager,
                            lifecycle,
                            if (media.format == "NOVEL") SupportedMedia.NOVEL
                            else SupportedMedia.MANGA,
                            media,
                            intent.getIntExtra("commentId", -1)
                    )
            anime = false
        }
        val comment = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 2
        selected = media.selected!!.window.coerceIn(0, if (comment) 2 else 3)
        binding.mediaTitle.translationX = -screenWidth

        val infoTab = navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info)
        val watchTab =
                if (anime) {
                    navBar.createTab(
                            R.drawable.ic_round_movie_filter_24,
                            R.string.watch,
                            R.id.watch
                    )
                } else if (media.format == "NOVEL") {
                    navBar.createTab(R.drawable.ic_round_book_24, R.string.read, R.id.read)
                } else {
                    navBar.createTab(
                            R.drawable.ic_round_import_contacts_24,
                            R.string.read,
                            R.id.read
                    )
                }
        val commentTab =
                navBar.createTab(R.drawable.ic_round_comment_24, R.string.comments, R.id.comment)
        navBar.addTab(infoTab)
        navBar.addTab(watchTab)
        if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1) {
            navBar.addTab(commentTab)
        }
        if (model.continueMedia == null && media.cameFromContinue) {
            model.continueMedia = PrefManager.getVal(PrefName.ContinueMedia)
            selected = 1
        }
        if (intent.getStringExtra("FRAGMENT_TO_LOAD") != null) selected = 2
        if (viewPager.currentItem != selected)
                viewPager.post { viewPager.setCurrentItem(selected, false) }
        binding.commentInputLayout.isVisible = selected == 2
        navBar.selectTabAt(selected)
        navBar.setOnTabSelectListener(
                object : AnimatedBottomBar.OnTabSelectListener {
                    override fun onTabSelected(
                            lastIndex: Int,
                            lastTab: AnimatedBottomBar.Tab?,
                            newIndex: Int,
                            newTab: AnimatedBottomBar.Tab
                    ) {
                        selected = newIndex
                        binding.commentInputLayout.isVisible = selected == 2
                        viewPager.setCurrentItem(selected, true)
                        val sel = model.loadSelected(media, isDownload)
                        sel.window = selected
                        model.saveSelected(media.id, sel)
                    }
                }
        )

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch(Dispatchers.IO) {
                    model.loadMedia(media)
                    live.postValue(false)
                }
            }
        }

        // Observe media updates to refresh the progress display
        model.getMedia().observe(this) { updatedMedia ->
            if (updatedMedia != null) {
                media = updatedMedia
                progress()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val rightMargin =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        navBarHeight
                else 0
        val bottomMargin =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 0
                else navBarHeight
        val params: ViewGroup.MarginLayoutParams =
                navBar.layoutParams as ViewGroup.MarginLayoutParams
        params.updateMargins(right = rightMargin, bottom = bottomMargin)
    }

    override fun onResume() {
        if (::navBar.isInitialized) navBar.selectTabAt(selected)
        super.onResume()
    }

    private enum class SupportedMedia {
        ANIME,
        MANGA,
        NOVEL
    }

    // ViewPager
    private class ViewPagerAdapter(
            fragmentManager: FragmentManager,
            lifecycle: Lifecycle,
            private val mediaType: SupportedMedia,
            private val media: Media,
            private val commentId: Int
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment =
                when (position) {
                    0 -> MediaInfoFragment()
                    1 ->
                            when (mediaType) {
                                SupportedMedia.ANIME -> AnimeWatchFragment()
                                SupportedMedia.MANGA -> MangaReadFragment()
                                SupportedMedia.NOVEL -> NovelReadFragment()
                            }
                    2 -> {
                        val fragment = CommentsFragment()
                        val bundle = Bundle()
                        bundle.putInt("mediaId", media.id)
                        bundle.putString("mediaName", media.mainName())
                        if (commentId != -1) bundle.putInt("commentId", commentId)
                        fragment.arguments = bundle
                        fragment
                    }
                    else -> MediaInfoFragment()
                }
    }

    // Collapsing UI Stuff
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
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", 0f)
                    .setDuration(duration)
                    .start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", screenWidth)
                    .setDuration(duration)
                    .start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", screenWidth)
                    .setDuration(duration)
                    .start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", screenWidth)
                    .setDuration(duration)
                    .start()
            binding.mediaBanner.pause()
        }
        if (percentage <= percent && isCollapsed) {
            isCollapsed = false
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", -screenWidth)
                    .setDuration(duration)
                    .start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", 0f)
                    .setDuration(duration)
                    .start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", 0f)
                    .setDuration(duration)
                    .start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", 0f)
                    .setDuration(duration)
                    .start()
            if (PrefManager.getVal(PrefName.BannerAnimations)) binding.mediaBanner.resume()
        }
        if (percentage == 1 && model.scrolledToTop.value != false)
                model.scrolledToTop.postValue(false)
        if (percentage == 0 && model.scrolledToTop.value != true)
                model.scrolledToTop.postValue(true)
    }

    class PopImageButton(
            private val scope: CoroutineScope,
            private val image: ImageView,
            private val d1: Int,
            private val d2: Int,
            private val c1: Int,
            private val c2: Int,
            var clicked: Boolean,
            needsInitialClick: Boolean = false,
            callback: suspend (Boolean) -> (Unit)
    ) {
        private var disabled = false
        private val context = image.context
        private var pressable = true

        init {
            enabled(true)
            if (needsInitialClick) {
                scope.launch { clicked() }
            }
            image.setOnClickListener {
                if (pressable && !disabled) {
                    pressable = false
                    clicked = !clicked
                    scope.launch {
                        launch(Dispatchers.IO) { callback.invoke(clicked) }
                        clicked()
                        pressable = true
                    }
                }
            }
        }

        suspend fun clicked() {
            ObjectAnimator.ofFloat(image, "scaleX", 1f, 0f).setDuration(69).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1f, 0f).setDuration(100).start()
            delay(100)

            if (clicked) {
                ObjectAnimator.ofArgb(
                                image,
                                "ColorFilter",
                                ContextCompat.getColor(context, c1),
                                ContextCompat.getColor(context, c2)
                        )
                        .setDuration(120)
                        .start()
                image.setImageDrawable(AppCompatResources.getDrawable(context, d1))
            } else image.setImageDrawable(AppCompatResources.getDrawable(context, d2))
            ObjectAnimator.ofFloat(image, "scaleX", 0f, 1.5f).setDuration(120).start()
            ObjectAnimator.ofFloat(image, "scaleY", 0f, 1.5f).setDuration(100).start()
            delay(120)
            ObjectAnimator.ofFloat(image, "scaleX", 1.5f, 1f).setDuration(100).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1.5f, 1f).setDuration(100).start()
            delay(200)
            if (clicked) {
                ObjectAnimator.ofArgb(
                                image,
                                "ColorFilter",
                                ContextCompat.getColor(context, c2),
                                ContextCompat.getColor(context, c1)
                        )
                        .setDuration(200)
                        .start()
            }
        }

        fun enabled(enabled: Boolean) {
            disabled = !enabled
            image.alpha = if (disabled) 0.33f else 1f
        }
    }

    private fun shareMediaLinks(media: Media) {
        val isAnime = media.anime != null
        val mediaType = if (isAnime) "anime" else "manga"

        // Build list of available links with their labels and icons
        val linkOptions = mutableListOf<Triple<String, String, Int>>() // label, url, iconRes

        // AniList link
        val anilistLink = "https://anilist.co/$mediaType/${media.id}"
        linkOptions.add(Triple("AniList", anilistLink, R.drawable.ic_anilist))

        // MAL link
        if (media.idMAL != null) {
            val malLink = "https://myanimelist.net/$mediaType/${media.idMAL}"
            linkOptions.add(Triple("MyAnimeList", malLink, R.drawable.ic_myanimelist))
        }

        // For manga only: Comick and MangaUpdates
        if (!isAnime) {
            // Comick link
            val comickSlug = model.comickSlug.value
            if (comickSlug != null) {
                val comickLink = "https://comick.io/comic/$comickSlug"
                linkOptions.add(Triple("Comick", comickLink, R.drawable.ic_round_comick_24))
            }

            // MangaUpdates link - check ViewModel first
            var muLink = model.mangaUpdatesLink.value

            // If not in ViewModel, check external links
            if (muLink == null) {
                media.externalLinks.forEach { linkEntry ->
                    val candidate = linkEntry.getOrNull(1) ?: linkEntry.getOrNull(0)
                    if (!candidate.isNullOrBlank() &&
                                    candidate.contains("mangaupdates", ignoreCase = true)
                    ) {
                        muLink = candidate
                        return@forEach
                    }
                }
            }

            if (muLink != null) {
                linkOptions.add(Triple("MangaUpdates", muLink, R.drawable.ic_round_mangaupdates_24))
            }
        }

        // Create custom dialog with icons in a row
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.MyPopup).create()

        // Create custom title view with centered text
        val titleView =
                android.widget.TextView(this).apply {
                    setText(R.string.share)
                    textSize = 20f
                    gravity = android.view.Gravity.CENTER
                    setPadding(32, 32, 32, 16)
                    setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                }
        dialog.setCustomTitle(titleView)

        // Create horizontal layout with icons
        val iconLayout =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(32, 16, 32, 32)
                }

        linkOptions.forEach { (label, link, iconRes) ->
            val iconButton =
                    ImageView(this).apply {
                        setImageResource(iconRes)
                        val size = (56 * resources.displayMetrics.density).toInt()
                        layoutParams =
                                LinearLayout.LayoutParams(size, size).apply {
                                    setMargins(16, 0, 16, 0)
                                }
                        setPadding(12, 12, 12, 12)
                        // Set icon color to match tab icons (bg_opp: black in light theme, white in
                        // dark theme)
                        setColorFilter(
                                ContextCompat.getColor(context, R.color.bg_opp),
                                android.graphics.PorterDuff.Mode.SRC_IN
                        )
                        // Add ripple effect without background
                        val outValue = android.util.TypedValue()
                        context.theme.resolveAttribute(
                                android.R.attr.selectableItemBackgroundBorderless,
                                outValue,
                                true
                        )
                        setBackgroundResource(outValue.resourceId)
                        contentDescription = label
                        setOnClickListener {
                            // Open share sheet directly - it will dismiss the dialog automatically
                            val shareIntent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, link)
                                        putExtra(
                                                Intent.EXTRA_SUBJECT,
                                                "${media.userPreferredName} - $label"
                                        )
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

    companion object {
        var mediaSingleton: Media? = null
    }
}
