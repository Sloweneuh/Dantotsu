package ani.dantotsu.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.others.Xpandable
import com.google.android.material.color.MaterialColors

/**
 * A single, searchable setting somewhere in the settings tree.
 *
 * The registry below is the single source of truth for the settings search bar. It references the
 * same string resources the screens themselves use, so results stay localized and in sync with the
 * displayed labels. When you add a new toggle to a settings screen, add a matching entry here so it
 * remains findable.
 *
 * @param dest         the activity that owns this setting
 * @param titleRes     the label shown for the setting (and the search result)
 * @param sectionRes   the breadcrumb/section label shown under the result
 * @param icon         drawable shown next to the result (usually the section icon)
 * @param descRes      optional description, included in the search text
 * @param anchorViewId for XML-based screens, the id of the control to scroll to & flash. When 0,
 *                     the destination is a list screen and the row is matched by [titleRes].
 * @param keywords     extra space separated search terms that aren't part of the visible label
 */
data class SearchableSetting(
    val dest: Class<out Activity>,
    val titleRes: Int,
    val sectionRes: Int,
    val icon: Int,
    val descRes: Int = 0,
    val anchorViewId: Int = 0,
    val keywords: String = "",
)

object SettingsSearch {

    // Section icons, reused from the top-level settings list.
    private val IC_ACCOUNT = R.drawable.ic_round_person_24
    private val IC_THEME = R.drawable.ic_palette
    private val IC_COMMON = R.drawable.ic_lightbulb_24
    private val IC_ANIME = R.drawable.ic_round_movie_filter_24
    private val IC_MANGA = R.drawable.ic_round_import_contacts_24
    private val IC_EXTENSION = R.drawable.ic_extension
    private val IC_ADDON = R.drawable.ic_round_restaurant_24
    private val IC_NOTIF = R.drawable.ic_round_notifications_none_24
    private val IC_ABOUT = R.drawable.ic_round_info_24
    private val IC_PLAYER = R.drawable.ic_round_video_settings_24
    private val IC_READER = R.drawable.ic_round_import_contacts_24
    private val IC_UI = R.drawable.ic_round_auto_awesome_24

    val index: List<SearchableSetting> by lazy { buildIndex() }

    private fun buildIndex(): List<SearchableSetting> {
        val l = ArrayList<SearchableSetting>()

        // ---- Top level sections ----
        l += SearchableSetting(SettingsAccountActivity::class.java, R.string.accounts, R.string.settings, IC_ACCOUNT, R.string.accounts_desc)
        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.theme, R.string.settings, IC_THEME, R.string.theme_desc, keywords = "appearance color dark light")
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.common, R.string.settings, IC_COMMON, R.string.common_desc)
        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.anime, R.string.settings, IC_ANIME, R.string.anime_desc)
        l += SearchableSetting(SettingsMangaActivity::class.java, R.string.manga, R.string.settings, IC_MANGA, R.string.manga_desc)
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.extensions, R.string.settings, IC_EXTENSION, R.string.extensions_desc)
        l += SearchableSetting(SettingsAddonActivity::class.java, R.string.addons, R.string.settings, IC_ADDON, R.string.addons_desc)
        l += SearchableSetting(SettingsNotificationActivity::class.java, R.string.notifications, R.string.settings, IC_NOTIF, R.string.notifications_desc)
        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.about, R.string.settings, IC_ABOUT, R.string.about_desc)

        // ---- Accounts ----
        l += SearchableSetting(SettingsAccountActivity::class.java, R.string.enable_rpc, R.string.accounts, IC_ACCOUNT, R.string.enable_rpc_desc, keywords = "discord presence")
        l += SearchableSetting(AnilistSettingsActivity::class.java, R.string.anilist_settings, R.string.accounts, IC_ACCOUNT, R.string.alsettings_desc)
        l += SearchableSetting(MangaUpdatesSettingsActivity::class.java, R.string.mangaupdates_settings, R.string.accounts, IC_ACCOUNT, R.string.mangaupdates_settings_desc)
        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.connections_settings, R.string.accounts, IC_ACCOUNT, R.string.connections_desc)
        l += SearchableSetting(SettingsAccountActivity::class.java, R.string.comments_button, R.string.accounts, IC_ACCOUNT, R.string.comments_button_desc)

        // ---- Anilist account settings ----
        l += SearchableSetting(AnilistSettingsActivity::class.java, R.string.airing_notifications, R.string.anilist_settings, IC_ACCOUNT, R.string.airing_notifications_desc)
        l += SearchableSetting(AnilistSettingsActivity::class.java, R.string.display_adult_content, R.string.anilist_settings, IC_ACCOUNT, R.string.display_adult_content_desc, keywords = "nsfw 18")
        l += SearchableSetting(AnilistSettingsActivity::class.java, R.string.restrict_messages, R.string.anilist_settings, IC_ACCOUNT, R.string.restrict_messages_desc)

        // ---- MangaUpdates ----
        l += SearchableSetting(MangaUpdatesSettingsActivity::class.java, R.string.mu_tab_enabled, R.string.mangaupdates_settings, IC_ACCOUNT, R.string.mu_tab_enabled_desc)
        l += SearchableSetting(MangaUpdatesSettingsActivity::class.java, R.string.mu_list_fetch_enabled, R.string.mangaupdates_settings, IC_ACCOUNT, R.string.mu_list_fetch_enabled_desc)
        l += SearchableSetting(MangaUpdatesSettingsActivity::class.java, R.string.mu_custom_list_mapping, R.string.mangaupdates_settings, IC_ACCOUNT, R.string.mu_custom_list_mapping_desc)

        // ---- Connections ----
        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.disable_comick, R.string.connections_settings, IC_ACCOUNT, R.string.disable_comick_desc)
        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.disable_mal, R.string.connections_settings, IC_ACCOUNT, R.string.disable_mal_desc)
        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.disable_malsync, R.string.connections_settings, IC_ACCOUNT, R.string.disable_malsync_desc)

        // ---- Theme ----
        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.theme, R.string.theme, IC_THEME, anchorViewId = R.id.themeSwitcher, keywords = "color scheme palette dark light oled auto appearance")
        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.oled_theme_variant, R.string.theme, IC_THEME, R.string.oled_theme_variant_desc, keywords = "amoled black")
        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.use_material_you, R.string.theme, IC_THEME, R.string.use_material_you_desc, keywords = "monet dynamic")
        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.use_unique_theme_for_each_item, R.string.theme, IC_THEME, R.string.use_unique_theme_for_each_item_desc)
        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.use_custom_theme, R.string.theme, IC_THEME, R.string.use_custom_theme_desc)
        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.color_picker, R.string.theme, IC_THEME, R.string.color_picker_desc, keywords = "custom accent")

        // ---- Common ----
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.language_setting, R.string.common, IC_COMMON, keywords = "locale translation")
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.ui_settings, R.string.common, IC_UI, R.string.ui_settings_desc)
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.download_manager_select, R.string.common, IC_COMMON, R.string.download_manager_select_desc)
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.allow_metered_downloads, R.string.common, IC_COMMON, R.string.allow_metered_downloads_desc, keywords = "data wifi mobile")
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.app_lock, R.string.common, IC_COMMON, R.string.app_lock_desc, keywords = "password biometric pin security")
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.backup_restore, R.string.common, IC_COMMON, R.string.backup_restore_desc, keywords = "export import")
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.change_download_location, R.string.common, IC_COMMON, R.string.change_download_location_desc, keywords = "folder directory storage")
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.always_continue_content, R.string.common, IC_COMMON, R.string.always_continue_content_desc)
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.handoff_discovery_setting, R.string.common, IC_COMMON, R.string.handoff_discovery_setting_desc, keywords = "cast nearby lan")
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.hide_private, R.string.common, IC_COMMON, R.string.hide_private_desc)
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.search_source_list, R.string.common, IC_COMMON, R.string.search_source_list_desc)
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.recentlyListOnly, R.string.common, IC_COMMON, R.string.recentlyListOnly_desc)
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.adult_only_content, R.string.common, IC_COMMON, R.string.adult_only_content_desc, keywords = "nsfw 18")
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.selected_dns, R.string.common, IC_COMMON, anchorViewId = R.id.settingsExtensionDns, keywords = "doh dns over https cloudflare google")
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.startUpTab, R.string.common, IC_COMMON, anchorViewId = R.id.uiSettingsHome, keywords = "default startup home anime manga tab")

        // ---- User Interface ----
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.immersive_mode, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsImmersive, keywords = "fullscreen")
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.ui_show_system_bars, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsShowSystemBarsUI, keywords = "status navigation bar")
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.hide_notification_dot, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsHideRedDot, keywords = "red dot badge")
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.home_layout_show, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsHomeLayout)
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.home_stats_select, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsHomeStats)
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.small_view, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsSmallView)
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.show_anime_tab, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsShowAnimeTab)
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.show_manga_tab, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsShowMangaTab)
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.banner_animations, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsBannerAnimation)
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.layout_animations, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsLayoutAnimation)
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.trending_scroller, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsTrendingScroller)
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.blur_banners, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsBlurBanners)

        // ---- Anime ----
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.player_settings, R.string.anime, IC_PLAYER, R.string.player_settings_desc)
        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.purge_anime_downloads, R.string.anime, IC_ANIME, R.string.purge_anime_downloads_desc, keywords = "delete clear")
        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.prefer_dub, R.string.anime, IC_ANIME, R.string.prefer_dub_desc, keywords = "dubbed audio")
        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.show_yt, R.string.anime, IC_ANIME, R.string.show_yt_desc, keywords = "youtube trailer")
        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.include_list, R.string.anime, IC_ANIME, R.string.include_list_anime_desc)
        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.default_ep_view, R.string.anime, IC_ANIME, anchorViewId = R.id.settingsEpList, keywords = "episode list grid compact")

        // ---- Manga ----
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.reader_settings, R.string.manga, IC_READER, R.string.reader_settings_desc)
        l += SearchableSetting(SettingsMangaActivity::class.java, R.string.purge_manga_downloads, R.string.manga, IC_MANGA, R.string.purge_manga_downloads_desc, keywords = "delete clear")
        l += SearchableSetting(SettingsMangaActivity::class.java, R.string.purge_novel_downloads, R.string.manga, IC_MANGA, R.string.purge_novel_downloads_desc, keywords = "delete clear")
        l += SearchableSetting(SettingsMangaActivity::class.java, R.string.include_list, R.string.manga, IC_MANGA, R.string.include_list_desc)
        l += SearchableSetting(SettingsMangaActivity::class.java, R.string.default_chp_view, R.string.manga, IC_MANGA, anchorViewId = R.id.settingsChpList, keywords = "chapter list compact")

        // ---- Player settings (XML) ----
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.default_playback_speed, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsSpeed, keywords = "video")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.cursed_speeds, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsCursedSpeeds, keywords = "playback speed")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.resize_mode_button, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerResizeMode, keywords = "video aspect fit fill")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.subtitle_toggle, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.subSwitch, keywords = "subtitles captions")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.primary_sub_color_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubColorPrimary, keywords = "subtitle color")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.secondary_sub_color_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubColorSecondary, keywords = "subtitle color")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.secondary_sub_outline_type_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubOutline, keywords = "subtitle outline")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.sub_background_color_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubColorBackground, keywords = "subtitle background color")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.sub_window_color_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubColorWindow, keywords = "subtitle window color")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.sub_alpha, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubAlphaButton, keywords = "subtitle opacity transparency")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.textview_sub, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.subTextSwitch, keywords = "subtitle text")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.textview_sub_stroke, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubStrokeButton, keywords = "subtitle stroke")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.textview_sub_bottom_margin, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubBottomMarginButton, keywords = "subtitle margin")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.sub_font_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubFont, keywords = "subtitle font typeface")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.subtitle_font_size, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.subtitle_font_size_text, keywords = "subtitle text size")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.subtitle_langauge, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubLanguage, keywords = "subtitle language")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.always_load_time_stamps, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsTimeStamps, keywords = "aniskip timestamps")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.timestamp_proxy, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsTimeStampsProxy, keywords = "aniskip proxy")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.show_skip_time_stamp_button, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsShowTimeStamp, keywords = "skip op ed")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_hide_time_stamps, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsTimeStampsAutoHide)
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_skip_op_ed, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAutoSkipOpEd, keywords = "opening ending intro outro")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_skip_recap, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAutoSkipRecap)
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_play_next_episode, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAutoPlay, keywords = "autoplay")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_skip_fillers, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAutoSkip, keywords = "filler")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.ask_update_progress_anime, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAskUpdateProgress)
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.ask_update_progress_chapter_zero, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAskChapterZero)
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.ask_update_progress_hentai, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAskUpdateHentai)
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.always_continue, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAlwaysContinue)
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.pause_video_focus, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsPauseVideo)
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.gestures, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsVerticalGestures, keywords = "brightness volume swipe")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.fast_forward, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsFastForward, keywords = "seek")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.double_tap, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsDoubleTap, keywords = "seek skip")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.picture_in_picture, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsPiP, keywords = "pip")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.show_cast_button, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsCast, keywords = "chromecast")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.show_rotate_button, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsRotate, keywords = "orientation")
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.try_internal_cast_experimental, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsInternalCast)
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.use_additional_codec, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAdditionalCodec, keywords = "ffmpeg decoder")

        // ---- Reader settings (XML) ----
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.auto_detect_webtoon, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAutoWebToon)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.over_scroll, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsOverscroll)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.true_colors, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsTrueColors)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.image_rotation, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsImageRotation)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.crop_borders, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsCropBorders)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.spaced_pages, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsPadding)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.hide_scroll_bar, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsHideScrollBar)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.hide_page_numbers, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsHidePageNumbers)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.horizontal_scroll_bar, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsHorizontalScrollBar)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.keep_screen_on, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsKeepScreenOn, keywords = "manga")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.lock_screen_rotation, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsLockRotation)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.autoscroll, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAutoscrollEnabled)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.volume_buttons, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsVolumeButton, keywords = "manga")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.wrap_images, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsWrapImages)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.image_long_clicking, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsLongClickImage)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.layout, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsLayoutText, keywords = "manga reading mode paged webtoon continuous")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.direction, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsDirectionText, keywords = "manga reading ltr rtl")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.dual_page, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsDualPageText, keywords = "manga double page spread")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.source_info, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsSourceName)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.continuous_multi_chapter, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsContinuousMultiChapter)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.show_system_bars, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsSystemBars)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.ask_update_progress_manga, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAskUpdateProgress)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.ask_update_progress_chapter_zero, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAskChapterZero)
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.ask_update_progress_doujin, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAskUpdateDoujins)
        // Novel reader sub-section
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.use_dark_theme, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNuseDarkTheme, keywords = "novel light novel")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.use_oled_theme, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNuseOledTheme, keywords = "novel amoled")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.keep_screen_on, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNkeepScreenOn, keywords = "novel light novel")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.volume_buttons, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNvolumeButton, keywords = "novel light novel")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.layout, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNlayoutText, keywords = "novel light novel reading mode")
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.dual_page, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNdualPageText, keywords = "novel light novel")

        // ---- Extensions ----
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.anime_add_repository, R.string.extensions, IC_EXTENSION, R.string.anime_add_repository_desc, keywords = "repo source")
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.manga_add_repository, R.string.extensions, IC_EXTENSION, R.string.manga_add_repository_desc, keywords = "repo source")
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.novel_add_repository, R.string.extensions, IC_EXTENSION, R.string.novel_add_repository_desc, keywords = "repo source")
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.extension_test, R.string.extensions, IC_EXTENSION, R.string.extension_test_desc)
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.user_agent, R.string.extensions, IC_EXTENSION, R.string.user_agent_desc)
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.proxy, R.string.extensions, IC_EXTENSION, R.string.proxy_desc)
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.proxy_setup, R.string.extensions, IC_EXTENSION, R.string.proxy_setup_desc)
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.force_legacy_installer, R.string.extensions, IC_EXTENSION, R.string.force_legacy_installer_desc)
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.skip_loading_extension_icons, R.string.extensions, IC_EXTENSION, R.string.skip_loading_extension_icons_desc)
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.NSFWExtention, R.string.extensions, IC_EXTENSION, R.string.NSFWExtention_desc, keywords = "nsfw adult")

        // ---- Add-ons ----
        l += SearchableSetting(SettingsAddonActivity::class.java, R.string.anime_downloader_addon, R.string.addons, IC_ADDON, keywords = "download")
        l += SearchableSetting(SettingsAddonActivity::class.java, R.string.torrent_addon, R.string.addons, IC_ADDON, keywords = "torrent")
        l += SearchableSetting(SettingsAddonActivity::class.java, R.string.enable_torrent, R.string.addons, IC_ADDON, R.string.enable_torrent_desc)

        // ---- Notifications ----
        l += SearchableSetting(SettingsSubscriptionNotificationActivity::class.java, R.string.subscription_notifications, R.string.notifications, IC_NOTIF, R.string.subscription_notifications_desc)
        l += SearchableSetting(SettingsUnreadChapterNotificationActivity::class.java, R.string.unread_chapter_notifications, R.string.notifications, IC_NOTIF, R.string.unread_chapter_notifications_desc)
        l += SearchableSetting(SettingsAnilistNotificationActivity::class.java, R.string.anilist_notifications, R.string.notifications, IC_NOTIF, R.string.anilist_notifications_desc)
        l += SearchableSetting(SettingsCommentNotificationActivity::class.java, R.string.comment_notifications, R.string.notifications, IC_NOTIF, R.string.comment_notifications_desc)
        l += SearchableSetting(SettingsMuNotificationActivity::class.java, R.string.mu_notifications, R.string.notifications, IC_NOTIF, R.string.mu_notifications_desc)
        l += SearchableSetting(SettingsNotificationActivity::class.java, R.string.use_alarm_manager_reliable, R.string.notifications, IC_NOTIF, R.string.use_alarm_manager_reliable_desc, keywords = "exact alarm")
        // Notification children
        l += SearchableSetting(SettingsAnilistNotificationActivity::class.java, R.string.anilist_notification_filters, R.string.anilist_notifications, IC_NOTIF, R.string.anilist_notification_filters_desc)
        l += SearchableSetting(SettingsSubscriptionNotificationActivity::class.java, R.string.notification_for_checking_subscriptions, R.string.subscription_notifications, IC_NOTIF, R.string.notification_for_checking_subscriptions_desc)
        l += SearchableSetting(SettingsSubscriptionNotificationActivity::class.java, R.string.view_subscriptions, R.string.subscription_notifications, IC_NOTIF, R.string.view_subscriptions_desc)
        l += SearchableSetting(SettingsUnreadChapterNotificationActivity::class.java, R.string.unread_chapter_check_progress_notification, R.string.unread_chapter_notifications, IC_NOTIF, R.string.unread_chapter_check_progress_notification_desc)
        l += SearchableSetting(SettingsUnreadChapterNotificationActivity::class.java, R.string.clear_unread_chapter_history, R.string.unread_chapter_notifications, IC_NOTIF, R.string.clear_unread_chapter_history_desc)
        l += SearchableSetting(SettingsMuNotificationActivity::class.java, R.string.mu_notifications_enabled, R.string.mu_notifications, IC_NOTIF, R.string.mu_notifications_enabled_desc)

        // ---- About ----
        l += SearchableSetting(FAQActivity::class.java, R.string.faq, R.string.about, IC_ABOUT, R.string.faq_desc, keywords = "help frequently asked questions")
        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.check_app_updates, R.string.about, IC_ABOUT, R.string.check_app_updates_desc, keywords = "update version")
        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.share_username_in_crash_reports, R.string.about, IC_ABOUT, R.string.share_username_in_crash_reports_desc)
        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.disable_crash_reports, R.string.about, IC_ABOUT, R.string.disable_crash_reports_desc, keywords = "telemetry analytics")
        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.log_to_file, R.string.about, IC_ABOUT, R.string.logging_warning, keywords = "logging debug")
        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.devs, R.string.about, IC_ABOUT, R.string.devs_desc, keywords = "developers credits")
        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.disclaimer, R.string.about, IC_ABOUT, R.string.disclaimer_desc)
        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.privacy_policy, R.string.about, IC_ABOUT, R.string.privacy_policy_desc)

        return l
    }

    /** Returns the settings matching [raw], ranked with the closest title matches first. */
    fun query(context: Context, raw: String): List<SearchableSetting> {
        val q = raw.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(" ").filter { it.isNotBlank() }
        return index.mapNotNull { e ->
            val title = context.getString(e.titleRes).lowercase()
            val desc = if (e.descRes != 0) context.getString(e.descRes).lowercase() else ""
            val section = context.getString(e.sectionRes).lowercase()
            val haystack = "$title $desc $section ${e.keywords.lowercase()}"
            if (tokens.all { haystack.contains(it) }) {
                val score = when {
                    title == q -> 0
                    title.startsWith(q) -> 1
                    title.contains(q) -> 2
                    else -> 3
                }
                e to score
            } else null
        }.sortedWith(compareBy({ it.second }, { context.getString(it.first.titleRes) }))
            .map { it.first }
    }
}

/**
 * Opens a settings screen from a search result and, on arrival, scrolls to and briefly flashes the
 * target control so the user lands directly on "the smallest toggle".
 */
object SettingsRouter {
    const val EXTRA_ANCHOR_VIEW = "ani.dantotsu.settings.ANCHOR_VIEW"
    const val EXTRA_ANCHOR_TITLE = "ani.dantotsu.settings.ANCHOR_TITLE"

    fun open(context: Context, setting: SearchableSetting) {
        val intent = Intent(context, setting.dest)
        if (setting.anchorViewId != 0) {
            intent.putExtra(EXTRA_ANCHOR_VIEW, setting.anchorViewId)
        } else {
            intent.putExtra(EXTRA_ANCHOR_TITLE, setting.titleRes)
        }
        context.startActivity(intent)
    }

    /**
     * Call from a settings activity's onCreate (after its adapter is set). Reads the anchor extras
     * and, if present, scrolls to & flashes the matching control. [recycler] is required only for
     * list-style screens that match by title.
     */
    fun handleHighlight(activity: Activity, vararg recyclers: RecyclerView) {
        val viewId = activity.intent.getIntExtra(EXTRA_ANCHOR_VIEW, 0)
        val titleRes = activity.intent.getIntExtra(EXTRA_ANCHOR_TITLE, 0)
        when {
            viewId != 0 -> {
                val target = activity.findViewById<View>(viewId) ?: return
                expandSections(target)
                target.doOnPreDraw { scrollToAndFlash(target) }
            }

            titleRes != 0 && recyclers.isNotEmpty() -> {
                val title = activity.getString(titleRes)
                recyclers.forEach { recycler -> scheduleListHighlight(recycler, title, attempts = 12) }
            }
        }
    }

    private fun scrollToAndFlash(target: View) {
        val scroll = findScrollParent(target)
        if (scroll != null) {
            val y = (relativeTop(target, scroll) - dp(target, 80)).coerceAtLeast(0)
            scroll.smoothScrollTo(0, y)
        }
        val views = groupToFlash(target)
        // Delay the flash slightly so it is seen after the scroll settles.
        Handler(Looper.getMainLooper()).postDelayed({ flashGroup(views) }, 350)
    }

    /**
     * In the XML settings screens a control's description is a separate, dimmed [TextView] placed
     * right after it. Flash the control together with any such trailing description siblings so the
     * whole setting is highlighted. (For list rows the description is inside the row itself, so this
     * just returns the row.)
     */
    private fun groupToFlash(target: View): List<View> {
        val views = mutableListOf(target)
        val parent = target.parent as? ViewGroup ?: return views
        var i = parent.indexOfChild(target) + 1
        while (i < parent.childCount) {
            val sibling = parent.getChildAt(i)
            val isDescription = sibling is TextView && sibling !is Button &&
                !sibling.isClickable && sibling.alpha < 1f
            if (!isDescription) break
            views.add(sibling)
            i++
        }
        return views
    }

    /**
     * Finds the row titled [title] in [recycler] and highlights it. The adapter may not be set and
     * the rows may not be laid out yet when navigating in, so this retries on the next frame until
     * the row is available (or [attempts] run out).
     */
    private fun scheduleListHighlight(recycler: RecyclerView, title: String, attempts: Int) {
        val adapter = recycler.adapter as? SettingsAdapter
        if (adapter == null) {
            // Adapter is assigned later in onCreate; wait for it.
            if (attempts > 0) recycler.postOnAnimation { scheduleListHighlight(recycler, title, attempts - 1) }
            return
        }
        val pos = adapter.indexOfTitle(title)
        if (pos < 0) return // The row isn't in this list.
        val target = recycler.layoutManager?.findViewByPosition(pos)
            ?: recycler.findViewHolderForAdapterPosition(pos)?.itemView
        if (target != null) {
            scrollToAndFlash(target)
            return
        }
        // Rows not laid out yet; try again next frame.
        if (attempts > 0) recycler.postOnAnimation { scheduleListHighlight(recycler, title, attempts - 1) }
    }

    /** Expands any collapsed [Xpandable] sections that contain [view] so it becomes visible. */
    private fun expandSections(view: View) {
        var p = view.parent
        while (p != null) {
            if (p is Xpandable) p.expand()
            p = (p as? View)?.parent
        }
    }

    private fun findScrollParent(view: View): NestedScrollView? {
        var p = view.parent
        while (p != null) {
            if (p is NestedScrollView) return p
            p = (p as? View)?.parent
        }
        return null
    }

    private fun relativeTop(view: View, ancestor: View): Int {
        var y = 0
        var v: View? = view
        while (v != null && v !== ancestor) {
            y += v.top
            v = v.parent as? View
        }
        return y
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    /**
     * Flashes [views] as one continuous highlight. The views are siblings, so a single translucent
     * rectangle covering their union bounds is drawn on the shared parent's overlay — this avoids
     * the stacked-alpha seam you'd get from flashing each view's foreground separately.
     */
    private fun flashGroup(views: List<View>) {
        val anchor = views.firstOrNull() ?: return
        val parent = anchor.parent as? ViewGroup ?: run { flashSingle(anchor); return }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        views.forEach { v ->
            if (v.parent === parent) {
                left = minOf(left, v.left)
                top = minOf(top, v.top)
                right = maxOf(right, v.right)
                bottom = maxOf(bottom, v.bottom)
            }
        }
        if (left == Int.MAX_VALUE) {
            flashSingle(anchor)
            return
        }
        val color = MaterialColors.getColor(
            anchor, com.google.android.material.R.attr.colorPrimary, Color.CYAN
        )
        val highlight = ColorDrawable(color).apply { setBounds(left, top, right, bottom) }
        val overlay = parent.overlay
        overlay.add(highlight)
        ValueAnimator.ofInt(0, 110, 0, 110, 0).apply {
            duration = 1500
            addUpdateListener { highlight.alpha = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.remove(highlight)
                }
            })
            start()
        }
    }

    private fun flashSingle(view: View) {
        val color = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorPrimary, Color.CYAN
        )
        val overlay = ColorDrawable(color)
        val previousForeground = view.foreground
        view.foreground = overlay
        ValueAnimator.ofInt(0, 110, 0, 110, 0).apply {
            duration = 1500
            addUpdateListener { overlay.alpha = it.animatedValue as Int }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.foreground = previousForeground
                }
            })
            start()
        }
    }
}
