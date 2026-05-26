package ani.dantotsu.media

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityFollowBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.Jikan
import ani.dantotsu.others.MALReview
import ani.dantotsu.others.toMALReview
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MALReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFollowBinding
    private val adapter = GroupieAdapter()
    private val reviews = mutableListOf<MALReview>()
    private var malId = 0
    private var isAnime = true
    private var currentPage = 1
    private var hasNextPage = true

    @SuppressLint("ClickableViewAccessibility")
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

        malId = intent.getIntExtra("malId", -1)
        isAnime = intent.getBooleanExtra("isAnime", true)

        if (malId == -1) {
            finish()
            return
        }

        binding.followerGrid.visibility = View.GONE
        binding.followerList.visibility = View.GONE
        binding.followFilterButton.visibility = View.GONE
        binding.listTitle.text = getString(R.string.reviews)
        binding.listRecyclerView.adapter = adapter
        binding.listRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.listProgressBar.visibility = View.VISIBLE
        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        lifecycleScope.launch(Dispatchers.IO) {
            val response = if (isAnime) Jikan.getAnimeReviews(malId) else Jikan.getMangaReviews(malId)
            withContext(Dispatchers.Main) {
                binding.listProgressBar.visibility = View.GONE
                binding.listRecyclerView.setOnTouchListener { _, event ->
                    if (event?.action == MotionEvent.ACTION_UP) {
                        if (hasNextPage && !binding.listRecyclerView.canScrollVertically(1) && !binding.followRefresh.isVisible
                            && binding.listRecyclerView.adapter!!.itemCount != 0 &&
                            (binding.listRecyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() == (binding.listRecyclerView.adapter!!.itemCount - 1)
                        ) {
                            binding.followRefresh.visibility = ViewGroup.VISIBLE
                            loadPage(++currentPage) {
                                binding.followRefresh.visibility = ViewGroup.GONE
                            }
                        }
                    }
                    false
                }
                response?.data?.map { it.toMALReview() }?.let {
                    reviews.addAll(it)
                    hasNextPage = response.pagination?.hasNextPage == true
                    fillList()
                }
            }
        }
    }

    private fun loadPage(page: Int, callback: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val response = if (isAnime) Jikan.getAnimeReviews(malId, page) else Jikan.getMangaReviews(malId, page)
            withContext(Dispatchers.Main) {
                hasNextPage = response?.pagination?.hasNextPage == true
                response?.data?.map { it.toMALReview() }?.let {
                    reviews.addAll(it)
                    fillList()
                }
                callback()
            }
        }
    }

    private fun fillList() {
        adapter.clear()
        reviews.forEach { adapter.add(MALReviewAdapter(it)) }
    }
}
