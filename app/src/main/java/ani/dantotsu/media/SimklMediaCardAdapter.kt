package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.simkl.SimklApi
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.setSafeOnClickListener
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade

/**
 * Compact media-card strip for the Simkl page's Relations / Recommendations sections — the same
 * card shape AniList uses. A tap opens that entry's [SimklMediaActivity].
 */
class SimklMediaCardAdapter(
    private val cards: List<Card>,
    private val onClick: (Long) -> Unit,
) : RecyclerView.Adapter<SimklMediaCardAdapter.Holder>() {

    data class Card(
        val title: String,
        val posterPath: String?,
        val label: String?,
        val simklId: Long?,
    )

    inner class Holder(val binding: ItemMediaCompactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = cards[position]
        val b = holder.binding

        SimklApi.posterUrl(card.posterPath)?.let { url ->
            Glide.with(b.itemCompactImage.context).load(url).transition(withCrossFade())
                .into(b.itemCompactImage)
        }

        b.itemCompactTitle.text = card.title
        b.itemCompactScoreBG.visibility = View.GONE
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactProgressContainer.visibility = View.GONE

        if (card.label.isNullOrBlank()) {
            b.itemCompactType.visibility = View.GONE
        } else {
            b.itemCompactType.visibility = View.VISIBLE
            b.itemCompactRelation.text = card.label.replace('_', ' ')
            b.itemCompactTypeImage.setImageDrawable(
                ContextCompat.getDrawable(b.root.context, R.drawable.ic_round_movie_filter_24)
            )
        }

        val open = { card.simklId?.let(onClick) ?: Unit }
        b.root.setSafeOnClickListener { open() }
        b.itemCompactImage.setSafeOnClickListener { open() }
        val browse = {
            card.simklId?.let {
                b.root.context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://simkl.com/anime/$it"))
                )
            }
            Unit
        }
        b.root.setOnLongClickListener { browse(); true }
        b.itemCompactImage.setOnLongClickListener { browse(); true }
    }

    override fun getItemCount() = cards.size
}
