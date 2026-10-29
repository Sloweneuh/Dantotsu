package ani.dantotsu.media

import android.view.LayoutInflater
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.R
import ani.dantotsu.connections.ReleaseSourceIgnores
import ani.dantotsu.connections.ReleaseSourceIgnores.Provider
import ani.dantotsu.databinding.ItemReleaseSourceBinding
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.px

/**
 * The sheet behind a media page's progress line: each provider the newest chapter (or episode) is
 * taken from, what it currently reports, and whether it is believed — see [ReleaseSourceIgnores].
 * Opened by tapping the line; each toggle applies at once, so the line behind redraws as it goes.
 */
object ReleaseSourcesSheet {

    /** One provider and its current answer; [latest] null when it has said nothing yet. */
    data class Row(val provider: Provider, val latest: Int?, val source: String? = null)

    fun show(
        activity: FragmentActivity,
        /** The page's series, as [ReleaseSourceIgnores.anilist] or [ReleaseSourceIgnores.mu] names it. */
        target: String,
        anime: Boolean,
        rows: List<Row>,
        onChanged: () -> Unit,
    ) {
        val dialog = CustomBottomDialog.newInstance()
        dialog.setTitleText(activity.getString(R.string.release_sources))
        val inflater = LayoutInflater.from(activity)

        dialog.addView(TextView(activity).apply {
            setText(if (anime) R.string.release_sources_desc_anime else R.string.release_sources_desc)
            alpha = 0.75f
            textSize = 13f
            setPadding(24f.px, 0, 24f.px, 8f.px)
        })

        rows.forEach { row ->
            val b = ItemReleaseSourceBinding.inflate(inflater, null, false)
            fun bind() {
                val ignored = ReleaseSourceIgnores.isIgnored(row.provider, target)
                b.releaseSourceIcon.setImageResource(iconOf(row.provider))
                b.releaseSourceName.setText(row.provider.title)
                val value = row.latest?.let {
                    val number = activity.getString(
                        if (anime) R.string.episode_num else R.string.chapter_num, it.toString()
                    )
                    row.source?.takeIf { s -> s.isNotBlank() }?.let { s -> "$number · $s" } ?: number
                } ?: activity.getString(R.string.release_source_none)
                b.releaseSourceValue.text =
                    if (ignored) activity.getString(R.string.release_source_ignored_value, value) else value
                b.releaseSourceText.alpha = if (ignored) 0.5f else 1f
                b.releaseSourceToggle.setText(
                    if (ignored) R.string.release_source_track else R.string.release_source_ignore
                )
            }
            bind()
            b.releaseSourceToggle.setOnClickListener {
                ReleaseSourceIgnores.setIgnored(
                    row.provider, target, !ReleaseSourceIgnores.isIgnored(row.provider, target)
                )
                bind()
                onChanged()
            }
            dialog.addView(b.root)
        }
        dialog.show(activity.supportFragmentManager, "release_sources")
    }

    private fun iconOf(provider: Provider) = when (provider) {
        Provider.MALSYNC -> R.drawable.ic_round_malsync_notifications_24
        Provider.MANGAUPDATES -> R.drawable.ic_round_mangaupdates_24
        Provider.COMICK -> R.drawable.ic_round_comick_24
    }
}

/**
 * The scanlation group(s) behind Comick's newest chapter of [hid], as the Comick tab names them —
 * or null when Comick names none or can't be asked. Blocking network: call off the main thread.
 */
suspend fun comickGroupOf(hid: String?, nearChapter: Double?): String? {
    if (hid.isNullOrBlank()) return null
    val latest = runCatching {
        ani.dantotsu.connections.comick.ComickApi.getLatestChapter(hid, nearChapter = nearChapter)
    }.getOrNull() ?: return null
    return latest.group_name?.filter { it.isNotBlank() }?.joinToString(", ")
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
        ?.takeIf { it.isNotBlank() }
}
