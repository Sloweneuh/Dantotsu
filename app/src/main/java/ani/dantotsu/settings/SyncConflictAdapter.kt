package ani.dantotsu.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.sync.SyncMerge
import ani.dantotsu.databinding.ItemSyncConflictBinding
import ani.dantotsu.settings.saving.BackupTree
import ani.dantotsu.util.setLeadingIcon

/**
 * Lists the settings two devices disagree about, one row each, showing what both of them say.
 *
 * The prompt used to print raw preference names — `UI.DarkMode`, `Player.SubBottomMargin` — into a
 * dialog message, which asked the user to choose a side without telling them what either side
 * actually held. This shows the disagreement instead, laid out like the list-comparison detail so
 * the two columns can be read against each other, and named using the labels the backup screen
 * already carries for the same preferences.
 */
class SyncConflictAdapter(
    private val items: List<SyncMerge.Conflict>,
) : RecyclerView.Adapter<SyncConflictAdapter.Holder>() {

    inner class Holder(val binding: ItemSyncConflictBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemSyncConflictBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx = holder.binding.root.context
        holder.binding.conflictName.text = displayName(ctx, item.key)
        holder.binding.conflictLocalValue.text = displayValue(ctx, item.local)
        holder.binding.conflictRemoteValue.text = displayValue(ctx, item.remote)
        // The same two icons the sync details sheet uses for the same two sides. Which column is
        // which was carried by a 10sp all-caps label alone, on a dialog whose whole job is telling
        // them apart — and the choice underneath it is between exactly these two.
        holder.binding.conflictLocalLabel.setLeadingIcon(R.drawable.ic_round_devices_24, 12f, gapDp = 4f)
        holder.binding.conflictRemoteLabel.setLeadingIcon(R.drawable.ic_round_cloud_24, 12f, gapDp = 4f)
    }

    companion object {

        /** The backup screen's label where there is one, else the camel case broken into words. */
        fun displayName(context: Context, prefName: String): String =
            BackupTree.titleResFor(prefName)?.let { context.getString(it) }
                ?: BackupOptionsAdapter.prettifyName(prefName)

        /**
         * A stored `{type, value}` rendered for a person.
         *
         * Serialized values — saved filters, search history, home layout — are stored as an opaque
         * blob, so there is nothing meaningful to print and the row says only that the two differ.
         * Claiming to show a value here and then showing base64 would be worse than admitting it.
         */
        fun displayValue(context: Context, stored: Map<String, Any?>?): String {
            if (stored == null) return context.getString(R.string.cloud_sync_conflict_not_set)
            val value = stored["value"] ?: return context.getString(R.string.cloud_sync_conflict_not_set)
            return when (stored["type"] as? String) {
                "kotlin.Boolean" -> context.getString(
                    if (value as? Boolean == true) R.string.cloud_sync_conflict_on
                    else R.string.cloud_sync_conflict_off
                )

                // Numbers arrive from the payload as Double regardless of how they were stored.
                "kotlin.Int", "kotlin.Long" -> (value as? Double)?.toLong()?.toString() ?: "—"
                "kotlin.Float" -> (value as? Double)?.let { d ->
                    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
                } ?: "—"

                "kotlin.String" -> {
                    val text = value.toString()
                    if (text.length > MAX_VALUE_CHARS || text.isBlank()) {
                        context.getString(R.string.cloud_sync_conflict_changed)
                    } else text
                }

                "java.util.HashSet" -> {
                    val count = (value as? List<*>)?.size ?: 0
                    context.resources.getQuantityString(
                        R.plurals.cloud_sync_conflict_items, count, count
                    )
                }

                else -> context.getString(R.string.cloud_sync_conflict_changed)
            }
        }

        /** Past this, a stored string is a serialized blob rather than something worth reading. */
        private const val MAX_VALUE_CHARS = 60
    }
}
