package ani.dantotsu.media

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.initActivity
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
        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        val mediaList =
            passedMedia ?: intent.getSerialized("media") as? ArrayList<Media> ?: ArrayList()
        if (passedMedia != null) passedMedia = null

        // Store unread info locally for use in changeView
        val localUnreadInfo = passedUnreadInfo
        if (passedUnreadInfo != null) passedUnreadInfo = null

        // Store unreleased episode info locally for use in changeView
        val localUnreleasedInfo = passedUnreleasedInfo
        if (passedUnreleasedInfo != null) passedUnreleasedInfo = null

        val view = PrefManager.getCustomVal("mediaView", 0)
        var mediaView: View = when (view) {
            1 -> binding.mediaList
            0 -> binding.mediaGrid
            else -> binding.mediaGrid
        }

        // Set initial button states - selected button at full alpha, other at 0.33f
        when (view) {
            1 -> {
                binding.mediaList.alpha = 1f
                binding.mediaGrid.alpha = 0.33f
            }
            else -> {
                binding.mediaGrid.alpha = 1f
                binding.mediaList.alpha = 0.33f
            }
        }

        fun changeView(mode: Int, current: View) {
            mediaView.alpha = 0.33f
            mediaView = current
            current.alpha = 1f
            PrefManager.setCustomVal("mediaView", mode)

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
        val text = "${intent.getStringExtra("title")} (${mediaList.count()})"
        binding.listTitle.text = text

        // Check if we have unread chapter info or unreleased episode info to display
        when {
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
        var passedUnreadInfo: Map<Int, ani.dantotsu.connections.malsync.UnreadChapterInfo>? = null
        var passedUnreleasedInfo: Map<Int, ani.dantotsu.connections.malsync.UnreleasedEpisodeInfo>? = null
    }
}
