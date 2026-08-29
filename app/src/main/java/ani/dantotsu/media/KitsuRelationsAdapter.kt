package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.kitsu.KitsuApi
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.setSafeOnClickListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade

/**
 * Kitsu related-media strip — the same compact media cards AniList's relations section uses
 * ([MediaAdaptor] type 0), but built straight from [KitsuApi.Relation] so a tap opens the Kitsu
 * media page rather than an AniList one.
 */
class KitsuRelationsAdapter(
    private val relations: List<KitsuApi.Relation>,
    private val onItemClick: (KitsuApi.Relation) -> Unit,
) : RecyclerView.Adapter<KitsuRelationsAdapter.Holder>() {

    inner class Holder(val binding: ItemMediaCompactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rel = relations[position]
        val b = holder.binding
        val media = rel.media

        val poster = media.posterImage?.medium ?: media.posterImage?.original ?: media.posterImage?.small
        if (poster != null) {
            Glide.with(b.itemCompactImage.context).load(poster).transition(withCrossFade())
                .into(b.itemCompactImage)
        }

        b.itemCompactTitle.text = media.canonicalTitle
            ?: media.titles?.values?.firstOrNull { !it.isNullOrBlank() } ?: ""

        b.itemCompactScoreBG.visibility = View.GONE
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactProgressContainer.visibility = View.GONE

        b.itemCompactType.visibility = View.VISIBLE
        b.itemCompactRelation.text = rel.role.replace('_', ' ')
        b.itemCompactTypeImage.setImageDrawable(
            ContextCompat.getDrawable(
                b.root.context,
                if (rel.isAnime) R.drawable.ic_round_movie_filter_24 else R.drawable.ic_round_menu_book_24,
            )
        )

        b.root.setSafeOnClickListener { onItemClick(rel) }
        b.itemCompactImage.setSafeOnClickListener { onItemClick(rel) }
        b.root.setOnLongClickListener { openInBrowser(rel, b.root); true }
        b.itemCompactImage.setOnLongClickListener { openInBrowser(rel, b.root); true }
    }

    private fun openInBrowser(rel: KitsuApi.Relation, view: View) {
        val kind = if (rel.isAnime) "anime" else "manga"
        view.context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("${KitsuApi.WEB_URL}/$kind/${rel.media.slug ?: rel.id}"))
        )
    }

    override fun getItemCount() = relations.size
}
