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
import ani.dantotsu.connections.mal.MALPicture
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
        val onClick: () -> Unit,
    )

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

    private fun addMalPictures(parent: ViewGroup, pictures: List<MALPicture>) {
        val covers = pictures.mapNotNull { picture ->
            val thumb = picture.medium ?: picture.large ?: return@mapNotNull null
            MangaBakaCover(thumb, picture.large ?: thumb)
        }.distinctBy { it.thumbUrl }
        if (covers.isEmpty()) return
        val bind = ItemTitleRecyclerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val title = parent.context.getString(R.string.info_section_pictures)
        bind.itemTitle.text = title
        val adapter = MangaBakaCoverAdapter(covers)
        bind.itemRecycler.adapter = adapter
        bind.itemRecycler.layoutManager =
            LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        bind.itemMore.visibility = View.VISIBLE
        bind.itemMore.setSafeOnClickListener { adapter.showGallery(bind.itemMore, title) }
        parent.addInfoSection(InfoSectionKind.COVERS, bind.root)
    }

    private fun addMalRelations(parent: ViewGroup, relatedAnime: List<MALRelation>, relatedManga: List<MALRelation>) {
        val context = parent.context
        fun card(relation: MALRelation, isAnime: Boolean): RelationCard {
            val cover = relation.node.mainPicture?.large ?: relation.node.mainPicture?.medium
            return RelationCard(relation.node.title, cover, relation.relationTypeFormatted, isAnime) {
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
            parent.addInfoSection(InfoSectionKind.RELATIONS, relationCardsView(parent, cards))
        }
    }

    /** What MAL's details response carries beyond the basics, for its tab and its own page alike. */
    fun addMalExtras(
        parent: ViewGroup,
        background: String?,
        pictures: List<MALPicture>,
        relatedAnime: List<MALRelation>,
        relatedManga: List<MALRelation>,
        serialization: List<MALSerialization> = emptyList(),
    ) {
        val context = parent.context
        addText(
            parent, InfoSectionKind.BACKGROUND, context.getString(R.string.info_section_background),
            background.orEmpty(),
        )
        addMalPictures(parent, pictures)
        addMalRelations(parent, relatedAnime, relatedManga)
        addChips(
            parent, InfoSectionKind.PUBLISHERS, context.getString(R.string.info_section_publishers),
            serialization.map { Chip(it.node.name) },
        )
    }

    /** MangaUpdates sends these two with every series; no request is spent on them. */
    fun addMangaUpdatesExtras(context: Context, parent: ViewGroup, series: MUSeriesRecord) {
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

    private class RelationCardAdapter(private val cards: List<RelationCard>) :
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
                ContextCompat.getDrawable(
                    b.root.context,
                    if (card.isAnime) R.drawable.ic_round_movie_filter_24 else R.drawable.ic_round_menu_book_24,
                )
            )
            b.root.setSafeOnClickListener { card.onClick() }
            b.itemCompactImage.setSafeOnClickListener { card.onClick() }
        }
    }
}
