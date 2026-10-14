package ani.dantotsu.profile.notification

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Rect
import androidx.core.view.isInvisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemNotificationBinding
import com.google.android.material.color.MaterialColors
import com.xwray.groupie.viewbinding.GroupieViewHolder
import kotlin.math.abs

/**
 * Gmail-style swipes on notification cards: right marks read (or unread again), left deletes.
 *
 * Only the card moves; the row's backdrop stays put and shows the action, its icon growing once
 * the swipe has gone far enough to count. Left is only offered on cards that can be deleted.
 */
class NotificationSwipeCallback : ItemTouchHelper.Callback() {

    private lateinit var helper: ItemTouchHelper
    private var recyclerView: RecyclerView? = null

    fun attachTo(list: RecyclerView) {
        recyclerView = list
        helper = ItemTouchHelper(this)
        helper.attachToRecyclerView(list)
    }

    private fun itemOf(holder: RecyclerView.ViewHolder) =
        (holder as? GroupieViewHolder<*>)?.item as? NotificationItem

    private fun bindingOf(holder: RecyclerView.ViewHolder) =
        (holder as? GroupieViewHolder<*>)?.binding as? ItemNotificationBinding

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val item = itemOf(viewHolder) ?: return 0
        val swipe = ItemTouchHelper.RIGHT or (if (item.canDelete) ItemTouchHelper.LEFT else 0)
        return makeMovementFlags(0, swipe)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ) = false

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = THRESHOLD

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val item = itemOf(viewHolder) ?: return
        val stays = if (direction == ItemTouchHelper.RIGHT) item.onSwipedRead() else item.onSwipedDelete()
        val binding = bindingOf(viewHolder)
        val list = recyclerView
        if (!stays || binding == null || list == null) return
        // ItemTouchHelper expects a swiped row to go away: it holds on to the finished swipe until
        // the row leaves the screen, replaying its end position on every redraw and onto the next
        // touch, which sends the card straight back off. Re-attaching drops that, then the card
        // slides back in by itself.
        val from = binding.notificationCard.translationX
        list.post {
            helper.attachToRecyclerView(null)
            helper.attachToRecyclerView(list)
            slideBack(binding, from) { item.isUnread }
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val binding = bindingOf(viewHolder)
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE || binding == null) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            return
        }
        showSwipe(binding, dX, itemOf(viewHolder)?.isUnread ?: true, recyclerView.width * THRESHOLD)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        bindingOf(viewHolder)?.let { showSwipe(it, 0f, unread = true, threshold = 0f) }
    }

    companion object {
        /** Fraction of the list's width a swipe has to travel to go through. */
        const val THRESHOLD = 0.35f

        /** Brings a swiped card that stays in the list back from [from] over its backdrop. */
        private fun slideBack(binding: ItemNotificationBinding, from: Float, unread: () -> Boolean) {
            if (from == 0f) return
            ValueAnimator.ofFloat(from, 0f).apply {
                duration = 250
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    showSwipe(binding, it.animatedValue as Float, unread(), 0f)
                }
            }.start()
        }

        /**
         * Slides the card to [dX] and shows what the swipe does behind it. [threshold] (in px) is
         * where the icon pops to say "let go now"; 0 when the card is settling back by itself.
         */
        fun showSwipe(binding: ItemNotificationBinding, dX: Float, unread: Boolean, threshold: Float) {
            val backdrop = binding.notificationSwipeBackground
            binding.notificationCard.translationX = dX
            if (dX == 0f) {
                backdrop.isInvisible = true
                backdrop.clipBounds = null
                listOf(binding.notificationSwipeReadIcon, binding.notificationSwipeDeleteIcon).forEach {
                    it.animate().cancel()
                    it.scaleX = 1f
                    it.scaleY = 1f
                    it.setTag(R.id.swipe_armed, null)
                }
                return
            }
            backdrop.isInvisible = false
            val toRight = dX > 0
            binding.notificationSwipeRead.isInvisible = !toRight
            binding.notificationSwipeDelete.isInvisible = toRight

            val background = MaterialColors.getColor(
                backdrop,
                if (toRight) androidx.appcompat.R.attr.colorPrimary else androidx.appcompat.R.attr.colorError
            )
            val foreground = MaterialColors.getColor(
                backdrop,
                if (toRight) com.google.android.material.R.attr.colorOnPrimary
                else com.google.android.material.R.attr.colorOnError
            )
            backdrop.backgroundTintList = ColorStateList.valueOf(background)

            val icon = if (toRight) binding.notificationSwipeReadIcon else binding.notificationSwipeDeleteIcon
            val label = if (toRight) binding.notificationSwipeReadText else binding.notificationSwipeDeleteText
            if (toRight) {
                icon.setImageResource(
                    if (unread) R.drawable.ic_round_remove_red_eye_24
                    else R.drawable.ic_round_visibility_off_24
                )
                label.setText(
                    if (unread) R.string.notification_action_mark_read else R.string.mark_as_unread
                )
            }
            icon.imageTintList = ColorStateList.valueOf(foreground)
            label.setTextColor(foreground)

            // The action fades in over the first stretch, so a tap's jitter doesn't flash it.
            val density = backdrop.resources.displayMetrics.density
            (if (toRight) binding.notificationSwipeRead else binding.notificationSwipeDelete).alpha =
                (abs(dX) / (48 * density)).coerceIn(0f, 1f)

            // Only the strip the card has uncovered, plus its corner radius, so a dimmed (read)
            // card isn't tinted by the colour underneath it.
            val reach = (abs(dX) + 24 * density).toInt()
            val width = backdrop.width
            backdrop.clipBounds =
                if (toRight) Rect(0, 0, reach.coerceAtMost(width), backdrop.height)
                else Rect((width - reach).coerceAtLeast(0), 0, width, backdrop.height)

            val armed = threshold > 0f && abs(dX) >= threshold
            if (icon.getTag(R.id.swipe_armed) != armed) {
                icon.setTag(R.id.swipe_armed, armed)
                val scale = if (armed) 1.3f else 1f
                icon.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }
        }
    }
}
