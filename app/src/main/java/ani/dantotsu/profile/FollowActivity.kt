package ani.dantotsu.profile

import ani.dantotsu.others.setViewModeIcon
import ani.dantotsu.px
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Rect
import ani.dantotsu.media.listColumns
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.User
import ani.dantotsu.databinding.ActivityFollowBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.enableSettingsLongPress
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.xwray.groupie.GroupieAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class FollowActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFollowBinding
    val adapter = GroupieAdapter()
    var users: List<User>? = null
    private var layoutType = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityFollowBinding.inflate(layoutInflater)
        binding.listToolbar.updateLayoutParams<MarginLayoutParams> { topMargin = statusBarHeight }
        binding.listFrameLayout.updateLayoutParams<MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        setContentView(binding.root)
        layoutType = if (PrefManager.getVal<Int>(PrefName.FollowerLayout) == 1) 1 else 0
        binding.followFilterButton.visibility = View.GONE
        binding.followerViewToggle.setViewModeIcon(showingGrid = layoutType == 1)
        binding.listRecyclerView.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.VERTICAL,
            false
        )
        binding.listRecyclerView.adapter = adapter
        binding.listProgressBar.visibility = View.VISIBLE
        binding.listBack.enableSettingsLongPress()
        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val title = intent.getStringExtra("title")
        val userID = intent.getIntExtra("userId", 0)
        binding.listTitle.text = title

        lifecycleScope.launch(Dispatchers.IO) {
            val respond = when (title) {
                "Following" -> Anilist.query.userFollowing(userID)?.data?.page?.following
                "Followers" -> Anilist.query.userFollowers(userID)?.data?.page?.followers
                else -> null
            }
            users = respond
            withContext(Dispatchers.Main) {
                fillList()
                binding.listProgressBar.visibility = View.GONE
            }
        }
        binding.followerViewToggle.setOnClickListener {
            layoutType = if (layoutType == 1) 0 else 1
            binding.followerViewToggle.setViewModeIcon(showingGrid = layoutType == 1, animate = true)
            PrefManager.setVal(PrefName.FollowerLayout, layoutType)
            fillList()
        }
        binding.followSwipeRefresh.setOnRefreshListener {
            binding.followSwipeRefresh.isRefreshing = false
        }
    }

    private fun fillList() {
        adapter.clear()
        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        // Rows side by side (a wide window's list view) need a gap; one column has the edges.
        if (binding.listRecyclerView.itemDecorationCount == 0) {
            binding.listRecyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
                ) {
                    val columns = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                    if (layoutType != 0 || columns < 2) return
                    outRect.left = 4f.px
                    outRect.right = 4f.px
                }
            })
        }
        binding.listRecyclerView.layoutManager = when (layoutType) {
            0 -> GridLayoutManager(this, listColumns(this))
            1 -> GridLayoutManager(
                this,
                (screenWidth / 120f).toInt(),
                GridLayoutManager.VERTICAL,
                false
            )

            else -> LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        }
        users?.forEach { user ->
            val username = SpannableString(user.name ?: "Unknown")
            if (layoutType == 0) {
                adapter.add(
                    FollowerItem(
                        false,
                        user.id,
                        username,
                        user.avatar?.medium,
                        user.bannerImage ?: user.avatar?.medium
                    ) { onUserClick(it) }
                )
            } else {
                adapter.add(
                    FollowerItem(
                        true,
                        user.id,
                        username,
                        user.avatar?.medium,
                        user.bannerImage ?: user.avatar?.medium
                    ) { onUserClick(it) }
                )
            }
        }
    }

    private fun onUserClick(id: Int) {
        val intent = Intent(this, ProfileActivity::class.java)
        intent.putExtra("userId", id)
        startActivity(intent)
    }
}