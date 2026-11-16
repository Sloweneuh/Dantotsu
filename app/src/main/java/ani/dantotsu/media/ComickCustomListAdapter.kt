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

/**
 * Comick lists as stack cards, the same card MangaBaka and MAL stacks use: the one cover the list
 * has (Comick names no other titles for a list), its size as a pill.
 */
class ComickCustomListAdapter(
    private val lists: List<ComickCustomList>,
    private val onItemClick: (ComickCustomList) -> Unit
) : RecyclerView.Adapter<ComickCustomListAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ani.dantotsu.databinding.ItemStackCompactBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = lists.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ani.dantotsu.databinding.ItemStackCompactBinding
            .inflate(LayoutInflater.from(parent.context), parent, false).withPressFeedback()
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val list = lists[position]
        val b = holder.binding
        val stack = ComickListStack(list)
        StackCoverCollage.bind(b.itemStackCoversContainer, stack.covers, stack.entries)
        b.itemCompactTitle.text = list.title ?: list.slug ?: b.root.context.getString(R.string.unknown)
        b.itemCompactProgressContainer.visibility = View.GONE
        b.itemCompactType.visibility = View.GONE
        b.root.setSafeOnClickListener { onItemClick(list) }
    }
}
