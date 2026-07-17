package ani.dantotsu.media

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.comick.ComickListComic
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.initActivity
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComickListFilter {
    var sort: String = "created_at"
    var selectedStatus = mutableListOf<Int>()
    var selectedCountry = mutableListOf<String>()
    var selectedContentRating = mutableListOf<String>()
    var selectedDemographic = mutableListOf<Int>()
    var translationCompleted: Boolean? = null
    var selectedGenres = mutableListOf<String>()
    var excludedGenres = mutableListOf<String>()
    var fromYear: Int? = null
    var toYear: Int? = null
    var minChapters: Int? = null

    fun isDefault(): Boolean =
        sort == "created_at" &&
            selectedStatus.isEmpty() &&
            selectedCountry.isEmpty() &&
            selectedContentRating.isEmpty() &&
            selectedDemographic.isEmpty() &&
            translationCompleted == null &&
            selectedGenres.isEmpty() &&
            excludedGenres.isEmpty() &&
            fromYear == null &&
            toYear == null &&
            minChapters == null

    fun activeChips(): List<String> {
        val out = mutableListOf<String>()
        if (sort != "created_at") {
            out += "Sort: " + when (sort) {
                "title" -> "Title"
                "bayesian_rating" -> "Rating"
                "uploaded_at" -> "Last Upload"
                else -> sort.replace('_', ' ')
            }
        }
        selectedStatus.forEach {
            out += when (it) { 1 -> "Ongoing"; 2 -> "Completed"; 3 -> "Cancelled"; 4 -> "Hiatus"; else -> "Status $it" }
        }
        selectedCountry.forEach {
            out += when (it) { "jp" -> "Manga"; "kr" -> "Manhwa"; "cn" -> "Manhua"; else -> it }
        }
        selectedContentRating.forEach { out += it.replaceFirstChar { c -> c.uppercase() } }
        selectedDemographic.forEach {
            out += when (it) { 1 -> "Shounen"; 2 -> "Shoujo"; 3 -> "Seinen"; 4 -> "Josei"; else -> "Demo $it" }
        }
        when (translationCompleted) {
            true -> out += "Completed only"
            false -> out += "Not completed"
            null -> Unit
        }
        if (fromYear != null || toYear != null) out += "Year: ${fromYear ?: "?"}-${toYear ?: "?"}"
        minChapters?.let { out += "Min chapters: $it" }
        selectedGenres.forEach { out += ComickApi.resolveGenreName(it) ?: it }
        excludedGenres.forEach { out += "−${ComickApi.resolveGenreName(it) ?: it}" }
        return out
    }
}

class ComickListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USER_ID = "comick_list_user_id"
        const val EXTRA_LIST_SLUG = "comick_list_slug"
        const val EXTRA_LIST_TITLE = "comick_list_title"
    }

    private lateinit var binding: ActivityMediaListViewBinding

    val filterState = ComickListFilter()
    private var allComics: List<ComickListComic> = emptyList()
    private var adapter: ComickListComicAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityMediaListViewBinding.inflate(layoutInflater)
        initActivity(this)

        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true
        } else {
            binding.root.fitsSystemWindows = false
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }

        setContentView(binding.root)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listAppBar.setBackgroundColor(primaryColor)

        binding.mediaList.visibility = View.GONE

        // Filter button
        binding.mediaGrid.visibility = View.VISIBLE
        binding.mediaGrid.setImageResource(R.drawable.ic_round_filter_alt_24)
        binding.mediaGrid.isClickable = true
        binding.mediaGrid.isFocusable = true
        binding.mediaGrid.setOnClickListener {
            ComickListFilterBottomSheet.newInstance()
                .show(supportFragmentManager, "ComickListFilter")
        }

        // Description button — shown once description is loaded
        binding.listDescription.visibility = View.GONE

        val userId = intent.getStringExtra(EXTRA_USER_ID) ?: run { finish(); return }
        val listSlug = intent.getStringExtra(EXTRA_LIST_SLUG) ?: run { finish(); return }
        val listTitle = intent.getStringExtra(EXTRA_LIST_TITLE)

        binding.listTitle.text = listTitle ?: listSlug
        binding.listTitle.isSelected = true
        binding.listBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val screenWidth = resources.displayMetrics.run { widthPixels / density }
        binding.mediaRecyclerView.layoutManager = GridLayoutManager(this, maxOf(1, (screenWidth / 120f).toInt()))

        lifecycleScope.launch {
            // Preload genres so chips and filtering work immediately after load
            launch(Dispatchers.IO) { ComickApi.getGenres() }

            val listMeta = withContext(Dispatchers.IO) {
                ComickApi.getUserLists(userId)?.firstOrNull { it.slug == listSlug }
            }
            val description = listMeta?.description?.takeIf { it.isNotBlank() }
            if (description != null) {
                binding.listDescription.setImageResource(R.drawable.ic_round_info_24)
                binding.listDescription.visibility = View.VISIBLE
                binding.listDescription.setOnClickListener {
                    val descView = TextView(this@ComickListActivity).apply {
                        setPadding(32, 16, 32, 16)
                        text = HtmlCompat.fromHtml(
                            description.replace("\n", "<br>"),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        )
                        textSize = 14f
                        movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    }
                    Linkify.addLinks(descView, Linkify.WEB_URLS)
                    CustomBottomDialog.newInstance().apply {
                        setTitleText(binding.listTitle.text.toString())
                        addView(descView)
                    }.show(supportFragmentManager, "listDesc")
                }
            }

            val comics = withContext(Dispatchers.IO) {
                ComickApi.getListComics(userId, listSlug)
            }
            if (!comics.isNullOrEmpty()) {
                allComics = comics
                adapter = ComickListComicAdapter { comic ->
                    startActivity(
                        Intent(this@ComickListActivity, ComickMediaActivity::class.java)
                            .putExtra(ComickMediaActivity.EXTRA_SLUG, comic.slug)
                    )
                }
                binding.mediaRecyclerView.adapter = adapter
                applyFilterAndDisplay()
            } else if (comics == null) {
                Logger.log("Comick list comics: API call failed for user $userId, list $listSlug")
                Toast.makeText(this@ComickListActivity, R.string.comick_list_load_failed, Toast.LENGTH_LONG).show()
            } else {
                val rating = listMeta?.content_rating
                if (rating != null && rating != "safe") {
                    Logger.log("Comick list comics: empty for user $userId, list $listSlug (content_rating=$rating, likely requires Comick login)")
                    Toast.makeText(this@ComickListActivity, R.string.comick_list_needs_login, Toast.LENGTH_LONG).show()
                } else {
                    Logger.log("Comick list comics: no comics returned for user $userId, list $listSlug")
                }
            }
        }
    }

    fun applyFilterAndDisplay() {
        val filtered = applyFilter(allComics)
        adapter?.setItems(filtered)
        updateChips()
    }

    private fun applyFilter(comics: List<ComickListComic>): List<ComickListComic> {
        var result = comics

        if (filterState.selectedStatus.isNotEmpty()) {
            result = result.filter { it.status in filterState.selectedStatus }
        }

        if (filterState.selectedCountry.isNotEmpty()) {
            result = result.filter { it.country in filterState.selectedCountry }
        }

        if (filterState.selectedDemographic.isNotEmpty()) {
            result = result.filter { it.demographic in filterState.selectedDemographic }
        }

        if (filterState.selectedContentRating.isNotEmpty()) {
            result = result.filter { it.content_rating in filterState.selectedContentRating }
        }

        filterState.translationCompleted?.let { completed ->
            result = result.filter { it.translation_completed == completed }
        }

        val from = filterState.fromYear
        val to = filterState.toYear
        if (from != null || to != null) {
            result = result.filter { comic ->
                val year = comic.year ?: return@filter false
                (from == null || year >= from) && (to == null || year <= to)
            }
        }

        filterState.minChapters?.let { min ->
            result = result.filter { (it.last_chapter ?: 0.0) >= min }
        }

        if (filterState.selectedGenres.isNotEmpty()) {
            val includedIds = filterState.selectedGenres
                .mapNotNull { ComickApi.resolveGenreId(it) }.toSet()
            if (includedIds.isNotEmpty()) {
                result = result.filter { comic ->
                    val comicGenres = comic.genres ?: return@filter false
                    includedIds.any { it in comicGenres }
                }
            }
        }

        if (filterState.excludedGenres.isNotEmpty()) {
            val excludedIds = filterState.excludedGenres
                .mapNotNull { ComickApi.resolveGenreId(it) }.toSet()
            if (excludedIds.isNotEmpty()) {
                result = result.filter { comic ->
                    val comicGenres = comic.genres ?: return@filter true
                    excludedIds.none { it in comicGenres }
                }
            }
        }

        result = when (filterState.sort) {
            "title" -> result.sortedBy { (it.title ?: "").lowercase() }
            "bayesian_rating" -> result.sortedByDescending { it.bayesian_rating?.toDoubleOrNull() ?: -1.0 }
            "uploaded_at" -> result.sortedByDescending { it.uploaded_at.orEmpty() }
            "created_at" -> result.sortedByDescending { it.created_at.orEmpty() }
            else -> result
        }

        return result
    }

    private fun updateChips() {
        val chips = filterState.activeChips()
        if (chips.isEmpty()) {
            binding.mediaFilterChipsScroll.visibility = View.GONE
            binding.mediaFilterChipGroup.removeAllViews()
            return
        }

        binding.mediaFilterChipsScroll.visibility = View.VISIBLE
        binding.mediaFilterChipGroup.removeAllViews()
        chips.forEach { label ->
            val chip = Chip(this)
            chip.text = label
            chip.isCloseIconVisible = true
            chip.chipBackgroundColor = ContextCompat.getColorStateList(this, R.color.chip_background_color)
            chip.chipStrokeColor = ColorStateList.valueOf(
                getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
            )
            chip.setTextAppearance(R.style.Suffix)
            chip.textSize = 14f
            chip.setOnClickListener { removeChip(label) }
            chip.setOnCloseIconClickListener { removeChip(label) }
            binding.mediaFilterChipGroup.addView(chip)
        }
    }

    private fun removeChip(label: String) {
        // Sort chip
        if (label.startsWith("Sort: ")) {
            filterState.sort = "created_at"
            applyFilterAndDisplay()
            return
        }
        // Status chips
        val statusMap = mapOf("Ongoing" to 1, "Completed" to 2, "Cancelled" to 3, "Hiatus" to 4)
        statusMap[label]?.let { filterState.selectedStatus.remove(it); applyFilterAndDisplay(); return }
        // Country chips
        val countryMap = mapOf("Manga" to "jp", "Manhwa" to "kr", "Manhua" to "cn")
        countryMap[label]?.let { filterState.selectedCountry.remove(it); applyFilterAndDisplay(); return }
        // Content rating (capitalised display → lowercase value)
        val ratingVal = label.lowercase()
        if (filterState.selectedContentRating.remove(ratingVal)) { applyFilterAndDisplay(); return }
        // Demographic
        val demoMap = mapOf("Shounen" to 1, "Shoujo" to 2, "Seinen" to 3, "Josei" to 4)
        demoMap[label]?.let { filterState.selectedDemographic.remove(it); applyFilterAndDisplay(); return }
        // Translation
        if (label == "Completed only") { filterState.translationCompleted = null; applyFilterAndDisplay(); return }
        if (label == "Not completed") { filterState.translationCompleted = null; applyFilterAndDisplay(); return }
        // Year range
        if (label.startsWith("Year: ")) { filterState.fromYear = null; filterState.toYear = null; applyFilterAndDisplay(); return }
        // Minimum chapters
        if (label.startsWith("Min chapters: ")) { filterState.minChapters = null; applyFilterAndDisplay(); return }
        // Genres (excluded start with −)
        if (label.startsWith("−")) {
            val name = label.removePrefix("−")
            val slug = ComickApi.resolveGenreSlugByName(name) ?: name
            filterState.excludedGenres.remove(slug)
            applyFilterAndDisplay()
            return
        }
        // Included genre
        val slug = ComickApi.resolveGenreSlugByName(label) ?: label
        filterState.selectedGenres.remove(slug)
        applyFilterAndDisplay()
    }
}
