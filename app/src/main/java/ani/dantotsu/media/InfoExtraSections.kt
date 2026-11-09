package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.mal.MALRelation
import ani.dantotsu.connections.mal.MALSerialization
import ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity
import ani.dantotsu.connections.mangaupdates.MUSeriesRecord
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemTitleChipgroupBinding
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.databinding.ItemTitleTextBinding
import ani.dantotsu.setSafeOnClickListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade

/** Sections several sources build the same way out of data of different shapes. */
object InfoExtraSections {

    class Chip(val label: String, val onClick: (() -> Unit)? = null)

    /** A related entry that opens in its own source's page. */
    class RelationCard(
        val title: String,
        val coverUrl: String?,
        val relation: String,
        val isAnime: Boolean,
        /** Ids it goes by elsewhere, as "mal:123", for telling it apart from another source's. */
        val ids: List<String> = emptyList(),
        /** Other names it goes by. */
        val altTitles: List<String> = emptyList(),
        /** What the source calls its format ("NOVEL", "light_novel"), where it says. */
        val format: String? = null,
        val onClick: () -> Unit,
    ) {
        val titleKeys: Set<String>
            get() = (altTitles + title).map { name -> name.lowercase().filter { it.isLetterOrDigit() } }
                .filter { it.isNotEmpty() }.toSet()

        /** The sources' words for a format reduced to one, as far as telling works apart needs. */
        val formatKey: String?
            get() {
                val word = format?.lowercase()?.filter { it.isLetter() }?.takeIf { it.isNotEmpty() } ?: return null
                return when {
                    "novel" in word -> "novel"
                    "oneshot" in word -> "oneshot"
                    word.startsWith("tv") -> "tv"
                    word == "manga" || word == "manhwa" || word == "manhua" -> "comic"
                    else -> word
                }
            }
    }

    /**
     * [bySource]'s cards, in order, with each work once. Adaptations often share a title, as can a
     * series and its one-shot, so when in doubt a card stays: a repeat is better than a missing work.
     *
     *  - Only across sources: a source listing two cards of one name means two works.
     *  - An id both know decides. Differing ids mean different works whatever the titles say.
     *  - Otherwise the title has to match, with neither format saying otherwise.
     *  - One card is only ever the same as one card of each later source, so a source listing two
     *    works of one name against another's single card still shows the second.
     */
    /**
     * The icon a card shows for what kind of work it is: a film for anime, an open book for comics
     * and a closed one for novels, as the novel reader's button and the extension list have it.
     * [format] is whatever the source calls it; MAL's relations say nothing, so read as comics.
     */
    @androidx.annotation.DrawableRes
    fun typeIcon(isAnime: Boolean, format: String?): Int = when {
        isAnime -> R.drawable.ic_round_movie_filter_24
        format?.contains("novel", ignoreCase = true) == true -> R.drawable.ic_round_book_24
        else -> R.drawable.ic_round_import_contacts_24
    }

    fun distinctRelations(bySource: List<List<RelationCard>>): List<RelationCard> {
        class Work(val card: RelationCard) {
            val ids = card.ids.toMutableSet()
            val titles = card.titleKeys.toMutableSet()
            val formats = listOfNotNull(card.formatKey).toMutableSet()
            var lastMatchedBy = -1

            fun namespaces() = ids.map { it.substringBefore(':') }.toSet()

            fun absorb(other: RelationCard, source: Int) {
                lastMatchedBy = source
                ids += other.ids
                titles += other.titleKeys
                other.formatKey?.let { formats += it }
            }
        }

        val works = ArrayList<Work>()
        bySource.forEachIndexed { source, cards ->
            val earlier = works.toList()
            fun open(card: RelationCard) = earlier.filter { it.lastMatchedBy != source && it.card.isAnime == card.isAnime }

            // All ids first, so that a title can't take the card an id belongs to.
            val left = cards.filter { card ->
                val work = open(card).firstOrNull { work -> card.ids.any { it in work.ids } }
                work?.absorb(card, source)
                work == null
            }
            val fresh = left.filter { card ->
                val work = open(card).firstOrNull { work ->
                    val idsDiffer = card.ids.any { it.substringBefore(':') in work.namespaces() }
                    val formatsDiffer = card.formatKey != null && work.formats.isNotEmpty() && card.formatKey !in work.formats
                    !idsDiffer && !formatsDiffer && card.titleKeys.any { it in work.titles }
                }
                work?.absorb(card, source)
                work == null
            }
            fresh.forEach { works.add(Work(it)) }
        }
        return works.map { it.card }
    }

    fun addChips(parent: ViewGroup, kind: InfoSectionKind, title: String, chips: List<Chip>) {
        if (chips.isEmpty()) return
        val inflater = LayoutInflater.from(parent.context)
        val bind = ItemTitleChipgroupBinding.inflate(inflater, parent, false)
        bind.itemTitle.text = title
        chips.forEach { item ->
            val chip = ItemChipBinding.inflate(inflater, bind.itemChipGroup, false).root
            chip.text = item.label
            item.onClick?.let { click -> chip.setOnClickListener { click() } }
            chip.setOnLongClickListener { copyToClipboard(item.label); true }
            bind.itemChipGroup.addView(chip)
        }
        parent.addInfoSection(kind, bind.root)
    }

    /** Builds the section without adding it, for a caller holding a slot for it. */
    fun relationCardsView(parent: ViewGroup, cards: List<RelationCard>): View {
        val bind = ItemTitleRecyclerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        bind.itemTitle.setText(R.string.relations)
        bind.itemRecycler.adapter = RelationCardAdapter(cards)
        bind.itemRecycler.layoutManager =
            LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        return bind.root
    }

    fun addText(parent: ViewGroup, kind: InfoSectionKind, title: String, text: String) {
        if (text.isBlank()) return
        val bind = ItemTitleTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        bind.itemTitle.text = title
        bind.itemText.text = text.trim()
        // Long prose, so in the plain font the synopsis uses rather than this layout's bold one.
        bind.itemText.typeface = android.widget.TextView(parent.context).typeface
        bind.itemText.setOnClickListener {
            val target = if (bind.itemText.maxLines == 3) 100 else 3
            ObjectAnimator.ofInt(bind.itemText, "maxLines", target).setDuration(400).start()
        }
        bind.itemText.setOnLongClickListener { copyToClipboard(text.trim()); true }
        parent.addInfoSection(kind, bind.root)
    }

    private fun addMalRelations(parent: ViewGroup, relatedAnime: List<MALRelation>, relatedManga: List<MALRelation>) {
        val context = parent.context
        fun card(relation: MALRelation, isAnime: Boolean): RelationCard {
            val cover = relation.node.mainPicture?.large ?: relation.node.mainPicture?.medium
            return RelationCard(
                relation.node.title, cover, relation.relationTypeFormatted, isAnime,
                ids = listOf("mal:${relation.node.id}"),
            ) {
                context.startActivity(
                    Intent(context, MalMediaActivity::class.java)
                        .putExtra(MalMediaActivity.EXTRA_MEDIA_ID, relation.node.id)
                        .putExtra(MalMediaActivity.EXTRA_IS_ANIME, isAnime)
                        .putExtra(MalMediaActivity.EXTRA_COVER_URL, cover)
                )
            }
        }
        val cards = relatedAnime.map { card(it, true) } + relatedManga.map { card(it, false) }
        if (cards.isNotEmpty()) {
            parent.offerInfoRelations(cards)
            parent.addInfoSection(InfoSectionKind.RELATIONS, relationCardsView(parent, cards))
        }
    }

    /** What MAL's details response carries beyond the basics, for its tab and its own page alike. */
    fun addMalExtras(
        parent: ViewGroup,
        background: String?,
        relatedAnime: List<MALRelation>,
        relatedManga: List<MALRelation>,
        serialization: List<MALSerialization> = emptyList(),
    ) {
        val context = parent.context
        addText(
            parent, InfoSectionKind.BACKGROUND, context.getString(R.string.info_section_background),
            background.orEmpty(),
        )
        addMalRelations(parent, relatedAnime, relatedManga)
        addChips(
            parent, InfoSectionKind.PUBLISHERS, context.getString(R.string.info_section_publishers),
            serialization.map { Chip(it.node.name) },
        )
    }

    /** MangaUpdates sends these two with every series; no request is spent on them. */
    fun addMangaUpdatesExtras(context: Context, parent: ViewGroup, series: MUSeriesRecord) {
        parent.offerInfoRelations(series.related_series.orEmpty().mapNotNull { related ->
            val name = related.relatedSeriesName ?: return@mapNotNull null
            RelationCard(
                name, null, related.relationType.orEmpty(), isAnime = false,
                ids = listOfNotNull(related.relatedSeriesId?.let { "mu:$it" }),
            ) { related.relatedSeriesUrl?.let { openMangaUpdates(context, it) } }
        })
        addChips(
            parent, InfoSectionKind.RELATIONS, context.getString(R.string.relations),
            series.related_series.orEmpty().mapNotNull { related ->
                val name = related.relatedSeriesName ?: return@mapNotNull null
                val url = related.relatedSeriesUrl
                val label = related.relationType?.takeIf { it.isNotBlank() }?.let { "$it: $name" } ?: name
                Chip(label, url?.let { { openMangaUpdates(context, it) } })
            }
        )
        val publishers = series.publishers.orEmpty().mapNotNull { publisher ->
            val name = publisher.publisherName ?: return@mapNotNull null
            Chip(publisher.type?.takeIf { it.isNotBlank() }?.let { "$name ($it)" } ?: name)
        }
        val magazines = series.publications.orEmpty().mapNotNull { publication ->
            publication.publication_name?.let { Chip(it) }
        }
        addChips(
            parent, InfoSectionKind.PUBLISHERS, context.getString(R.string.info_section_publishers),
            publishers + magazines,
        )
    }

    private fun openMangaUpdates(context: Context, url: String) {
        // Explicitly, as the AniList tab's links do: the page is ours, not the browser's.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).setClass(context, MUMediaDetailsActivity::class.java)
        )
    }

    class RelationCardAdapter(private val cards: List<RelationCard>) :
        RecyclerView.Adapter<RelationCardAdapter.Holder>() {
        class Holder(val binding: ItemMediaCompactBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                .withPressFeedback()
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val card = cards[position]
            val b = holder.binding
            if (card.coverUrl != null) {
                Glide.with(b.itemCompactImage.context).load(card.coverUrl)
                    .transition(withCrossFade()).into(b.itemCompactImage)
            } else {
                Glide.with(b.itemCompactImage.context).clear(b.itemCompactImage)
            }
            b.itemCompactTitle.text = card.title
            b.itemCompactScoreBG.visibility = View.GONE
            b.itemCompactOngoing.visibility = View.GONE
            b.itemCompactProgressContainer.visibility = View.GONE
            b.itemCompactType.visibility = View.VISIBLE
            b.itemCompactRelation.text = card.relation.replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
            b.itemCompactTypeImage.setImageDrawable(
                ContextCompat.getDrawable(b.root.context, typeIcon(card.isAnime, card.format))
            )
            b.root.setSafeOnClickListener { card.onClick() }
            b.itemCompactImage.setSafeOnClickListener { card.onClick() }
        }
    }
}
