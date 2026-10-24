package ani.dantotsu.settings

import android.content.Intent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MUSeriesRecord
import ani.dantotsu.media.ComickSearchAdapter
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * Searches Comick for a title by hand, starting from the first of [titles] — the rest are offered
 * from the search bar's dropdown. Picking a result hands over its slug and HID (looked up when the
 * search result lacks it: sync writes by HID) and its title, and closes the sheet.
 *
 * For [ComickMatchesActivity]: matching a waiting title, and changing one matched by hand.
 */
internal fun AppCompatActivity.showComickPickSheet(
    titles: List<String>,
    isAnime: Boolean,
    onPicked: (slug: String, hid: String, comickTitle: String) -> Unit,
) {
    val dialog = BottomSheetDialog(this)
    val view = layoutInflater.inflate(R.layout.fragment_search_page, null)
    dialog.setContentView(view)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    view.minimumHeight = (resources.displayMetrics.heightPixels * 0.85f).toInt()

    val barLayout = view.findViewById<TextInputLayout>(R.id.searchBarLayout)
    val bar = view.findViewById<AutoCompleteTextView>(R.id.searchBarText)
    val progress = view.findViewById<View>(R.id.searchProgress)
    val empty = view.findViewById<TextView>(R.id.emptyMessage)
    val results = view.findViewById<RecyclerView>(R.id.searchRecyclerView)
    results.layoutManager = GridLayoutManager(
        this, (resources.displayMetrics.widthPixels / 124f.px).coerceIn(2, 4)
    )
    val mediaType = if (isAnime) ComickApi.MEDIA_TYPE_ANIME else ComickApi.MEDIA_TYPE_MANGA

    fun search(query: String) {
        if (query.isBlank()) return
        progress.isVisible = true
        empty.isVisible = false
        results.isVisible = false
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    ComickApi.searchComics(query, PrefManager.getVal<Boolean>(PrefName.AdultOnly), mediaType = mediaType)
                }.getOrNull()
            }
            progress.isVisible = false
            if (found.isNullOrEmpty()) {
                empty.isVisible = true
                return@launch
            }
            results.isVisible = true
            results.adapter = ComickSearchAdapter(found) { comic, _ ->
                val slug = comic.slug ?: return@ComickSearchAdapter
                lifecycleScope.launch {
                    // The HID is what sync writes to; the slug alone would need a lookup each run.
                    val hid = comic.hid ?: withContext(Dispatchers.IO) {
                        ComickApi.getComicDetails(slug, mediaType = mediaType)?.comic?.hid
                    }
                    if (hid == null) {
                        Snackbar.make(view, R.string.failed_fetch_comick, Snackbar.LENGTH_SHORT).show()
                        return@launch
                    }
                    dialog.dismiss()
                    onPicked(slug, hid, comic.title ?: slug)
                }
            }
        }
    }

    val choices = titles.ifEmpty { return }
    bar.setText(choices.first())
    if (choices.size > 1) {
        barLayout.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        barLayout.setEndIconOnClickListener {
            val popup = ListPopupWindow(this)
            popup.anchorView = bar
            popup.setAdapter(ArrayAdapter(this, R.layout.item_titles_dropdown, choices))
            popup.isModal = true
            popup.width = barLayout.width
            popup.setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.dropdown_background))
            popup.setOnItemClickListener { _, _, position, _ ->
                bar.setText(choices[position])
                popup.dismiss()
                search(choices[position])
            }
            popup.show()
        }
    }
    bar.setOnEditorActionListener { _, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
        search(bar.text.toString())
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(bar.windowToken, 0)
        true
    }
    dialog.show()
    search(choices.first())
}

/** The names to search Comick by for an AniList title: preferred, romaji, English, then synonyms. */
internal fun comickSearchTitles(media: Media): List<String> = buildList {
    add(media.userPreferredName)
    add(media.nameRomaji)
    media.name?.let { add(it) }
    addAll(media.synonyms)
}.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** The same for a MangaUpdates series: its main title, then every associated one. */
internal fun comickSearchTitles(record: MUSeriesRecord): List<String> =
    (listOfNotNull(record.title) + record.associated.orEmpty().mapNotNull { it.title })
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** AniList's format as a label; none where it only repeats the type ("Manga · Manga"). */
internal fun anilistFormatLabel(format: String?): String? = when (format) {
    null, "MANGA" -> null
    "TV" -> "TV"
    "TV_SHORT" -> "TV short"
    "ONA", "OVA" -> format
    else -> format.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

/** A title's own media page: AniList's, or MangaUpdates' for a series AniList lacks. */
internal fun AppCompatActivity.openTitlePage(media: Media?, mu: MUSeriesRecord?, cover: String?) {
    media?.let {
        startActivity(Intent(this, MediaDetailsActivity::class.java).putExtra("mediaId", it.id))
    }
    mu?.let { record ->
        val muMedia = MUMedia(
            id = record.seriesId,
            title = record.title,
            url = record.url,
            coverUrl = cover,
            listId = -1,
            userChapter = null,
            userVolume = null,
            latestChapter = record.latest_chapter?.toInt(),
            bayesianRating = record.bayesian_rating?.toDoubleOrNull(),
            priority = null,
            format = record.type,
        )
        startActivity(Intent(this, MUMediaDetailsActivity::class.java).putExtra("muMedia", muMedia as Serializable))
    }
}
