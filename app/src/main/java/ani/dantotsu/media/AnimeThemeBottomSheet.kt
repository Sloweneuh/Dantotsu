package ani.dantotsu.media

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.animethemes.AnimeThemeTrack
import ani.dantotsu.connections.animethemes.AnimeThemeVersion
import ani.dantotsu.connections.animethemes.AnimeThemeVideo
import ani.dantotsu.databinding.BottomSheetAnimeThemeBinding
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.snackString
import com.google.android.material.chip.Chip

/**
 * Plays one opening/ending and shows what AnimeThemes knows about it.
 *
 * A theme can have been re-cut during the run, and each cut can exist in several copies (a 1080p
 * BD rip, a 720p WEB one), so the sheet carries two chip rows: which version aired over which
 * episodes, and which copy of it to play. Downloading asks which file to save first: every copy
 * of the playing version, plus the audio track on its own for anyone who only wants the song.
 */
@OptIn(UnstableApi::class)
class AnimeThemeBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAnimeThemeBinding? = null
    private val binding get() = _binding!!

    private var track: AnimeThemeTrack? = null
    private var player: ExoPlayer? = null

    private var version: AnimeThemeVersion? = null
    private var video: AnimeThemeVideo? = null

    /** Set while a drag is in progress, so the ticker doesn't fight the thumb. */
    private var seeking = false

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            _binding?.root?.postDelayed(this, 500)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAnimeThemeBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val current = arguments?.getSerializable("track") as? AnimeThemeTrack
            ?: run { dismiss(); return }
        track = current

        binding.themeSheetTitle.text = listOfNotNull(current.slug, current.song).joinToString(" · ")
        binding.themeSheetArtists.text = current.artists.joinToString(", ")
        binding.themeSheetArtists.visibility =
            if (current.artists.isEmpty()) View.GONE else View.VISIBLE

        binding.themeSheetDownload.setSafeOnClickListener { download() }

        player = ExoPlayer.Builder(requireContext()).build().also { exo ->
            binding.themeSheetPlayer.player = exo
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPause(isPlaying)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    updateProgress()
                }
            })
        }

        binding.themeSheetPlayPause.setOnClickListener { togglePlay() }
        binding.themeSheetPlayer.setOnClickListener { togglePlay() }

        binding.themeSheetSeek.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.themeSheetPosition.text = formatTime(positionOf(progress))
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                player?.seekTo(positionOf(bar.progress))
            }
        })

        buildVersionChips(current)
        buildSearchChips(current)
        selectVersion(current.versions.firstOrNull())
        binding.root.postDelayed(tick, 500)
    }

    private fun buildVersionChips(track: AnimeThemeTrack) {
        binding.themeSheetVersions.removeAllViews()
        // A single cut needs no picker — the episode range is already in the meta line.
        if (track.versions.size < 2) {
            binding.themeSheetVersions.visibility = View.GONE
            return
        }
        binding.themeSheetVersions.visibility = View.VISIBLE
        track.versions.forEachIndexed { index, entry ->
            val label = entry.episodesLabel(requireContext())
                ?: getString(R.string.theme_version, entry.version ?: (index + 1))
            // The warning belongs on the chip itself: picking a cut is exactly the moment someone
            // would want to know this one spoils something.
            val flags = listOfNotNull(
                if (entry.spoiler) getString(R.string.spoiler_tag) else null,
                if (entry.nsfw) getString(R.string.adult_tags) else null
            )
            val text = if (flags.isEmpty()) label else "$label · ${flags.joinToString(" · ")}"
            binding.themeSheetVersions.addView(chip(text) { selectVersion(entry) })
        }
    }

    /**
     * AnimeThemes carries Spotify/Apple Music links in its schema but has not backfilled them,
     * so these are searches rather than links: the song and its artists handed to each service,
     * which its app picks up when installed.
     */
    private fun buildSearchChips(track: AnimeThemeTrack) {
        val query = listOfNotNull(track.song, track.artists.joinToString(" ").takeIf {
            it.isNotBlank()
        }).joinToString(" ").trim()
        binding.themeSheetSearch.removeAllViews()
        val hasQuery = query.isNotEmpty()
        binding.themeSheetSearch.visibility = if (hasQuery) View.VISIBLE else View.GONE
        binding.themeSheetSearchLabel.visibility = if (hasQuery) View.VISIBLE else View.GONE
        if (!hasQuery) return
        // Uri.encode, not URLEncoder: Spotify and Deezer take the terms as a path segment, where
        // URLEncoder's '+' would be searched for literally.
        val encoded = Uri.encode(query)
        MUSIC_SERVICES.forEach { service ->
            binding.themeSheetSearch.addView(
                chip(service.name, checkable = false, icon = service.icon) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, service.url.format(encoded).toUri())
                        )
                    } catch (_: Throwable) {
                    }
                }
            )
        }
    }

    private fun selectVersion(entry: AnimeThemeVersion?) {
        version = entry ?: return
        check(binding.themeSheetVersions, track?.versions?.indexOf(entry) ?: 0)

        binding.themeSheetVideos.removeAllViews()
        if (entry.videos.size < 2) {
            binding.themeSheetVideos.visibility = View.GONE
        } else {
            binding.themeSheetVideos.visibility = View.VISIBLE
            entry.videos.forEach { candidate ->
                binding.themeSheetVideos.addView(
                    chip(candidate.label(requireContext())) { selectVideo(candidate) }
                )
            }
        }
        selectVideo(entry.videos.firstOrNull())
    }

    private fun selectVideo(candidate: AnimeThemeVideo?) {
        video = candidate ?: return
        check(binding.themeSheetVideos, version?.videos?.indexOf(candidate) ?: 0)
        updateMeta()
        play()
    }

    private fun updateMeta() {
        val entry = version ?: return
        val parts = listOfNotNull(
            entry.episodesLabel(requireContext()),
            version?.let {
                if (it.version != null && (track?.versions?.size ?: 0) > 1)
                    getString(R.string.theme_version, it.version) else null
            },
            video?.label(requireContext()),
            if (entry.spoiler) getString(R.string.spoiler_tag) else null,
            if (entry.nsfw) getString(R.string.adult_tags) else null,
            entry.notes
        )
        binding.themeSheetMeta.text = parts.joinToString(" · ")
        binding.themeSheetMeta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun togglePlay() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    private fun updatePlayPause(isPlaying: Boolean) {
        _binding?.themeSheetPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24
        )
    }

    private fun updateProgress() {
        val binding = _binding ?: return
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        binding.themeSheetDuration.text = if (duration > 0) formatTime(duration) else ""
        if (seeking) return
        binding.themeSheetPosition.text = formatTime(exo.currentPosition)
        binding.themeSheetSeek.progress =
            if (duration > 0) ((exo.currentPosition * 1000) / duration).toInt() else 0
    }

    private fun positionOf(progress: Int): Long {
        val duration = player?.duration?.takeIf { it > 0 } ?: return 0
        return duration * progress / 1000
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun play() {
        val url = video?.link ?: return
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * A theme exists as several rips, as an audio track on its own, and — once re-encoded —
     * in whatever smaller shape the user actually wants, so the button asks before saving.
     */
    private fun download() {
        val current = track ?: return
        val entry = version ?: return
        AnimeThemeDownloadPicker.newInstance(current, entry)
            .show(parentFragmentManager, "themeDownload")
    }

    private fun chip(
        text: String,
        checkable: Boolean = true,
        @DrawableRes icon: Int? = null,
        onClick: () -> Unit
    ): Chip = Chip(requireContext()).apply {
        this.text = text
        isCheckable = checkable
        icon?.let {
            chipIcon = ContextCompat.getDrawable(requireContext(), it)
            // The brand marks are solid black paths, so they have to follow the theme the way
            // the chip's own label does, or they disappear against a dark background.
            chipIconTint = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.bg_opp)
            )
        }
        setTextAppearance(R.style.Suffix)
        // After the text appearance, not before: applying one resets the colour, which is what
        // left the selected chip's label sitting invisibly on its own highlight. The state lists
        // key off state_selected rather than state_checked, hence both flags on selection.
        if (checkable) {
            setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.chip_text_color))
        }
        textSize = 12f
        chipBackgroundColor =
            ContextCompat.getColorStateList(requireContext(), R.color.chip_background_color)
        setOnClickListener {
            if (checkable) {
                isChecked = true
                isSelected = true
            }
            onClick()
        }
    }

    /** ChipGroup's own single-selection only fires on user taps, so the initial one is set here. */
    private fun check(group: ViewGroup, index: Int) {
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? Chip)?.apply {
                isChecked = i == index
                isSelected = i == index
            }
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroyView() {
        binding.root.removeCallbacks(tick)
        binding.themeSheetPlayer.player = null
        player?.release()
        player = null
        _binding = null
        super.onDestroyView()
    }

    private data class MusicService(
        val name: String,
        /** `%s` is the search terms, already percent-encoded. */
        val url: String,
        @DrawableRes val icon: Int
    )

    companion object {
        private val MUSIC_SERVICES = listOf(
            MusicService("Spotify", "https://open.spotify.com/search/%s", R.drawable.ic_spotify),
            MusicService(
                "YouTube Music",
                "https://music.youtube.com/search?q=%s",
                R.drawable.ic_youtubemusic
            ),
            MusicService(
                "Apple Music",
                "https://music.apple.com/search?term=%s",
                R.drawable.ic_applemusic
            ),
            MusicService(
                "Amazon Music",
                "https://music.amazon.com/search/%s",
                R.drawable.ic_amazonmusic
            ),
            MusicService("Deezer", "https://www.deezer.com/search/%s", R.drawable.ic_deezer),
            MusicService(
                "YouTube",
                "https://www.youtube.com/results?search_query=%s",
                R.drawable.ic_youtube
            ),
        )

        fun newInstance(track: AnimeThemeTrack) = AnimeThemeBottomSheet().apply {
            arguments = Bundle().apply { putSerializable("track", track) }
        }
    }
}
