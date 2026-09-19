package ani.dantotsu.media

import android.view.View
import ani.dantotsu.R
import ani.dantotsu.connections.animethemes.AnimeThemeTrack
import ani.dantotsu.databinding.ItemAnimeThemeBinding
import ani.dantotsu.setSafeOnClickListener
import com.xwray.groupie.viewbinding.BindableItem

/**
 * One opening/ending in the info tab's theme list: label, song, artists, and a line of the
 * details that differ between them. Tapping anywhere plays it.
 */
class AnimeThemeAdapter(
    private val track: AnimeThemeTrack,
    private val onPlay: (AnimeThemeTrack) -> Unit
) : BindableItem<ItemAnimeThemeBinding>() {

    override fun bind(viewBinding: ItemAnimeThemeBinding, position: Int) {
        viewBinding.themeSlug.text = track.slug
        viewBinding.themeSong.text = track.song ?: track.slug

        viewBinding.themeArtists.text = track.artists.joinToString(", ")
        viewBinding.themeArtists.visibility =
            if (track.artists.isEmpty()) View.GONE else View.VISIBLE

        // The row shows the first cut's details; the rest are one tap away in the sheet.
        val version = track.versions.firstOrNull()
        val context = viewBinding.root.context
        val meta = listOfNotNull(
            track.group,
            version?.episodesLabel(context),
            version?.videos?.firstOrNull()?.label(context),
            if (track.versions.size > 1)
                context.getString(R.string.theme_version_count, track.versions.size) else null
        )
        viewBinding.themeMeta.text = meta.joinToString(" · ")
        viewBinding.themeMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

        // Spoiler and NSFW belong on the row, not behind a tap: they are the reason someone
        // would decide *not* to open it. Scoped to the cut they apply to, because a theme whose
        // third version spoils one episode is not a spoiler theme.
        val flags = listOfNotNull(
            flag(context, R.string.spoiler_tag, R.string.theme_flag_eps, track) { it.spoiler },
            flag(context, R.string.adult_tags, R.string.theme_flag_eps, track) { it.nsfw }
        )
        viewBinding.themeFlags.text = flags.joinToString(" · ")
        viewBinding.themeFlags.visibility = if (flags.isEmpty()) View.GONE else View.VISIBLE

        viewBinding.root.setSafeOnClickListener { onPlay(track) }
    }

    /**
     * "Spoiler" when every cut of the theme carries it, "Spoiler: eps 28" when only some do.
     */
    private fun flag(
        context: android.content.Context,
        labelRes: Int,
        scopedRes: Int,
        track: AnimeThemeTrack,
        predicate: (ani.dantotsu.connections.animethemes.AnimeThemeVersion) -> Boolean
    ): String? {
        val flagged = track.versions.filter(predicate)
        if (flagged.isEmpty()) return null
        val label = context.getString(labelRes)
        if (flagged.size == track.versions.size) return label
        val episodes = flagged.mapNotNull { it.episodesLabel(context) }.joinToString(", ")
        return if (episodes.isBlank()) label
        else context.getString(scopedRes, label, episodes)
    }

    override fun getLayout(): Int = R.layout.item_anime_theme

    override fun initializeViewBinding(view: View): ItemAnimeThemeBinding =
        ItemAnimeThemeBinding.bind(view)
}
