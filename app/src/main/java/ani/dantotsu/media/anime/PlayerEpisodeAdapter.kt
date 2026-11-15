package ani.dantotsu.media.anime

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemEpisodeListBinding
import ani.dantotsu.media.MediaNameAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.google.android.material.color.MaterialColors

/**
 * The player's list of episodes: the episode list screen's row, minus what belongs to that list,
 * the one playing marked "Now playing", and a tap plays another. Shared by the upright panel
 * ([PlayerPortraitPanel]) and the episode sheet ([PlayerEpisodesSheet]).
 */
class PlayerEpisodeAdapter(
    private val activity: AppCompatActivity,
    private val host: PlayerPortraitPanel.Host,
    /** After a tap has started another episode; the sheet closes itself here. */
    private val onPicked: () -> Unit = {},
) : RecyclerView.Adapter<PlayerEpisodeAdapter.Holder>() {
    private val media get() = host.media

    inner class Holder(val binding: ItemEpisodeListBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = host.episodeNumbers.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemEpisodeListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // The episode list's row, minus what belongs to that list: downloads, the browser
        // link and the selection's seen toggle.
        binding.itemEpisodeBrowser.isVisible = false
        binding.itemDownload.isVisible = false
        binding.itemDownloadProgress.isVisible = false
        binding.itemEpisodeViewed.isVisible = false
        binding.itemEpisodeDesc.isVisible = false
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val b = holder.binding
        val number = host.episodeNumbers[position]
        val ep = media.anime?.episodes?.get(number)
        val thumb = ep?.thumb?.let { if (it.url.isNotEmpty()) GlideUrl(it.url) { it.headers } else null }
        Glide.with(b.itemMediaImage).load(thumb ?: media.cover).override(400, 0).into(b.itemMediaImage)

        b.itemEpisodeNumber.text = number
        val title = ep?.title?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { MediaNameAdapter.removeEpisodeNumber(it) }?.takeIf { it.isNotBlank() }
        b.itemEpisodeTitle.text = title ?: activity.getString(R.string.episode_num, number)
        val filler = ep?.filler == true
        b.itemEpisodeFiller.isVisible = filler
        b.itemEpisodeFillerView.isVisible = filler

        val playing = position == host.currentIndex
        b.itemDownloadStatus.isVisible = playing
        if (playing) showNowPlaying(b.itemDownloadStatus)
        b.root.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(
                b.root,
                if (playing) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurfaceVariant
            )
        )
        val progress = media.userProgress
        b.itemEpisodeViewedCover.isVisible = !playing && progress != null &&
            (number.toFloatOrNull() ?: Float.MAX_VALUE) <= progress.toFloat()

        handleProgress(
            b.itemMediaProgressCont, b.itemMediaProgress, b.itemMediaProgressEmpty, media.id, number
        )
        b.root.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION && index != host.currentIndex) {
                host.playEpisode(index)
                onPicked()
            }
        }
    }

    /** "Now playing" under the playing episode's title, after an equaliser, in the accent colour. */
    private fun showNowPlaying(label: android.widget.TextView) {
        val accent = MaterialColors.getColor(label, androidx.appcompat.R.attr.colorPrimary)
        val size = (16 * label.resources.displayMetrics.density).toInt()
        val icon = androidx.core.content.ContextCompat.getDrawable(label.context, R.drawable.ic_round_equalizer_24)
            ?.mutate()?.apply {
                setBounds(0, 0, size, size)
                setTint(accent)
            }
        label.setCompoundDrawablesRelative(icon, null, null, null)
        label.compoundDrawablePadding = (4 * label.resources.displayMetrics.density).toInt()
        label.setTextColor(accent)
        label.setText(R.string.player_now_playing)
    }
}
