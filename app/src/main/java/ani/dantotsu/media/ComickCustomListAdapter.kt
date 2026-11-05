package ani.dantotsu.media

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickCustomList
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.loadImage
import ani.dantotsu.setSafeOnClickListener

/** A Comick list as a stack card, on a page that shows it among other sources' stacks. */
class ComickListStack(private val list: ComickCustomList) : InterestStack {
    // The app knows no web address for a list, so a long press on this card does nothing.
    override val url = ""
    override val name = list.title ?: list.slug.orEmpty()
    override val covers = listOfNotNull(list.cover?.let { "https://meo.comick.pictures/$it" })
    override val entries = list.follows_count ?: 0
    override val description = list.description

    fun open(context: Context) {
        context.startActivity(
            Intent(context, ComickListActivity::class.java)
                .putExtra(ComickListActivity.EXTRA_USER_ID, list.user_id)
                .putExtra(ComickListActivity.EXTRA_LIST_SLUG, list.slug)
                .putExtra(ComickListActivity.EXTRA_LIST_TITLE, list.title ?: list.slug)
        )
    }
}

class ComickCustomListAdapter(
    private val lists: List<ComickCustomList>,
    private val onItemClick: (ComickCustomList) -> Unit
) : RecyclerView.Adapter<ComickCustomListAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemMediaCompactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = lists.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemMediaCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false).withPressFeedback())

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val list = lists[position]
        val b = holder.binding

        val coverUrl = list.cover?.let { "https://meo.comick.pictures/$it" }
        if (coverUrl != null) {
            b.itemCompactImage.scaleType = ImageView.ScaleType.CENTER_CROP
            b.itemCompactImage.loadImage(coverUrl)
        } else {
            b.itemCompactImage.scaleType = ImageView.ScaleType.CENTER
            b.itemCompactImage.setImageResource(R.drawable.ic_round_view_list_24)
        }

        b.itemCompactTitle.text = list.title ?: list.slug ?: b.root.context.getString(R.string.unknown)
        b.itemCompactTitle.maxLines = 2

        val count = list.follows_count
        if (count != null) {
            b.itemCompactProgressContainer.visibility = View.VISIBLE
            b.itemCompactUserProgress.text = b.root.context.getString(R.string.comick_list_comics_count, count)
            b.itemCompactTotal.visibility = View.GONE
        } else {
            b.itemCompactProgressContainer.visibility = View.GONE
        }

        b.itemCompactScoreBG.visibility = View.GONE
        b.itemCompactOngoing.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE

        b.root.setSafeOnClickListener { onItemClick(list) }
    }
}
