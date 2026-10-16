package ani.dantotsu.media

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.TrackerSessions
import ani.dantotsu.connections.mangabaka.MangaBaka
import ani.dantotsu.connections.mangabaka.MangaBakaStacks
import ani.dantotsu.databinding.ItemTitleRecyclerBinding
import ani.dantotsu.px
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.snackString
import ani.dantotsu.util.materialDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Labels, error reporting and the series-page pieces shared by the MangaBaka stack screens. */
object MangaBakaStackUi {

    fun visibilityLabel(visibility: String?): Int = when (visibility) {
        "public" -> R.string.stack_visibility_public
        "unlisted" -> R.string.stack_visibility_unlisted
        else -> R.string.stack_visibility_draft
    }

    fun contentRatingLabel(rating: String?): Int = when (rating) {
        "suggestive" -> R.string.content_rating_suggestive
        "erotica" -> R.string.content_rating_erotica
        "pornographic" -> R.string.content_rating_pornographic
        else -> R.string.content_rating_safe
    }

    /**
     * Snackbars a failed stack call: a session without the stack scopes is told to log in again,
     * anything else shows the API's own message (they're written for people, e.g. "A stack needs 5
     * series to be published").
     */
    fun reportError(error: MangaBakaStacks.Result.Error) {
        when {
            error.needsRelogin -> snackString(R.string.stack_relogin)
            !error.message.isNullOrBlank() -> snackString(error.message)
            else -> snackString(R.string.stack_action_failed)
        }
    }

    /**
     * The "Interest Stacks" section of a MangaBaka series page: a row of the public stacks that
     * include [seriesId], an arrow to the full list, and — when logged in — an "Add to a stack"
     * button beside it. With no stacks it still shows, saying so in its empty slot like the reviews
     * section does — and, logged in, with the button to start a first one. Only when MangaBaka
     * couldn't be reached is there nothing to say, and then it shows only to offer that button.
     */
    fun addSeriesStacks(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        isAlive: () -> Boolean,
        parent: ViewGroup,
        seriesId: Long,
        seriesTitle: String?,
        coverUrl: String?,
    ) {
        val placeholder = FrameLayout(activity).apply {
            tag = "stacks_mangabaka"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(placeholder)
        scope.launch {
            val page = MangaBakaStacks.getSeriesStacks(seriesId)
            TrackerSessions.await()
            val loggedIn = MangaBaka.token != null
            val stacks = page?.data.orEmpty()
            // A failed fetch isn't "no stacks": say nothing rather than something untrue.
            val knownEmpty = page != null && stacks.isEmpty()
            if (!isAlive() || placeholder.childCount > 0 || (stacks.isEmpty() && !loggedIn && !knownEmpty)) return@launch

            val bind = ItemTitleRecyclerBinding.inflate(activity.layoutInflater, placeholder, false)
            bind.itemTitle.setText(R.string.interest_stacks)
            if (stacks.isNotEmpty()) {
                bind.itemRecycler.adapter = StackAdapter(stacks, false)
                bind.itemRecycler.layoutManager =
                    LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
                bind.itemMore.visibility = View.VISIBLE
                bind.itemMore.setSafeOnClickListener {
                    activity.startActivity(StackListViewActivity.mangaBakaIntent(activity, seriesId, seriesTitle, coverUrl))
                }
            } else {
                bind.itemRecycler.visibility = View.GONE
                if (knownEmpty) {
                    bind.itemEmptyIcon.setImageResource(R.drawable.ic_round_mangabaka_stacks_24)
                    bind.itemEmpty.setText(R.string.stack_none_for_series)
                    bind.itemEmptyContainer.visibility = View.VISIBLE
                }
            }
            if (loggedIn) {
                // Sits beside the arrow, in the row header's own add slot.
                bind.itemAdd.setImageResource(R.drawable.ic_round_playlist_add_24)
                bind.itemAdd.contentDescription = activity.getString(R.string.stack_add_to)
                bind.itemAdd.visibility = View.VISIBLE
                // With no arrow after it, it takes over the arrow's end margin.
                if (stacks.isEmpty()) {
                    (bind.itemAdd.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = 16f.px
                }
                bind.itemAdd.setSafeOnClickListener { showAddToStack(activity, seriesId, seriesTitle, coverUrl) }
            }
            placeholder.addView(bind.root)
        }
    }

    /**
     * Lists the user's stacks for [seriesId] — ticked where it's already in one, greyed out where
     * it can't be added — plus "New stack…", which opens the editor with the series as its first
     * entry.
     */
    fun showAddToStack(activity: AppCompatActivity, seriesId: Long, seriesTitle: String?, coverUrl: String?) {
        activity.lifecycleScope.launch {
            val mine = when (val result = MangaBakaStacks.getMyStacksForSeries(seriesId)) {
                is MangaBakaStacks.Result.Ok -> result.value
                is MangaBakaStacks.Result.Error -> {
                    reportError(result)
                    return@launch
                }
            }
            val newLabel = activity.getString(R.string.stack_new_ellipsis)
            val labels = listOf(newLabel) + mine.map { s ->
                val state = when {
                    s.rank != null -> activity.getString(R.string.stack_contains_series)
                    !s.canAdd -> activity.getString(R.string.stack_cannot_add)
                    else -> activity.getString(visibilityLabel(s.visibility))
                }
                "${s.title}  ·  $state"
            }
            activity.materialDialog()
                .setTitle(R.string.stack_add_to)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which == 0) {
                        activity.startActivity(
                            MangaBakaStackEditActivity.createIntent(activity, seriesId, seriesTitle, coverUrl)
                        )
                        return@setItems
                    }
                    val stack = mine[which - 1]
                    if (stack.rank != null || !stack.canAdd) {
                        snackString(
                            if (stack.rank != null) R.string.stack_contains_series else R.string.stack_cannot_add
                        )
                        return@setItems
                    }
                    activity.lifecycleScope.launch {
                        when (val added = MangaBakaStacks.addEntry(stack.id, seriesId)) {
                            is MangaBakaStacks.Result.Ok ->
                                snackString(activity.getString(R.string.stack_added_to, stack.title))

                            is MangaBakaStacks.Result.Error -> reportError(added)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
