package ani.dantotsu.media

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.math.MathUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComickListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER_ID = "comick_list_user_id"
        const val EXTRA_LIST_SLUG = "comick_list_slug"
        const val EXTRA_LIST_TITLE = "comick_list_title"
    }

    private lateinit var binding: ActivityMediaListViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityMediaListViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initActivity(this)

        binding.listAppBar.setPadding(0, statusBarHeight, 0, 0)
        binding.mediaRecyclerView.setPadding(
            binding.mediaRecyclerView.paddingLeft,
            binding.mediaRecyclerView.paddingTop,
            binding.mediaRecyclerView.paddingRight,
            navBarHeight + 8f.px
        )
        binding.mediaRecyclerView.clipToPadding = false

        binding.mediaList.visibility = View.GONE
        binding.mediaGrid.visibility = View.GONE

        val userId = intent.getStringExtra(EXTRA_USER_ID) ?: run { finish(); return }
        val listSlug = intent.getStringExtra(EXTRA_LIST_SLUG) ?: run { finish(); return }
        val listTitle = intent.getStringExtra(EXTRA_LIST_TITLE)

        binding.listTitle.text = listTitle ?: listSlug
        binding.listTitle.isSelected = true
        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val spanCount = MathUtils.clamp(
            resources.displayMetrics.widthPixels / 124f.px, 1, 4
        )
        binding.mediaRecyclerView.layoutManager = GridLayoutManager(this, spanCount)

        lifecycleScope.launch {
            val comics = withContext(Dispatchers.IO) {
                ComickApi.getListComics(userId, listSlug)
            }
            if (!comics.isNullOrEmpty()) {
                val adapter = ComickListComicAdapter { comic ->
                    startActivity(
                        Intent(this@ComickListActivity, ComickMediaActivity::class.java)
                            .putExtra(ComickMediaActivity.EXTRA_SLUG, comic.slug)
                    )
                }
                adapter.appendItems(comics)
                binding.mediaRecyclerView.adapter = adapter
            }
        }
    }
}
