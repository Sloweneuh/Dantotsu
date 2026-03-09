package ani.dantotsu.media

import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.home.MergedReadingAdapter
import ani.dantotsu.initActivity
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager

class MediaListViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaListViewBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaListViewBinding.inflate(layoutInflater)
        ThemeManager(this).applyTheme()
        initActivity(this)
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            this.window.statusBarColor =
                ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true

        } else {
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }

        setContentView(binding.root)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = getThemeColor(com.google.android.material.R.attr.colorPrimary)
        val secondaryTextColor = getThemeColor(com.google.android.material.R.attr.colorOutline)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        binding.listTitle.isSelected = true

        val description = passedDescription
        if (passedDescription != null) passedDescription = null
        if (!description.isNullOrBlank()) {
            binding.listDescription.visibility = View.VISIBLE
            binding.listDescription.setOnClickListener {
                val descView = TextView(this).apply {
                    setPadding(32, 16, 32, 16)
                    text = HtmlCompat.fromHtml(description, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    textSize = 14f
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
                Linkify.addLinks(descView, Linkify.WEB_URLS)
                StackListAdapter.interceptLinks(
                    descView,
                    getThemeColor(com.google.android.material.R.attr.colorPrimary),
                    intent.getBooleanExtra("isAnime", false)
                )
                CustomBottomDialog.newInstance().apply {
                    setTitleText(intent.getStringExtra("title") ?: "")
                    addView(descView)
                }.show(supportFragmentManager, "stackDesc")
            }
        }

        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        val mediaList =
            passedMedia ?: intent.getSerialized("media") as? ArrayList<Media> ?: ArrayList()
        if (passedMedia != null) passedMedia = null

        val muMediaList: ArrayList<MUMedia> = passedMuMedia ?: arrayListOf()
        if (passedMuMedia != null) passedMuMedia = null

        // Store unread info locally for use in changeView
        val localUnreadInfo = passedUnreadInfo
        if (passedUnreadInfo != null) passedUnreadInfo = null

        // Store unreleased episode info locally for use in changeView
        val localUnreleasedInfo = passedUnreleasedInfo
        if (passedUnreleasedInfo != null) passedUnreleasedInfo = null

        // Build a timestamp-sorted merged list when MU items are present
        val combinedItems: List<Any>? = if (muMediaList.isNotEmpty()) {
            (mediaList.map { it to (it.userUpdatedAt ?: 0L) } +
             muMediaList.map { it to (it.updatedAt ?: 0L) })
                .sortedByDescending { (_, ts) -> ts }
                .map { (item, _) -> item }
        } else null

        val view = PrefManager.getCustomVal("mediaView", 0)
        var mediaView: android.widget.ImageView = when (view) {
            1 -> binding.mediaList
            0 -> binding.mediaGrid
            else -> binding.mediaGrid
        }

        // Set initial button states - selected button fully opaque, other at ~33%
        when (view) {
            1 -> {
                binding.mediaList.imageAlpha = 255
                binding.mediaGrid.imageAlpha = 84
            }
            else -> {
                binding.mediaGrid.imageAlpha = 255
                binding.mediaList.imageAlpha = 84
            }
        }

        fun changeView(mode: Int, current: android.widget.ImageView) {
            mediaView.imageAlpha = 84
            mediaView = current
            current.imageAlpha = 255
            PrefManager.setCustomVal("mediaView", mode)

            if (combinedItems != null) {
                binding.mediaRecyclerView.adapter = MergedReadingAdapter(combinedItems, mode)
                binding.mediaRecyclerView.layoutManager = GridLayoutManager(
                    this,
                    if (mode == 1) 1 else (screenWidth / 120f).toInt()
                )
                return
            }

            // Use custom adapter based on what info we have
            when {
                localUnreadInfo != null -> {
                    // Manga with unread chapters
                    binding.mediaRecyclerView.adapter = ani.dantotsu.home.UnreadChaptersAdapter(mediaList, localUnreadInfo, mode)
                }
                localUnreleasedInfo != null -> {
                    // Anime with unreleased episodes
                    binding.mediaRecyclerView.adapter = ani.dantotsu.home.UnreleasedEpisodesAdapter(mediaList, localUnreleasedInfo, mode)
                }
                else -> {
                    // Standard adapter
                    binding.mediaRecyclerView.adapter = MediaAdaptor(mode, mediaList, this)
                }
            }
            binding.mediaRecyclerView.layoutManager = GridLayoutManager(
                this,
                if (mode == 1) 1 else (screenWidth / 120f).toInt()
            )
        }
        binding.mediaList.setOnClickListener {
            changeView(1, binding.mediaList)
        }
        binding.mediaGrid.setOnClickListener {
            changeView(0, binding.mediaGrid)
        }
        val totalCount = combinedItems?.count() ?: mediaList.count()
        val text = "${intent.getStringExtra("title")} ($totalCount)"
        binding.listTitle.text = text

        binding.listBack.setOnClickListener {
            finish()
        }

        // Initial adapter setup
        if (combinedItems != null) {
            binding.mediaRecyclerView.adapter = MergedReadingAdapter(combinedItems, view)
        } else when {
            localUnreadInfo != null -> {
                // Use custom adapter for unread chapters (manga)
                binding.mediaRecyclerView.adapter = ani.dantotsu.home.UnreadChaptersAdapter(mediaList, localUnreadInfo, view)
            }
            localUnreleasedInfo != null -> {
                // Use custom adapter for unreleased episodes (anime)
                binding.mediaRecyclerView.adapter = ani.dantotsu.home.UnreleasedEpisodesAdapter(mediaList, localUnreleasedInfo, view)
            }
            else -> {
                // Use standard adapter
                binding.mediaRecyclerView.adapter = MediaAdaptor(view, mediaList, this)
            }
        }
        binding.mediaRecyclerView.layoutManager = GridLayoutManager(
            this,
            if (view == 1) 1 else (screenWidth / 120f).toInt()
        )
    }

    companion object {
        var passedMedia: ArrayList<Media>? = null
        var passedMuMedia: ArrayList<MUMedia>? = null
        var passedUnreadInfo: Map<Int, ani.dantotsu.connections.malsync.UnreadChapterInfo>? = null
        var passedUnreleasedInfo: Map<Int, ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo>? = null
        var passedDescription: String? = null
    }
}
