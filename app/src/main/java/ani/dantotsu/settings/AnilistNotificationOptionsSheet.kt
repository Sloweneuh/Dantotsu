package ani.dantotsu.settings

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.NotificationType
import ani.dantotsu.databinding.BottomSheetAnilistNotificationOptionsBinding
import ani.dantotsu.navBarHeight
import ani.dantotsu.snackString
import ani.dantotsu.toPx
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The account's own notification settings — which types AniList generates at all — edited in
 * place, laid out like the site's Notifications page. Every type is sent back in one UpdateUser
 * when the sheet closes (Save, swipe, or tap outside alike), so toggling several costs one
 * request; nothing is written if nothing changed.
 */
class AnilistNotificationOptionsSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetAnilistNotificationOptionsBinding? = null
    private val binding get() = _binding!!

    private var enabled: MutableMap<NotificationType, Boolean>? = null
    private var original: Map<NotificationType, Boolean>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAnilistNotificationOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Open at full height with no half-way stop: the Save button sits at the bottom of the
        // sheet, and a collapsed sheet would leave it off screen until the user dragged up.
        BottomSheetBehavior.from(requireView().parent as View).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.updatePadding(bottom = navBarHeight)
        binding.anilistNotificationOptionsSave.setOnClickListener { dismiss() }
        lifecycleScope.launch {
            val options = Anilist.notificationOptions
                ?: withContext(Dispatchers.IO) { Anilist.query.getNotificationOptions() }
            if (options == null) {
                snackString(R.string.anilist_notification_options_load_failed)
                dismiss()
                return@launch
            }
            // A type the account has no row for yet is one AniList sends by default.
            val state = NotificationType.anilistTypes
                .associateWith { options[it.name] ?: true }
                .toMutableMap()
            enabled = state
            original = state.toMap()
            if (_binding == null) return@launch
            buildRows(state)
            binding.anilistNotificationOptionsProgress.isVisible = false
        }
    }

    private fun buildRows(state: MutableMap<NotificationType, Boolean>) {
        val context = requireContext()
        val container = binding.anilistNotificationOptionsContainer
        SECTIONS.forEach { (headerRes, rows) ->
            container.addView(TextView(context).apply {
                text = getString(headerRes)
                textSize = 12f
                alpha = 0.58f
                typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
                updatePadding(top = 16.toPx, bottom = 4.toPx)
            })
            rows.forEach { (type, labelRes) ->
                container.addView(MaterialCheckBox(context).apply {
                    text = getString(labelRes)
                    textSize = 14f
                    typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
                    isChecked = state[type] == true
                    minHeight = 48.toPx
                    updatePadding(top = 6.toPx, bottom = 6.toPx)
                    setOnCheckedChangeListener { _, checked -> state[type] = checked }
                })
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        val state = enabled ?: return
        if (state == original) return
        val update = state.mapKeys { it.key.name }
        // The sheet is gone by now, so this can't ride on its lifecycle.
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            val saved = Anilist.mutation.updateNotificationOptions(update)
            withContext(Dispatchers.Main) {
                snackString(
                    if (saved != null) R.string.anilist_notification_options_saved
                    else R.string.anilist_notification_options_save_failed
                )
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        /**
         * The sections of anilist.co's Notifications settings page, with each type's sentence.
         * AIRING lives on the site's Anime & Manga page instead, so it gets a section of its own.
         */
        val SECTIONS: List<Pair<Int, List<Pair<NotificationType, Int>>>> = listOf(
            R.string.anilist_notification_section_activity_subscriptions to listOf(
                NotificationType.ACTIVITY_REPLY to R.string.anilist_notification_type_activity_reply,
                NotificationType.ACTIVITY_REPLY_SUBSCRIBED to R.string.anilist_notification_type_activity_reply_subscribed,
            ),
            R.string.anilist_notification_section_social to listOf(
                NotificationType.FOLLOWING to R.string.anilist_notification_type_following,
                NotificationType.ACTIVITY_MESSAGE to R.string.anilist_notification_type_activity_message,
                NotificationType.ACTIVITY_MENTION to R.string.anilist_notification_type_activity_mention,
                NotificationType.ACTIVITY_LIKE to R.string.anilist_notification_type_activity_like,
                NotificationType.ACTIVITY_REPLY_LIKE to R.string.anilist_notification_type_activity_reply_like,
                NotificationType.THREAD_COMMENT_REPLY to R.string.anilist_notification_type_thread_comment_reply,
                NotificationType.THREAD_COMMENT_MENTION to R.string.anilist_notification_type_thread_comment_mention,
                NotificationType.THREAD_COMMENT_LIKE to R.string.anilist_notification_type_thread_comment_like,
                NotificationType.THREAD_SUBSCRIBED to R.string.anilist_notification_type_thread_subscribed,
                NotificationType.THREAD_LIKE to R.string.anilist_notification_type_thread_like,
            ),
            R.string.anilist_notification_section_site_data to listOf(
                NotificationType.RELATED_MEDIA_ADDITION to R.string.anilist_notification_type_related_media_addition,
                NotificationType.MEDIA_DATA_CHANGE to R.string.anilist_notification_type_media_data_change,
                NotificationType.MEDIA_MERGE to R.string.anilist_notification_type_media_merge,
                NotificationType.MEDIA_DELETION to R.string.anilist_notification_type_media_deletion,
            ),
            R.string.anilist_notification_section_airing to listOf(
                NotificationType.AIRING to R.string.anilist_notification_type_airing,
            ),
        )
    }
}
