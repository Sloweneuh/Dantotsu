package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.chiaki.Chiaki
import ani.dantotsu.databinding.ActivityFollowBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.enableSettingsLongPress
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.launch

/**
 * The chiaki.site watch order for a franchise: every TV run, movie, OVA and special of it in the
 * order they're meant to be watched, which is not the order AniList's relation graph gives.
 *
 * Takes `malId` when AniList knows one and falls back to resolving it from `titles` (see
 * [Chiaki.resolveMalId]), so the caller never has to look one up before opening the screen.
 */
class WatchOrderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFollowBinding
    private val entries = mutableListOf<Chiaki.WatchOrderEntry>()

    /** The entry for the media this screen was opened from, highlighted in the list. */
    private var currentMalId = -1
    private var currentAnilistId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityFollowBinding.inflate(layoutInflater)
        binding.listToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.listFrameLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        setContentView(binding.root)

        binding.followFilterButton.visibility = View.GONE
        binding.followerGrid.visibility = View.GONE
        binding.followerList.visibility = View.GONE
        binding.followSwipeRefresh.isEnabled = false
        binding.listTitle.setText(R.string.watch_order)
        binding.listBack.enableSettingsLongPress()
        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.listRecyclerView.adapter = WatchOrderAdapter()
        binding.listRecyclerView.layoutManager = LinearLayoutManager(this)

        currentMalId = intent.getIntExtra("malId", -1)
        currentAnilistId = intent.getIntExtra("mediaId", -1)
        val titles = intent.getStringArrayListExtra("titles").orEmpty()
        val year = intent.getIntExtra("startYear", -1).takeIf { it > 0 }

        lifecycleScope.launch {
            // Chiaki is keyed by MAL id, so media AniList has no idMal for get one by title.
            val malId = currentMalId.takeIf { it > 0 } ?: Chiaki.resolveMalId(titles, year)
            if (malId != null) {
                currentMalId = malId
                entries.addAll(Chiaki.getWatchOrder(malId))
            }
            binding.listProgressBar.visibility = View.GONE
            if (entries.isEmpty()) {
                binding.listEmptyText.setText(R.string.no_watch_order)
                binding.listEmptyContainer.visibility = View.VISIBLE
                binding.followSwipeRefresh.visibility = View.GONE
            } else {
                binding.listRecyclerView.adapter?.notifyItemRangeInserted(0, entries.size)
            }
        }
    }

    private inner class WatchOrderAdapter : RecyclerView.Adapter<WatchOrderAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val position: TextView = view.findViewById(R.id.watchOrderPosition)
            val cover: ImageView = view.findViewById(R.id.watchOrderCover)
            val title: TextView = view.findViewById(R.id.watchOrderTitle)
            val subtitle: TextView = view.findViewById(R.id.watchOrderSubtitle)
            val meta: TextView = view.findViewById(R.id.watchOrderMeta)
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_watch_order, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.position.text = (position + 1).toString()
            holder.title.text = entry.title
            holder.subtitle.isVisible = entry.englishTitle != null
            holder.subtitle.text = entry.englishTitle

            val meta = listOfNotNull(entry.meta, entry.rating).joinToString(" • ")
            holder.meta.isVisible = meta.isNotEmpty()
            holder.meta.text = meta

            holder.cover.loadImage(entry.imageUrl)

            // The entry this screen was opened from: worth marking, since a long franchise scrolls
            // well past it and "where am I in this" is half the reason to open a watch order.
            val isCurrent = entry.malId == currentMalId ||
                    (entry.anilistId != null && entry.anilistId == currentAnilistId)
            holder.position.setTextColor(
                getThemeColor(
                    if (isCurrent) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnBackground
                )
            )
            holder.position.alpha = if (isCurrent) 1f else 0.4f

            holder.itemView.setOnClickListener {
                val anilistId = entry.anilistId
                if (anilistId != null && anilistId > 0) {
                    startActivity(
                        Intent(this@WatchOrderActivity, MediaDetailsActivity::class.java)
                            .putExtra("mediaId", anilistId)
                    )
                } else {
                    // chiaki has entries AniList doesn't; MAL is the id it is keyed by anyway.
                    openLinkInBrowser("https://myanimelist.net/anime/${entry.malId}")
                }
            }
        }
    }
}
