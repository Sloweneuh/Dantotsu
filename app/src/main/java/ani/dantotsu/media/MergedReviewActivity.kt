package ani.dantotsu.media

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.toComickReview
import ani.dantotsu.databinding.ActivityFollowBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.MalScraper
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.enableSettingsLongPress
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.tryWithSuspend
import com.xwray.groupie.Group
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.io.Serializable

/** What a source hands a custom tab so its arrow can open a page of several sources' items together. */
sealed interface MergedFeed

/** Where one source's reviews come from, for a page that shows several sources' together. */
sealed class ReviewFeed : MergedFeed, Serializable {
    /** One page of cards, and whether there are more. */
    abstract suspend fun load(page: Int): Pair<List<Group>, Boolean>

    data class AniList(val mediaId: Int) : ReviewFeed() {
        override suspend fun load(page: Int): Pair<List<Group>, Boolean> {
            val result = Anilist.query.getReviews(mediaId, page)?.data?.page
            return result?.reviews.orEmpty().map { ReviewAdapter(it) } to (result?.pageInfo?.hasNextPage == true)
        }
    }

    data class Mal(val malId: Int, val isAnime: Boolean) : ReviewFeed() {
        override suspend fun load(page: Int): Pair<List<Group>, Boolean> {
            val result = if (isAnime) MalScraper.getAnimeReviews(malId, page) else MalScraper.getMangaReviews(malId, page)
            return result?.reviews.orEmpty().map { MALReviewAdapter(it) } to (result?.hasNextPage == true)
        }
    }

    /** Comick sends its reviews along with the title, so there is only ever the one page. */
    data class Comick(val slug: String, val mediaType: String) : ReviewFeed() {
        override suspend fun load(page: Int): Pair<List<Group>, Boolean> {
            val reviews = ComickApi.getComicDetails(slug, mediaType = mediaType)?.comic?.reviews.orEmpty()
            return reviews.mapNotNull { raw ->
                try {
                    ComickReviewAdapter(raw.toComickReview())
                } catch (_: Exception) {
                    null
                }
            } to false
        }
    }
}

/**
 * The reviews of every source a custom tab merges. Each scroll to the end takes the next page from
 * all of them, so one with hundreds of reviews doesn't bury the others.
 */
class MergedReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFollowBinding
    private val adapter = GroupieAdapter()
    private var feeds: List<ReviewFeed> = emptyList()
    private var page = 1
    private var loading = false

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

        feeds = intent.getSerialized<ArrayList<ReviewFeed>>(EXTRA_FEEDS).orEmpty()
        if (feeds.isEmpty()) {
            finish()
            return
        }

        binding.followerGrid.visibility = View.GONE
        binding.followerList.visibility = View.GONE
        binding.followFilterButton.visibility = View.GONE
        binding.listTitle.text = getString(R.string.reviews)
        binding.listRecyclerView.adapter = adapter
        binding.listRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.listRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) loadMore()
            }
        })
        binding.listBack.enableSettingsLongPress()
        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.listProgressBar.visibility = View.VISIBLE
        loadMore()
    }

    private fun loadMore() {
        if (loading || feeds.isEmpty()) return
        loading = true
        val first = page == 1
        if (!first) binding.followRefresh.isVisible = true
        val current = feeds
        lifecycleScope.launch {
            val pages = current.map { feed ->
                async(Dispatchers.IO) { tryWithSuspend(snackbar = false) { feed.load(page) } }
            }.awaitAll()
            // Source order within a page, as on the tab.
            adapter.addAll(pages.flatMap { it?.first.orEmpty() })
            feeds = current.filterIndexed { index, _ -> pages[index]?.second == true }
            page++
            loading = false
            binding.listProgressBar.visibility = View.GONE
            binding.followRefresh.visibility = View.GONE
            if (first && adapter.itemCount == 0) {
                binding.listEmptyText.setText(R.string.no_reviews)
                binding.listEmptyContainer.visibility = View.VISIBLE
                binding.followSwipeRefresh.visibility = View.GONE
            }
        }
    }

    companion object {
        const val EXTRA_FEEDS = "feeds"
    }
}
