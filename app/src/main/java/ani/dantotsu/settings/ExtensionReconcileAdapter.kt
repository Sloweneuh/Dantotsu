package ani.dantotsu.settings

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.sync.ExtensionSync
import ani.dantotsu.databinding.ItemExtensionReconcileBinding
import com.bumptech.glide.Glide

/**
 * Rows for the extension-sync reconcile dialog: extension icon + name, a small type icon
 * (anime/manga/novel) and an action icon — a green plus for installs, a red minus for removals.
 * [checked] is shared with the dialog so it can read back the user's selections on Apply.
 */
class ExtensionReconcileAdapter(
    private val items: List<ExtensionSync.ExtItem>,
    private val checked: BooleanArray,
) : RecyclerView.Adapter<ExtensionReconcileAdapter.Holder>() {

    inner class Holder(val b: ItemExtensionReconcileBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ItemExtensionReconcileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val b = holder.b
        val ctx = b.root.context

        b.reconcileName.text = item.name

        val typeIcon = when (item.type) {
            ExtensionSync.ExtType.ANIME -> R.drawable.ic_round_movie_filter_24
            ExtensionSync.ExtType.MANGA -> R.drawable.ic_round_import_contacts_24
            ExtensionSync.ExtType.NOVEL -> R.drawable.ic_round_book_24
        }
        b.reconcileTypeIcon.setImageResource(typeIcon)

        if (item.isInstall) {
            b.reconcileActionIcon.setImageResource(R.drawable.ic_add)
            b.reconcileActionIcon.setColorFilter(GREEN)
            // Repo icon for a not-yet-installed extension; fall back to the type icon.
            Glide.with(ctx).load(item.iconUrl).placeholder(typeIcon).error(typeIcon)
                .into(b.reconcileExtIcon)
        } else {
            b.reconcileActionIcon.setImageResource(R.drawable.ic_minus)
            b.reconcileActionIcon.setColorFilter(RED)
            // Installed extension: use its real app icon.
            val icon = runCatching { ctx.packageManager.getApplicationIcon(item.pkgName) }.getOrNull()
            Glide.with(ctx).clear(b.reconcileExtIcon)
            if (icon != null) b.reconcileExtIcon.setImageDrawable(icon)
            else b.reconcileExtIcon.setImageResource(typeIcon)
        }

        // Installs whose repo is missing can't be acted on.
        val actionable = !item.isInstall || item.available
        b.reconcileRoot.alpha = if (actionable) 1f else 0.5f
        b.reconcileRoot.isEnabled = actionable
        b.reconcileCheck.isEnabled = actionable
        b.reconcileCheck.isChecked = checked[position]

        b.reconcileRoot.setOnClickListener {
            if (!actionable) return@setOnClickListener
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            checked[pos] = !checked[pos]
            b.reconcileCheck.isChecked = checked[pos]
        }
    }

    private companion object {
        val GREEN = Color.parseColor("#43A047")
        val RED = Color.parseColor("#E53935")
    }
}
