package ani.dantotsu.media

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.StringRes
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTrackerEpisodeBinding
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade

/**
 * One tracker-agnostic episode row plus the two ways the Kitsu / Simkl pages show them:
 * the full `item_tracker_episode` column (standalone activity's Episodes tab) and a short
 * "recent episodes" preview with a "more" arrow (info tab — mirrors the Comick anime info tab).
 */
object TrackerEpisodeRenderer {

    data class EpisodeRow(
        val number: String,
        val title: String?,
        val desc: String?,
        val thumbUrl: String?,
        /** Raw ISO date; formatted to "29 Sep 2023" at render time. */
        val date: String? = null,
        /** Short badge prefix for non-standard entries, e.g. "S" for a special. */
        val numberPrefix: String? = null,
    )

    /** Full vertical list — one `item_tracker_episode` row per episode. */
    fun renderList(activity: AppCompatActivity, parent: ViewGroup, rows: List<EpisodeRow>, coverUrl: String? = null) {
        rows.forEach { row -> parent.addView(buildRow(activity, parent, row, coverUrl)) }
    }

    /**
     * `ItemTitleRecyclerBinding` header + the first [take] rows + a "more" arrow. [rows] should
     * already be in the order to preview (newest first for an airing show) — including whatever
     * unaired/special entries sort newest; [coverUrl] fills in for a row with no thumbnail of its
     * own, same as Kitsu/Simkl's own episode lists do.
     */
    fun renderPreview(
        activity: AppCompatActivity,
        parent: ViewGroup,
        rows: List<EpisodeRow>,
        take: Int,
        @StringRes titleRes: Int,
        coverUrl: String? = null,
        onMore: () -> Unit,
    ) {
        if (rows.isEmpty()) return
        val header = ItemTitleRecyclerBinding.inflate(activity.layoutInflater, parent, false)
        header.itemTitle.setText(titleRes)
        header.itemRecycler.visibility = View.GONE
        header.itemMore.visibility = View.VISIBLE
        header.itemMore.setSafeOnClickListener { onMore() }
        parent.addView(header.root)
        rows.take(take).forEach { row -> parent.addView(buildRow(activity, parent, row, coverUrl)) }
    }

    private fun buildRow(activity: AppCompatActivity, parent: ViewGroup, row: EpisodeRow, coverUrl: String?): View {
        val b = ItemTrackerEpisodeBinding.inflate(activity.layoutInflater, parent, false)

        // The tracker's own metadata for an episode (especially the newest one, before it's aired)
        // can lag behind: no thumbnail of its own yet. Kitsu/Simkl's own episode lists fall back to
        // the show's poster in that case rather than leaving a blank rectangle — do the same.
        val imageUrl = row.thumbUrl?.takeIf { it.isNotBlank() } ?: coverUrl?.takeIf { it.isNotBlank() }
        if (imageUrl == null) {
            b.trackerEpisodeThumb.setImageDrawable(null)
        } else {
            // Rows can be inflated into a not-yet-laid-out (or still GONE) container — e.g. the
            // Episodes tab content built before that tab is first shown — where the thumbnail's
            // match_parent bounds can't be resolved from a real measure pass. An explicit pixel
            // target sidesteps that entirely instead of leaving the request stuck pending.
            Glide.with(activity).load(imageUrl).centerCrop()
                .override(140f.px, 79f.px)
                .transition(withCrossFade()).into(b.trackerEpisodeThumb)
        }

        b.trackerEpisodeNumber.text = row.numberPrefix?.let { "$it ${row.number}".uppercase() }
            ?: activity.getString(R.string.episode_number, row.number)
        b.trackerEpisodeTitle.text = row.title?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.episode_number, row.number)

        val dateText = TrackerFmt.date(row.date)
        b.trackerEpisodeDate.visibility = if (dateText.isNullOrBlank()) View.GONE else View.VISIBLE
        b.trackerEpisodeDate.text = dateText.orEmpty()

        val desc = row.desc?.takeIf { it.isNotBlank() }
        b.trackerEpisodeDesc.visibility = if (desc == null) View.GONE else View.VISIBLE
        b.trackerEpisodeDesc.text = desc.orEmpty()

        b.root.setOnClickListener {
            b.trackerEpisodeDesc.maxLines = if (b.trackerEpisodeDesc.maxLines == 3) 100 else 3
        }
        b.root.setOnLongClickListener {
            copyToClipboard("${b.trackerEpisodeTitle.text}\n\n${row.desc.orEmpty()}".trim())
            true
        }
        return b.root
    }
}
