package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R

class StackAdapter(private val items: List<InterestStack>, private val isAnime: Boolean) : RecyclerView.Adapter<StackAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val coversContainer: FrameLayout = view.findViewById(R.id.itemStackCoversContainer)
        val image: ImageView = view.findViewById(R.id.itemCompactImage)
        val title: TextView = view.findViewById(R.id.itemCompactTitle)
        val userProgress: TextView = view.findViewById(R.id.itemCompactUserProgress)
        val total: TextView? = view.findViewById(R.id.itemCompactTotal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stack_compact, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        StackCoverCollage.bind(holder.coversContainer, item.covers, item.entries)
        holder.title.text = item.name
        holder.userProgress.text = item.entries.toString()
        holder.total?.text = ctx.getString(R.string.stack_entries_suffix)

        // Click: open the internal list view, which resolves the stack's entries itself
        holder.itemView.setOnClickListener {
            StackResolver.open(ctx, item, isAnime)
        }

        // Long click: open stack in browser
        holder.itemView.setOnLongClickListener {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
            } catch (e: Exception) {
                // ignore
            }
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
