package ani.dantotsu.profile

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.databinding.FragmentStatisticsBinding
import ani.dantotsu.databinding.ItemStatSectionBinding
import ani.dantotsu.databinding.ItemStatSummaryBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.media.MediaPanes
import ani.dantotsu.others.ChoiceToggleButton
import ani.dantotsu.px
import ani.dantotsu.setBaseline
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import eu.kanade.tachiyomi.util.system.getSerializableCompat
import kotlinx.coroutines.launch
import java.util.Locale

enum class StatsMediaType { ANIME, MANGA }

/** What the charts count: titles, time (hours watched or chapters read), or the mean score. */
enum class StatsMeasure { COUNT, TIME, AVG_SCORE }

class StatsFragment : Fragment() {
    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!

    /** The profile's owner, then the viewer when comparing. */
    private val stats = mutableListOf<Query.StatisticsUser>()
    private var type = StatsMediaType.ANIME
    private var measure = StatsMeasure.COUNT
    private lateinit var user: Query.UserProfile
    private var loading = false
    private val expanded = HashSet<String>()
    private val adapter = StatsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        user = arguments?.getSerializableCompat<Query.UserProfile>("user") as Query.UserProfile
        arguments?.getString("mediaType")?.let { name ->
            StatsMediaType.entries.find { it.name == name }?.let { type = it }
        }
        arguments?.getString("statType")?.let { name ->
            StatsMeasure.entries.find { it.name == name }?.let { measure = it }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = binding.statisticList
        list.adapter = adapter
        // A wide window takes the sections two to a row; the summary keeps the whole width.
        list.layoutManager = if (MediaPanes.isTwoPane(resources.configuration)) {
            GridLayoutManager(requireContext(), 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) =
                        if (adapter.getItemViewType(position) == SUMMARY) 2 else 1
                }
            }
        } else LinearLayoutManager(requireContext())

        binding.filterContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.compare.isVisible = user.id != Anilist.userid
        binding.compare.setOnCheckedChangeListener { _, checked -> onCompare(checked) }
        binding.statisticRetry.setOnClickListener { load() }

        binding.statTypeAnime.setOnClickListener { selectType(StatsMediaType.ANIME) }
        binding.statTypeManga.setOnClickListener { selectType(StatsMediaType.MANGA) }
        binding.statMeasureCount.setOnClickListener { selectMeasure(StatsMeasure.COUNT) }
        binding.statMeasureTime.setOnClickListener { selectMeasure(StatsMeasure.TIME) }
        binding.statMeasureScore.setOnClickListener { selectMeasure(StatsMeasure.AVG_SCORE) }
        showFilters()

        if (stats.isNotEmpty()) render()
    }

    override fun onResume() {
        super.onResume()
        (activity as? ProfileActivity)?.let { binding.statisticList.setBaseline(it.navBar) }
        // On first being shown rather than when created: the tab may never be opened.
        if (stats.isEmpty() && !loading) load()
    }

    private fun load() {
        loading = true
        binding.statisticProgressBar.isVisible = true
        binding.statisticMessageContainer.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            val owner = Anilist.query.getUserStatistics(user.id)?.data?.user
            loading = false
            if (_binding == null) return@launch
            binding.statisticProgressBar.isVisible = false
            if (owner == null) {
                showMessage(R.string.stats_load_failed, retry = true)
                return@launch
            }
            stats.clear()
            stats.add(owner)
            render()
        }
    }

    private fun onCompare(checked: Boolean) {
        if (!checked) {
            stats.removeAll { it.id != user.id }
            render()
            return
        }
        val viewer = Anilist.userid ?: return
        binding.statisticProgressBar.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val mine = Anilist.query.getUserStatistics(viewer)?.data?.user
            if (_binding == null) return@launch
            binding.statisticProgressBar.isVisible = false
            if (mine == null) {
                snackString(R.string.stats_load_failed)
                binding.compare.isChecked = false
                return@launch
            }
            stats.add(mine)
            render()
        }
    }

    private fun selectType(selected: StatsMediaType) {
        if (type != selected) {
            type = selected
            expanded.clear()
        }
        showFilters()
        render()
    }

    private fun selectMeasure(selected: StatsMeasure) {
        measure = selected
        showFilters()
        render()
    }

    private fun showFilters() {
        fun ChoiceToggleButton.choose(chosen: Boolean) {
            alpha = if (chosen) 1f else 0.33f
        }
        binding.statTypeAnime.choose(type == StatsMediaType.ANIME)
        binding.statTypeManga.choose(type == StatsMediaType.MANGA)
        binding.statMeasureCount.choose(measure == StatsMeasure.COUNT)
        binding.statMeasureTime.choose(measure == StatsMeasure.TIME)
        binding.statMeasureScore.choose(measure == StatsMeasure.AVG_SCORE)
        binding.statMeasureTime.setText(
            if (type == StatsMediaType.ANIME) R.string.stats_hours else R.string.stats_chapters
        )
    }

    private fun showMessage(@StringRes message: Int, retry: Boolean) {
        binding.statisticMessage.setText(message)
        binding.statisticRetry.isVisible = retry
        binding.statisticMessageContainer.isVisible = true
    }

    private fun statsOf(user: Query.StatisticsUser) =
        if (type == StatsMediaType.ANIME) user.statistics.anime else user.statistics.manga

    private fun render() {
        if (stats.isEmpty() || _binding == null) return
        binding.filterContainer.isVisible = true
        binding.statisticMessageContainer.isVisible = false
        val sections = buildSections()
        adapter.show(stats.toList(), sections)
        if (sections.isEmpty() && stats.all { statsOf(it).count == 0 }) {
            showMessage(R.string.stats_empty, retry = false)
        }
    }

    // ---- data ------------------------------------------------------------------------------------

    /** One line of any of AniList's breakdowns: [key] matches it across two people's. */
    private class Entry(
        val key: String,
        val label: String,
        val count: Int,
        val meanScore: Float,
        val minutes: Int,
        val chapters: Int,
        val order: Int = 0,
    )

    private enum class Shape { RANKED, ORDERED_BARS, COLUMNS }

    private class SectionSpec(
        val key: String,
        @StringRes val title: Int,
        val shape: Shape,
        val entries: (Query.StatisticsUser, Query.Statistics) -> List<Entry>,
        val scrollsToEnd: Boolean = false,
    )

    private fun statusLabel(status: String): String {
        val labels = resources.getStringArray(
            if (type == StatsMediaType.ANIME) R.array.status_anime else R.array.status_manga
        )
        return when (status) {
            "PLANNING" -> labels[0]
            "CURRENT" -> labels[1]
            "COMPLETED" -> labels[2]
            "REPEATING" -> labels[3]
            "PAUSED" -> labels[4]
            "DROPPED" -> labels[5]
            else -> status
        }
    }

    private fun formatLabel(format: String): String = when (format) {
        "TV_SHORT" -> getString(R.string.stats_format_tv_short)
        "MOVIE" -> getString(R.string.stats_format_movie)
        "SPECIAL" -> getString(R.string.stats_format_special)
        "MUSIC" -> getString(R.string.stats_format_music)
        "MANGA" -> getString(R.string.manga)
        "NOVEL" -> getString(R.string.stats_format_novel)
        "ONE_SHOT" -> getString(R.string.stats_format_one_shot)
        else -> format
    }

    private fun countryLabel(code: String): String =
        Locale("", code).displayCountry.ifBlank { code }

    /** "2-6" and "101+" sort by where they start. */
    private fun lengthOrder(length: String?): Int =
        length?.takeWhile { it.isDigit() }?.toIntOrNull() ?: Int.MAX_VALUE

    private fun convertScore(score: Int, format: String?): Int = when (format) {
        "POINT_10" -> score * 10
        "POINT_5" -> score * 20
        "POINT_3" -> score * 33
        else -> score
    }

    private val specs: List<SectionSpec> = listOf(
        SectionSpec("status", R.string.status_title, Shape.RANKED, { _, s ->
            s.statuses.map {
                Entry(it.status, statusLabel(it.status), it.count, it.meanScore, it.minutesWatched, it.chaptersRead)
            }
        }),
        SectionSpec("format", R.string.format, Shape.RANKED, { _, s ->
            s.formats.map {
                Entry(it.format, formatLabel(it.format), it.count, it.meanScore, it.minutesWatched, it.chaptersRead)
            }
        }),
        SectionSpec("score", R.string.score, Shape.COLUMNS, { u, s ->
            s.scores.map {
                val score = convertScore(it.score, u.mediaListOptions.scoreFormat?.toString())
                Entry(score.toString(), score.toString(), it.count, it.meanScore, it.minutesWatched, it.chaptersRead, score)
            }
        }),
        SectionSpec("genres", R.string.genres, Shape.RANKED, { _, s ->
            s.genres.map {
                Entry(it.genre, it.genre, it.count, it.meanScore, it.minutesWatched, it.chaptersRead)
            }
        }),
        SectionSpec("tags", R.string.tags, Shape.RANKED, { _, s ->
            s.tags.map {
                Entry(it.tag.name, it.tag.name, it.count, it.meanScore, it.minutesWatched, it.chaptersRead)
            }
        }),
        SectionSpec("release_year", R.string.stats_release_year, Shape.COLUMNS, { _, s ->
            s.releaseYears.map {
                val year = it.releaseYear
                Entry(year.toString(), year.toString(), it.count, it.meanScore, it.minutesWatched, it.chaptersRead, year)
            }
        }, scrollsToEnd = true),
        SectionSpec("start_year", R.string.stats_start_year, Shape.COLUMNS, { _, s ->
            s.startYears.map {
                val year = it.startYear
                Entry(year.toString(), year.toString(), it.count, it.meanScore, it.minutesWatched, it.chaptersRead, year)
            }
        }, scrollsToEnd = true),
        SectionSpec("length", R.string.stats_length, Shape.ORDERED_BARS, { _, s ->
            s.lengths.map {
                val length = it.length
                Entry(
                    length ?: "", length ?: getString(R.string.unknown),
                    it.count, it.meanScore, it.minutesWatched, it.chaptersRead, lengthOrder(length),
                )
            }
        }),
        SectionSpec("country", R.string.stats_country, Shape.RANKED, { _, s ->
            s.countries.map {
                Entry(it.country, countryLabel(it.country), it.count, it.meanScore, it.minutesWatched, it.chaptersRead)
            }
        }),
        SectionSpec("studios", R.string.studios, Shape.RANKED, { _, s ->
            s.studios.map {
                Entry(it.studio.name, it.studio.name, it.count, it.meanScore, it.minutesWatched, it.chaptersRead)
            }
        }),
        SectionSpec("staff", R.string.staff, Shape.RANKED, { _, s ->
            s.staff.map {
                val name = it.staff.name.full ?: getString(R.string.unknown)
                Entry(name, name, it.count, it.meanScore, it.minutesWatched, it.chaptersRead)
            }
        }),
        SectionSpec("voice_actors", R.string.voice_actors, Shape.RANKED, { _, s ->
            s.voiceActors.map {
                val name = it.voiceActor.name.full ?: getString(R.string.unknown)
                Entry(name, name, it.count, it.meanScore, it.minutesWatched, it.chaptersRead)
            }
        }),
    )

    private fun valueOf(entry: Entry): Double? = when (measure) {
        StatsMeasure.COUNT -> entry.count.toDouble()
        StatsMeasure.TIME ->
            if (type == StatsMediaType.ANIME) (entry.minutes / 60).toDouble() else entry.chapters.toDouble()
        // AniList gives titles nobody scored a 0, which isn't a score of 0.
        StatsMeasure.AVG_SCORE -> entry.meanScore.toDouble().takeIf { it > 0 }
    }

    private fun buildSections(): List<Section> = specs.mapNotNull { spec ->
        val perPerson = stats.map { person ->
            spec.entries(person, statsOf(person)).associateBy { it.key }
        }
        val keys = perPerson.flatMap { it.keys }.distinct()
        val rows = keys.mapNotNull { key ->
            val first = perPerson.firstNotNullOfOrNull { it[key] } ?: return@mapNotNull null
            val values = perPerson.map { person -> person[key]?.let { valueOf(it) } }
            if (values.none { it != null && it > 0 }) return@mapNotNull null
            first to StatRow(first.label, values)
        }
        if (rows.isEmpty()) return@mapNotNull null
        val sorted = when (spec.shape) {
            Shape.RANKED -> rows.sortedWith(
                compareByDescending<Pair<Entry, StatRow>> { it.second.values[0] ?: -1.0 }
                    .thenByDescending { it.second.values.getOrNull(1) ?: -1.0 }
            )

            else -> rows.sortedBy { it.first.order }
        }
        Section(
            key = spec.key,
            title = getString(spec.title),
            rows = sorted.map { it.second },
            columns = spec.shape == Shape.COLUMNS,
            decimal = measure == StatsMeasure.AVG_SCORE,
            scrollsToEnd = spec.scrollsToEnd,
        )
    }

    // ---- list ------------------------------------------------------------------------------------

    private class Section(
        val key: String,
        val title: String,
        val rows: List<StatRow>,
        val columns: Boolean,
        val decimal: Boolean,
        val scrollsToEnd: Boolean,
    )

    private inner class StatsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var people: List<Query.StatisticsUser> = emptyList()
        private var sections: List<Section> = emptyList()

        inner class SummaryHolder(val binding: ItemStatSummaryBinding) : RecyclerView.ViewHolder(binding.root)
        inner class SectionHolder(val binding: ItemStatSectionBinding) : RecyclerView.ViewHolder(binding.root)

        @SuppressLint("NotifyDataSetChanged")
        fun show(people: List<Query.StatisticsUser>, sections: List<Section>) {
            this.people = people
            this.sections = sections
            notifyDataSetChanged()
        }

        override fun getItemCount() = if (people.isEmpty()) 0 else sections.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) SUMMARY else SECTION

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == SUMMARY) SummaryHolder(ItemStatSummaryBinding.inflate(inflater, parent, false))
            else SectionHolder(ItemStatSectionBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is SummaryHolder -> bindSummary(holder.binding)
                is SectionHolder -> bindSection(holder.binding, sections[position - 1])
            }
        }

        private fun bindSection(b: ItemStatSectionBinding, section: Section) {
            b.statSectionTitle.text = section.title
            b.statSectionBars.isVisible = !section.columns
            b.statSectionColumnsScroll.isVisible = section.columns
            b.statSectionMore.isVisible = false
            if (section.columns) {
                b.statSectionColumns.show(section.rows, section.decimal)
                b.statSectionColumnsScroll.post {
                    b.statSectionColumnsScroll.scrollTo(
                        if (section.scrollsToEnd) b.statSectionColumns.width else 0, 0
                    )
                }
                return
            }
            val open = section.key in expanded
            val capped = section.rows.size > RANKED_CAP
            b.statSectionBars.show(
                if (capped && !open) section.rows.take(RANKED_CAP) else section.rows,
                section.decimal,
            )
            if (capped) {
                b.statSectionMore.isVisible = true
                b.statSectionMore.text =
                    if (open) getString(R.string.stats_show_less)
                    else getString(R.string.stats_show_all, section.rows.size)
                b.statSectionMore.setOnClickListener {
                    if (!expanded.remove(section.key)) expanded.add(section.key)
                    notifyItemChanged(sections.indexOf(section) + 1)
                }
            }
        }

        private fun bindSummary(b: ItemStatSummaryBinding) {
            val rows = b.statSummaryRows
            rows.removeAllViews()
            people.forEachIndexed { index, person ->
                val s = statsOf(person)
                val personColor = statPersonColor(rows.context, index)
                if (people.size > 1) {
                    rows.addView(TextView(rows.context).apply {
                        text = person.name
                        setTextColor(personColor)
                        textSize = 12f
                        typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
                        setPadding(8f.px, if (index == 0) 0 else 10f.px, 8f.px, 4f.px)
                    })
                }
                val metrics = if (type == StatsMediaType.ANIME) listOf(
                    R.string.stats_titles to formatStat(s.count.toDouble(), false),
                    R.string.stats_hours to formatStat((s.minutesWatched / 60).toDouble(), false),
                    R.string.stats_episodes to formatStat(s.episodesWatched.toDouble(), false),
                    R.string.score to formatStat(s.meanScore.toDouble().takeIf { it > 0 }, true),
                ) else listOf(
                    R.string.stats_titles to formatStat(s.count.toDouble(), false),
                    R.string.stats_chapters to formatStat(s.chaptersRead.toDouble(), false),
                    R.string.stats_volumes to formatStat(s.volumesRead.toDouble(), false),
                    R.string.score to formatStat(s.meanScore.toDouble().takeIf { it > 0 }, true),
                )
                rows.addView(LinearLayout(rows.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    metrics.forEach { (label, value) -> addView(metric(label, value, personColor)) }
                })
            }
        }

        private fun metric(@StringRes label: Int, value: String, color: Int): View {
            val context = requireContext()
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = value
                    gravity = Gravity.CENTER
                    setTextColor(color)
                    textSize = 17f
                    maxLines = 1
                    typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
                })
                addView(TextView(context).apply {
                    setText(label)
                    gravity = Gravity.CENTER
                    setTextColor(context.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    textSize = 11f
                    maxLines = 1
                    typeface = ResourcesCompat.getFont(context, R.font.poppins)
                })
            }
        }
    }

    companion object {
        private const val SUMMARY = 0
        private const val SECTION = 1

        /** Rows a ranked section shows before "Show all". */
        private const val RANKED_CAP = 8

        fun newInstance(
            user: Query.UserProfile,
            mediaType: StatsMediaType? = null,
            statType: StatsMeasure? = null,
        ): StatsFragment = StatsFragment().apply {
            arguments = Bundle().apply {
                putSerializable("user", user)
                mediaType?.let { putString("mediaType", it.name) }
                statType?.let { putString("statType", it.name) }
            }
        }
    }
}
