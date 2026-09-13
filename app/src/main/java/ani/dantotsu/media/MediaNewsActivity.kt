package ani.dantotsu.media

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.chiaki.Chiaki
import ani.dantotsu.connections.kuroiru.Kuroiru
import ani.dantotsu.connections.mangabaka.MangaBakaApi
import ani.dantotsu.databinding.ActivityFollowBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.enableSettingsLongPress
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

/**
 * News for one media — announcements, PVs, delays, print runs — from whichever provider covers its
 * type: Kuroiru for anime, MangaBaka for manga and novels. AniList carries none of this.
 *
 * Both providers are keyed by an id that isn't AniList's, so the lookup happens here rather than
 * in the info tab: opening the screen is what costs a request, not showing the button.
 */
class MediaNewsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFollowBinding
    private val entries = mutableListOf<NewsEntry>()

    /** One news item, flattened from whichever provider it came from. */
    private data class NewsEntry(
        val title: String,
        val url: String,
        val source: String?,
        val timeMillis: Long?,
    )

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
        binding.listTitle.setText(R.string.news)
        binding.listBack.enableSettingsLongPress()
        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.listRecyclerView.adapter = NewsAdapter()
        binding.listRecyclerView.layoutManager = LinearLayoutManager(this)

        val isAnime = intent.getBooleanExtra("isAnime", true)
        val malId = intent.getIntExtra("malId", -1)
        val anilistId = intent.getIntExtra("mediaId", -1).takeIf { it > 0 }
        val muSeriesId = intent.getLongExtra("muSeriesId", -1L)
        val titles = intent.getStringArrayListExtra("titles").orEmpty()
        val year = intent.getIntExtra("startYear", -1).takeIf { it > 0 }

        lifecycleScope.launch {
            val loaded =
                if (isAnime) animeNews(malId, titles, year)
                else mangaNews(anilistId, malId, muSeriesId)
            // Both providers answer newest-first, but neither documents it, and the screen
            // only makes sense in that order. Undated items sort to the end.
            entries.addAll(loaded.sortedByDescending { it.timeMillis ?: Long.MIN_VALUE })
            binding.listProgressBar.visibility = View.GONE
            if (entries.isEmpty()) {
                binding.listEmptyText.setText(R.string.no_news)
                binding.listEmptyContainer.visibility = View.VISIBLE
                binding.followSwipeRefresh.visibility = View.GONE
            } else {
                binding.listRecyclerView.adapter?.notifyItemRangeInserted(0, entries.size)
            }
        }
    }

    /** Kuroiru, keyed by MAL id — resolved from the titles when AniList has no `idMal`. */
    private suspend fun animeNews(malId: Int, titles: List<String>, year: Int?): List<NewsEntry> {
        val id = malId.takeIf { it > 0 } ?: Chiaki.resolveMalId(titles, year) ?: return emptyList()
        return Kuroiru.getNews(id).map { news ->
            NewsEntry(
                // Kuroiru sends titles HTML-escaped.
                title = HtmlCompat.fromHtml(news.title, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    .toString().trim(),
                url = news.link,
                source = news.link.hostLabel(),
                timeMillis = news.time.takeIf { it > 0 }?.times(1000L),
            )
        }
    }

    /**
     * MangaBaka, keyed by its own series id, which it maps from whichever id the caller has: a
     * MangaUpdates series has no AniList or MAL id to offer, only its own.
     *
     * Unlike Kuroiru, [MangaBakaApi] leaves the dispatcher to its caller, and a long-running
     * series' feed is a thousand-odd items to decode.
     */
    private suspend fun mangaNews(anilistId: Int?, malId: Int, muSeriesId: Long): List<NewsEntry> =
        withContext(Dispatchers.IO) {
            val seriesId = muSeriesId.takeIf { it > 0 }
                ?.let { MangaBakaApi.resolveSeriesId(MangaBakaApi.Source.MANGAUPDATES, it) }
                ?: MangaBakaApi.resolveFromAnilist(anilistId, malId.takeIf { it > 0 })
                ?: return@withContext emptyList()
            MangaBakaApi.getNews(seriesId).map { news ->
                NewsEntry(
                    title = news.title.orEmpty().trim(),
                    url = news.url.orEmpty(),
                    source = news.sourceName?.uppercase(Locale.ROOT) ?: news.url?.hostLabel(),
                    timeMillis = news.publishedAt?.toEpochMillis(),
                )
            }
        }

    private fun String.hostLabel(): String? =
        runCatching { toUri().host?.removePrefix("www.") }.getOrNull()

    /** MangaBaka dates items with an ISO-8601 instant, e.g. `2026-09-06T00:00:00.000Z`. */
    private fun String.toEpochMillis(): Long? =
        runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

    private inner class NewsAdapter : RecyclerView.Adapter<NewsAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.newsTitle)
            val meta: TextView = view.findViewById(R.id.newsMeta)
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_news, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.title.text = entry.title

            val published = entry.timeMillis?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.DAY_IN_MILLIS
                )
            }
            val meta = listOfNotNull(entry.source, published).joinToString(" • ")
            holder.meta.isVisible = meta.isNotEmpty()
            holder.meta.text = meta

            holder.itemView.setOnClickListener { openLinkInBrowser(entry.url) }
        }
    }
}
