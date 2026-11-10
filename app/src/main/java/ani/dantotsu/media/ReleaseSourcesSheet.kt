package ani.dantotsu.media

import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ani.dantotsu.R
import ani.dantotsu.connections.ReleaseSourceIgnores
import ani.dantotsu.connections.ReleaseSourceIgnores.Provider
import ani.dantotsu.databinding.ItemReleaseSourceBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.px
import ani.dantotsu.settings.ReleaseSourceIgnoresSheet

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
        /** The page's series, its target as [ReleaseSourceIgnores.anilist] or [ReleaseSourceIgnores.mu] names it. */
        series: ReleaseSourceIgnores.Series,
        anime: Boolean,
        rows: List<Row>,
        /** The series' Comick entry, whose groups the Comick row opens onto; none, no chevron. */
        comickHid: String? = null,
        onChanged: () -> Unit,
    ) {
        val target = series.target
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
                    row.provider, target, !ReleaseSourceIgnores.isIgnored(row.provider, target), series
                )
                bind()
                onChanged()
            }
            dialog.addView(b.root)
            if (row.provider == Provider.COMICK && !comickHid.isNullOrBlank()) {
                val groups = comickGroups(activity, series, comickHid, onChanged)
                b.releaseSourceExpand.isVisible = true
                val toggle = android.view.View.OnClickListener {
                    (groups.tag as Runnable).run()
                    b.releaseSourceExpand.animate().rotation(if (groups.isVisible) 180f else 0f)
                        .setDuration(200).start()
                    b.releaseSourceExpand.contentDescription =
                        activity.getString(if (groups.isVisible) R.string.collapse else R.string.expand)
                }
                b.releaseSourceExpand.setOnClickListener(toggle)
                b.releaseSourceText.setOnClickListener(toggle)
                dialog.addView(groups)
            }
        }
        dialog.addView(TextView(activity).apply {
            setText(R.string.release_sources_manage)
            setTextColor(activity.getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            textSize = 14f
            setPadding(24f.px, 16f.px, 24f.px, 16f.px)
            setOnClickListener {
                dialog.dismiss()
                ReleaseSourceIgnoresSheet().apply { onDismissed = onChanged }
                    .show(activity.supportFragmentManager, "release_sources_manage")
            }
        })
        dialog.show(activity.supportFragmentManager, "release_sources")
    }

    /**
     * The lines under the Comick row, one per scanlation group with its newest upload — fetched
     * when first opened — each ignored or believed again on its own. A group that numbers its
     * uploads ahead of the real chapters is left out this way without losing what the others
     * upload. Groups ignored here that haven't uploaded lately are listed too, to take them back.
     */
    private fun comickGroups(
        activity: FragmentActivity,
        series: ReleaseSourceIgnores.Series,
        hid: String,
        onChanged: () -> Unit,
    ): android.widget.LinearLayout {
        val target = series.target
        val inflater = LayoutInflater.from(activity)
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            isVisible = false
        }
        var loaded = false

        fun status(text: Int) {
            container.removeAllViews()
            container.addView(TextView(activity).apply {
                setText(text)
                alpha = 0.6f
                textSize = 13f
                setPadding(64f.px, 4f.px, 24f.px, 8f.px)
            })
        }

        fun render(latest: List<Pair<String, ani.dantotsu.connections.comick.ComickChapter>>) {
            container.removeAllViews()
            val recent = latest.map { it.first.lowercase() }.toSet()
            val stale = ReleaseSourceIgnores.ignoredSeries().firstOrNull { it.series.target == target }
                ?.groups.orEmpty().filter { it.lowercase() !in recent }
            val lines = latest.map { (group, chapter) -> group to chapter.chap } + stale.map { it to null }
            if (lines.isEmpty()) {
                status(R.string.release_source_groups_none)
                return
            }
            lines.forEach { (group, chap) ->
                val b = ItemReleaseSourceBinding.inflate(inflater, container, false)
                b.root.setPadding(24f.px, 2f.px, 24f.px, 2f.px)
                // Kept, not shown: it lines the group up under the Comick name.
                b.releaseSourceIcon.visibility = android.view.View.INVISIBLE
                b.releaseSourceName.text = group
                b.releaseSourceName.textSize = 14f
                fun bind() {
                    val ignored = ReleaseSourceIgnores.isComickGroupIgnored(target, group)
                    val value = chap?.let { activity.getString(R.string.chapter_num, it) }
                        ?: activity.getString(R.string.release_source_none)
                    b.releaseSourceValue.text =
                        if (ignored) activity.getString(R.string.release_source_ignored_value, value) else value
                    b.releaseSourceText.alpha = if (ignored) 0.5f else 1f
                    b.releaseSourceToggle.setText(
                        if (ignored) R.string.release_source_track else R.string.release_source_ignore
                    )
                }
                bind()
                b.releaseSourceToggle.setOnClickListener {
                    ReleaseSourceIgnores.setComickGroupIgnored(
                        target, group, !ReleaseSourceIgnores.isComickGroupIgnored(target, group), series
                    )
                    bind()
                    onChanged()
                }
                container.addView(b.root)
            }
        }

        container.tag = Runnable {
            container.isVisible = !container.isVisible
            if (!container.isVisible || loaded) return@Runnable
            loaded = true
            status(R.string.release_source_groups_loading)
            activity.lifecycleScope.launch {
                val latest = ani.dantotsu.connections.comick.ComickApi.latestByGroup(hid)
                if (latest == null) {
                    loaded = false
                    status(R.string.release_source_groups_failed)
                } else render(latest)
            }
        }
        return container
    }

    private fun iconOf(provider: Provider) = when (provider) {
        Provider.MALSYNC -> R.drawable.ic_malsync_notifications
        Provider.MANGAUPDATES -> R.drawable.ic_mangaupdates
        Provider.COMICK -> R.drawable.ic_comick
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
    return groupLabel(latest.group_name)
}

private fun groupLabel(groups: List<String>?): String? =
    groups?.filter { it.isNotBlank() }?.joinToString(", ")
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
        ?.takeIf { it.isNotBlank() }

/**
 * Comick's newest chapter of [hid] as the page for [target] believes it, with the group(s) that
 * uploaded it: the catalog's own [lastChapter] — unless groups are ignored for [target], when it's
 * the newest anyone else uploaded. Null when there's none or Comick can't be asked. Blocking
 * network: call off the main thread.
 */
suspend fun comickLatestFor(hid: String?, lastChapter: Double?, target: String): Pair<Int, String?>? {
    val ignored = ReleaseSourceIgnores.comickGroups(target)
    if (ignored.isEmpty()) {
        val last = lastChapter?.toInt()?.takeIf { it > 0 } ?: return null
        return last to comickGroupOf(hid, lastChapter)
    }
    if (hid.isNullOrBlank()) return null
    val chapter = runCatching {
        ani.dantotsu.connections.comick.ComickApi.getLatestChapter(hid, ignoredGroups = ignored)
    }.getOrNull() ?: return null
    val last = chapter.chap?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: return null
    return last to groupLabel(chapter.group_name)
}
