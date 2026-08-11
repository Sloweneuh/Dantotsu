package ani.dantotsu.others.imagesearch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.App.Companion.context
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityImageSearchBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.enableSettingsLongPress
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.friendlyErrorReason
import ani.dantotsu.util.hideEmptyState
import ani.dantotsu.util.showErrorWithReason
import ani.dantotsu.util.showNoResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageSearchBinding
    private val viewModel: ImageSearchViewModel by viewModels()

    private val imageSelectionLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { imageUri ->
                val contentResolver = applicationContext.contentResolver
                lifecycleScope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    val inputStream = contentResolver.openInputStream(imageUri)

                    if (inputStream != null) viewModel.analyzeImage(inputStream)
                    else toast(getString(R.string.error_loading_image))

                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initActivity(this)
        ThemeManager(this).applyTheme()
        binding = ActivityImageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.uploadImage.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.uploadImage.setOnClickListener {
            viewModel.clearResults()
            imageSelectionLauncher.launch("image/*")
        }
        binding.imageSearchTitle.enableSettingsLongPress()
        binding.imageSearchTitle.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        viewModel.searchResultLiveData.observe(this) { result ->
            result?.let { displayResult(it) }
        }

    }

    private fun displayResult(result: ImageSearchViewModel.SearchResult) {
        // viewModel.clearResults() posts this all-null sentinel right before the picker opens -
        // it isn't a search outcome, so it shouldn't toggle the placeholder either way.
        if (result.error == null && result.result == null && result.frameCount == null) {
            binding.imageSearchEmptyState.hideEmptyState()
            return
        }

        val searchResults: List<ImageSearchViewModel.ImageResult> = result.result.orEmpty()
        if (result.error != null) {
            binding.imageSearchEmptyState.showErrorWithReason(friendlyErrorReason(result.error))
            return
        }
        if (searchResults.isEmpty()) {
            binding.imageSearchEmptyState.showNoResults()
            return
        }
        binding.imageSearchEmptyState.hideEmptyState()

        val recyclerView: RecyclerView = findViewById(R.id.recyclerView)
        val adapter = ImageSearchResultAdapter(searchResults)

        adapter.setOnItemClickListener(object : ImageSearchResultAdapter.OnItemClickListener {
            override fun onItemClick(searchResult: ImageSearchViewModel.ImageResult) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val id = searchResult.anilist?.id?.toInt()
                    if (id == null) {
                        toast(getString(R.string.no_anilist_id_found))
                        return@launch
                    }
                    val media = Anilist.query.getMedia(id, false)

                    withContext(Dispatchers.Main) {
                        media?.let {
                            startActivity(
                                Intent(this@ImageSearchActivity, MediaDetailsActivity::class.java)
                                    .putExtra("media", it)
                            )
                        }
                    }
                }
            }
        })

        recyclerView.post {
            recyclerView.adapter = adapter
            recyclerView.layoutManager = LinearLayoutManager(context)
        }
    }
}