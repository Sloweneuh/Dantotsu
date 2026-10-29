package ani.dantotsu.media.anime

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetPlayerSettingsBinding
import ani.dantotsu.settings.Settings
import ani.dantotsu.settings.SettingsRows
import ani.dantotsu.settings.SliderConfig
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.customAlertDialog
import kotlin.math.roundToInt

/**
 * The player's settings sheet, opened from the player as the readers open theirs: the settings
 * worth changing mid-episode, each taking effect as it is changed while the episode plays on, and
 * a row through to the full Player settings page for the rest.
 *
 * The settings are the global ones the full page edits — the player has no per-series settings —
 * and the player reads most of them as it goes. Subtitles are the exception, put back on the
 * screen through [ExoplayerView.applySubtitlePrefs] as they change.
 */
@UnstableApi
class PlayerSettingsSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlayerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetPlayerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val player = requireActivity() as ExoplayerView
        val ctx = requireContext()

        fun bind(list: RecyclerView, build: (refresh: () -> Unit) -> List<Settings>) =
            SettingsRows.bindRebuilt(list, build)

        fun pref(name: PrefName, title: Int, icon: Int, desc: Int? = null, enabled: Boolean = true,
                 then: () -> Unit = {}) =
            SettingsRows.switch(ctx, title, icon, PrefManager.getVal(name), desc, enabled) {
                PrefManager.setVal(name, it)
                then()
            }

        bind(binding.playerSubtitleRows) { refresh ->
            val on = PrefManager.getVal<Boolean>(PrefName.Subtitles)
            // Thickness and position are the text renderer's alone.
            val textRenderer = on && PrefManager.getVal<Boolean>(PrefName.TextviewSubtitles)
            listOf(
                pref(PrefName.Subtitles, R.string.sub_show, R.drawable.ic_round_subtitles_24) {
                    player.applySubtitlePrefs()
                    refresh()
                },
                pref(PrefName.TextviewSubtitles, R.string.sub_renderer, R.drawable.ic_round_short_text_24,
                    R.string.sub_renderer_desc, enabled = on) {
                    player.applySubtitleStyle()
                    refresh()
                },
                SettingsRows.slider(ctx, R.string.sub_size, R.drawable.ic_round_format_text_24, SliderConfig(
                    from = SUB_SIZE_MIN, to = SUB_SIZE_MAX, stepSize = 1f,
                    value = PrefManager.getVal<Int>(PrefName.FontSize).toFloat().coerceIn(SUB_SIZE_MIN, SUB_SIZE_MAX),
                    onChange = {
                        PrefManager.setVal(PrefName.FontSize, it.roundToInt())
                        player.applySubtitleStyle()
                    },
                    format = { getString(R.string.sub_size_value, it.roundToInt()) },
                ), enabled = on),
                SettingsRows.slider(ctx, R.string.sub_opacity, R.drawable.ic_round_brightness_medium_24, SliderConfig(
                    from = 0f, to = 1f, stepSize = 0.1f,
                    value = PrefManager.getVal<Float>(PrefName.SubAlpha).coerceIn(0f, 1f),
                    onChange = {
                        PrefManager.setVal(PrefName.SubAlpha, it)
                        player.applySubtitleStyle()
                    },
                    format = { "${(it * 100).roundToInt()}%" },
                ), enabled = on),
                // The player lifts the text by value/50 of the screen's height.
                SettingsRows.slider(ctx, R.string.sub_bottom, R.drawable.ic_round_swipe_up_alt_24, SliderConfig(
                    from = 0f, to = 50f, stepSize = 1f,
                    value = PrefManager.getVal<Float>(PrefName.SubBottomMargin).coerceIn(0f, 50f),
                    onChange = {
                        PrefManager.setVal(PrefName.SubBottomMargin, it)
                        player.applySubtitleStyle()
                    },
                    format = { "${(it * 2).roundToInt()}%" },
                ), enabled = textRenderer),
            )
        }

        bind(binding.playerPlaybackRows) { refresh ->
            homeActionRefresh = refresh
            listOf(
                pref(PrefName.AutoPlay, R.string.auto_play_next_episode, R.drawable.ic_round_play_arrow_24),
                pref(PrefName.NextEpisodeCountdown, R.string.next_episode_countdown, R.drawable.ic_round_schedule_24),
                pref(PrefName.SkipOutroToNext, R.string.skip_outro_to_next, R.drawable.ic_round_fast_forward_24,
                    R.string.skip_outro_to_next_desc),
                pref(PrefName.AlwaysContinue, R.string.always_continue, R.drawable.ic_round_replay_10_24),
                pref(PrefName.AutoSkipOPED, R.string.auto_skip_op_ed, R.drawable.ic_round_fast_forward_24) {
                    player.refreshSkipButton()
                },
                pref(PrefName.AutoSkipRecap, R.string.auto_skip_recap, R.drawable.ic_round_fast_forward_24),
                pref(PrefName.ShowTimeStampButton, R.string.show_skip_time_stamp_button,
                    R.drawable.ic_round_touch_app_24),
                homeActionRow(),
                pref(PrefName.PlayerBackgroundPlay, R.string.player_background_play,
                    R.drawable.ic_round_headphones_24, R.string.player_background_play_desc),
            )
        }

        bind(binding.playerControlsRows) { _ ->
            listOf(
                pref(PrefName.Gestures, R.string.gestures, R.drawable.ic_round_swipe_vertical_24) {
                    player.refreshGestures()
                },
                pref(PrefName.DoubleTap, R.string.double_tap, R.drawable.ic_round_touch_app_24) {
                    player.refreshGestures()
                },
                pref(PrefName.FastForward, R.string.fast_forward, R.drawable.ic_round_fast_forward_24),
                SettingsRows.slider(ctx, R.string.seek_time, R.drawable.ic_round_forward_10_24, SliderConfig(
                    from = 1f, to = 50f, stepSize = 1f,
                    value = PrefManager.getVal<Int>(PrefName.SeekTime).toFloat().coerceIn(1f, 50f),
                    onChange = { PrefManager.setVal(PrefName.SeekTime, it.roundToInt()) },
                    format = { "${it.roundToInt()} s" },
                )),
                pref(PrefName.FocusPause, R.string.pause_video_focus, R.drawable.ic_round_pause_24),
            )
        }

        bind(binding.playerMoreRows) { _ ->
            listOf(
                Settings(
                    type = 1,
                    name = getString(R.string.player_settings_all),
                    desc = getString(R.string.player_settings_all_desc),
                    icon = R.drawable.ic_round_video_settings_24,
                    onClick = {
                        dismiss()
                        player.openFullPlayerSettings()
                    },
                ),
            )
        }
    }

    /** What Home does: pause, picture-in-picture or keep playing; picked from a list. */
    private fun homeActionRow(): Settings {
        val names = PlayerHomeAction.names.map { getString(it) }.toTypedArray()
        return Settings(
            type = 1,
            name = getString(R.string.player_home_action),
            desc = names[PlayerHomeAction.current()],
            icon = R.drawable.ic_round_picture_in_picture_alt_24,
            onClick = {
                requireContext().customAlertDialog().apply {
                    setTitle(R.string.player_home_action)
                    singleChoiceItems(names, PlayerHomeAction.current()) { i ->
                        PlayerHomeAction.set(i)
                        homeActionRefresh?.invoke()
                    }
                    show()
                }
            },
        )
    }

    private var homeActionRefresh: (() -> Unit)? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ExoplayerView)?.onSettingsSheetClosed()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "PlayerSettingsSheet"
        private const val SUB_SIZE_MIN = 8f
        private const val SUB_SIZE_MAX = 64f
    }
}
