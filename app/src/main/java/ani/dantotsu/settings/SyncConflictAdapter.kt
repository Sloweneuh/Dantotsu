package ani.dantotsu.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.sync.SyncMerge
import ani.dantotsu.databinding.ItemSyncConflictBinding
import ani.dantotsu.notifications.anilist.AnilistNotificationWorker
import ani.dantotsu.notifications.comment.CommentNotificationWorker
import ani.dantotsu.notifications.subscription.SubscriptionNotificationWorker
import ani.dantotsu.settings.saving.BackupTree
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.durationLabel
import ani.dantotsu.util.durationLabelSeconds
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
        holder.binding.conflictLocalValue.text = displayValue(ctx, item.key, item.local)
        holder.binding.conflictRemoteValue.text = displayValue(ctx, item.key, item.remote)
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
         * Settings whose stored number is a length of time, as a conversion to minutes.
         *
         * There is no way to tell from the value: the payload carries a type, not a unit, so an
         * interval reaches this list as a bare `1440` sitting opposite a `10080` — a comparison the
         * user has to do arithmetic to make. The identity ones are minutes already; the rest are
         * older settings that store a *position* in their worker's own table of intervals, where
         * printing the number stored would be worse still ("3" against "5").
         */
        private val DURATION_TO_MINUTES: Map<String, (Long) -> Long?> = mapOf(
            PrefName.UnreadChapterNotificationInterval.name to { it },
            PrefName.MangaUpdatesNotificationInterval.name to { it },
            PrefName.SubscriptionNotificationIntervalMinutes.name to { it },
            PrefName.AutoListSyncInterval.name to { it },
            PrefName.AnilistNotificationInterval.name to
                    { AnilistNotificationWorker.checkIntervals.getOrNull(it.toInt()) },
            PrefName.CommentNotificationInterval.name to
                    { CommentNotificationWorker.checkIntervals.getOrNull(it.toInt()) },
            PrefName.SubscriptionNotificationInterval.name to
                    { SubscriptionNotificationWorker.checkIntervals.getOrNull(it.toInt()) },
        )

        /** Settings whose stored number is a length of time in seconds. */
        private val DURATION_SECONDS = setOf(PrefName.ClipDurationSeconds.name)

        /**
         * A duration setting's value written out, or null when [prefName] isn't one.
         *
         * Zero is the off/none end of every one of these, and says so rather than reading as a
         * duration of no length.
         */
        private fun durationValue(context: Context, prefName: String, raw: Long): String? {
            if (prefName in DURATION_SECONDS) {
                return if (raw <= 0L) context.getString(R.string.cloud_sync_conflict_off)
                else context.durationLabelSeconds(raw)
            }
            val minutes = DURATION_TO_MINUTES[prefName]?.invoke(raw) ?: return null
            return if (minutes <= 0L) context.getString(R.string.cloud_sync_conflict_off)
            else context.durationLabel(minutes)
        }

        /**
         * A stored `{type, value}` rendered for a person.
         *
         * Serialized values — saved filters, search history, home layout — are stored as an opaque
         * blob, so there is nothing meaningful to print and the row says only that the two differ.
         * Claiming to show a value here and then showing base64 would be worse than admitting it.
         */
        fun displayValue(context: Context, prefName: String, stored: Map<String, Any?>?): String {
            if (stored == null) return context.getString(R.string.cloud_sync_conflict_not_set)
            val value = stored["value"] ?: return context.getString(R.string.cloud_sync_conflict_not_set)
            return when (stored["type"] as? String) {
                "kotlin.Boolean" -> context.getString(
                    if (value as? Boolean == true) R.string.cloud_sync_conflict_on
                    else R.string.cloud_sync_conflict_off
                )

                // Numbers arrive from the payload as Double regardless of how they were stored.
                "kotlin.Int", "kotlin.Long" -> (value as? Double)?.toLong()?.let { number ->
                    durationValue(context, prefName, number) ?: number.toString()
                } ?: "—"
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
