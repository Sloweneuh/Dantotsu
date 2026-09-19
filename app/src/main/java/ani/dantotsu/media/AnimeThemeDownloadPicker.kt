package ani.dantotsu.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.animethemes.AnimeThemeDownloader
import ani.dantotsu.connections.animethemes.AnimeThemeDownloader.Format
import ani.dantotsu.connections.animethemes.AnimeThemeDownloader.Kind
import ani.dantotsu.connections.animethemes.AnimeThemeTrack
import ani.dantotsu.connections.animethemes.AnimeThemeVersion
import ani.dantotsu.connections.animethemes.AnimeThemeVideo
import ani.dantotsu.databinding.DialogThemeDownloadBinding
import ani.dantotsu.setSafeOnClickListener
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Asks what to save before saving it: source, video or audio, container, and quality.
 *
 * The four rows narrow each other down, because most combinations do not exist. A published file
 * can only be passed through in the container it already has, so webm and ogg offer the quality
 * it was published at and nothing else; mp4 and m4a are re-encodes and take the full ladder.
 * Picking a 720p source removes the 1080p rung, since upscaling only costs time.
 *
 * A sheet rather than a dialog: an AlertDialog hands its custom view a slice of the screen and
 * clips the rest, which four rows of chips overrun however tightly they are packed.
 */
class AnimeThemeDownloadPicker : BottomSheetDialogFragment() {

    private var _binding: DialogThemeDownloadBinding? = null
    private val binding get() = _binding!!

    private lateinit var state: State

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogThemeDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val track = arguments?.getSerializable("track") as? AnimeThemeTrack
        val version = arguments?.getSerializable("version") as? AnimeThemeVersion
        val sources = version?.let { AnimeThemeDownloader.sources(it) }.orEmpty()
        if (track == null || version == null || sources.isEmpty()) {
            dismiss(); return
        }
        state = State(sources.first())

        // A choice of one is noise, so a single published copy hides the row.
        val single = sources.size < 2
        binding.themeDownloadSource.visibility = if (single) View.GONE else View.VISIBLE
        binding.themeDownloadSourceLabel.visibility = if (single) View.GONE else View.VISIBLE
        bind(binding.themeDownloadSource, sources.map { it.label(requireContext()) }, 0) { index ->
            state.source = sources[index]
            state.format = null
            state.height = null
            refresh()
        }

        val kinds = listOf(Kind.VIDEO, Kind.AUDIO)
        bind(
            binding.themeDownloadType,
            listOf(getString(R.string.theme_video), getString(R.string.theme_audio)),
            0
        ) { index ->
            state.kind = kinds[index]
            state.format = null
            state.height = null
            refresh()
        }

        binding.themeDownloadConfirm.setSafeOnClickListener {
            AnimeThemeDownloader.start(requireContext(), option(track, version))
            dismiss()
        }

        refresh()
    }

    private fun refresh() {
        bindFormats()
        bindQuality()
    }

    private class State(var source: AnimeThemeVideo) {
        var kind: Kind = Kind.VIDEO
        var format: Format? = null
        var height: Int? = null
        var audioBitrate: Int? = null
    }

    private fun option(track: AnimeThemeTrack, version: AnimeThemeVersion) =
        AnimeThemeDownloader.option(
            track = track,
            version = version,
            source = state.source,
            format = state.format ?: Format.MP4,
            height = state.height,
            audioBitrate = state.audioBitrate
        )

    private fun bindFormats() {
        val formats = AnimeThemeDownloader.formats(state.source, state.kind)
        if (state.format !in formats) state.format = formats.firstOrNull()
        val selected = formats.indexOf(state.format).coerceAtLeast(0)
        bind(binding.themeDownloadFormat, formats.map { it.extension }, selected) { index ->
            state.format = formats[index]
            bindQuality()
        }
    }

    /**
     * Quality means lines for video and kbps for audio. A file that is only being passed through
     * has exactly one: the quality it was published at, shown rather than hidden so the row does
     * not appear and disappear as the format changes.
     */
    private fun bindQuality() {
        val format = state.format ?: return
        val group = binding.themeDownloadQuality

        if (!format.reEncodes) {
            val published = if (format.kind == Kind.VIDEO) {
                state.source.resolution?.let { "${it}p" }
            } else null
            bind(group, listOf(published ?: getString(R.string.theme_quality_published)), 0) { }
            return
        }

        if (format.kind == Kind.VIDEO) {
            val heights = AnimeThemeDownloader.heights(state.source)
            if (state.height !in heights) state.height = heights.firstOrNull()
            val selected = heights.indexOf(state.height).coerceAtLeast(0)
            bind(group, heights.map { "${it}p" }, selected) { state.height = heights[it] }
        } else {
            val rates = AnimeThemeDownloader.AUDIO_BITRATES
            if (state.audioBitrate !in rates) state.audioBitrate = rates.first()
            val selected = rates.indexOf(state.audioBitrate).coerceAtLeast(0)
            val labels = rates.map { getString(R.string.theme_bitrate, it) }
            bind(group, labels, selected) { state.audioBitrate = rates[it] }
        }
    }

    private fun bind(
        group: ChipGroup,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val context = requireContext()
        group.removeAllViews()
        labels.forEachIndexed { index, label ->
            group.addView(Chip(context).apply {
                text = label
                isCheckable = true
                isChecked = index == selected
                // chip_background_color and chip_text_color both key off state_selected, not
                // state_checked, so the selected chip needs both flags or its label keeps the
                // unselected colour and vanishes into the highlighted background.
                isSelected = index == selected
                setTextAppearance(R.style.Suffix)
                setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text_color))
                textSize = 12f
                // Four rows of chips at the 48dp minimum touch target make for a very tall sheet;
                // these sit right under the finger that opened them, so they take their own size.
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = CHIP_HEIGHT_DP * context.resources.displayMetrics.density
                chipBackgroundColor =
                    ContextCompat.getColorStateList(context, R.color.chip_background_color)
                setOnClickListener {
                    for (i in 0 until group.childCount) {
                        (group.getChildAt(i) as? Chip)?.apply {
                            isChecked = i == index
                            isSelected = i == index
                        }
                    }
                    onPick(index)
                }
            })
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        /** Material's own default for a chip, below the 48dp touch target it pads out to. */
        private const val CHIP_HEIGHT_DP = 32f

        fun newInstance(track: AnimeThemeTrack, version: AnimeThemeVersion) =
            AnimeThemeDownloadPicker().apply {
                arguments = Bundle().apply {
                    putSerializable("track", track)
                    putSerializable("version", version)
                }
            }
    }
}
