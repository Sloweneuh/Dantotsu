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
import ani.dantotsu.download.DownloadActivity
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
 * @param rowTitleRes  a second title to try when highlighting, for a setting whose row isn't
 *                     titled [titleRes]: either the row's label changes with state (the backup
 *                     screen's sync row reads "Sync code" once linked and "Set up sync" before
 *                     that), or the setting lives in a dialog and the row that opens it is named
 *                     after something else (the unread order, inside the MALSync checks dialog).
 *                     Whichever of the two is on screen matches; the other finds nothing.
 * @param keywordsRes  extra space separated search terms that aren't part of the visible label.
 *                     A string resource rather than a literal so it is translated with everything
 *                     else — the labels have always searched in the app's own language, and terms
 *                     stranded in English were the one part of a result that didn't. A locale with
 *                     no translation for one falls back to the English set, which is no worse than
 *                     what it had.
 * @param intentTab    for [ani.dantotsu.download.DownloadActivity], which ViewPager tab ("tab"
 *                     intent extra) to land on; -1 leaves it at the activity's default. The
 *                     setting itself lives inside a Fragment-hosted list there, not a plain
 *                     Activity RecyclerView, so it can't be scroll-highlighted like the rest of
 *                     the registry — landing on the right tab is the best this can do.
 * @param requiresOnline excluded from search results while offline, same as the row itself would
 *                     be if the destination screen hides it there. Only needed for entries whose
 *                     section mixes online and offline rows (Backup & sync does: local
 *                     backup/restore stays usable, the cloud rows don't) — a section that's
 *                     entirely one or the other, like Accounts, is filtered by [sectionRes]
 *                     instead, in [query].
 */
data class SearchableSetting(
    val dest: Class<out Activity>,
    val titleRes: Int,
    val sectionRes: Int,
    val icon: Int,
    val descRes: Int = 0,
    val anchorViewId: Int = 0,
    val rowTitleRes: Int = 0,
    val keywordsRes: Int = 0,
    val intentTab: Int = -1,
    val requiresOnline: Boolean = false,
)

object SettingsSearch {

    // Section icons, reused from the top-level settings list.
    private val IC_ACCOUNT = R.drawable.ic_round_person_24
    private val IC_COMMON = R.drawable.ic_lightbulb_24
    private val IC_ANIME = R.drawable.ic_round_movie_filter_24
    private val IC_MANGA = R.drawable.ic_round_import_contacts_24
    private val IC_PLAYER = R.drawable.ic_round_video_settings_24
    private val IC_READER = R.drawable.ic_round_import_contacts_24
    private val IC_UI = R.drawable.ic_round_auto_awesome_24
    private val IC_DOWNLOAD = R.drawable.ic_download_24

    val index: List<SearchableSetting> by lazy { buildIndex() }

    private fun buildIndex(): List<SearchableSetting> {
        val l = ArrayList<SearchableSetting>()

        // ---- Top level sections ----
        l += SearchableSetting(SettingsAccountActivity::class.java, R.string.accounts, R.string.settings, R.drawable.ic_round_manage_accounts_24, R.string.accounts_desc, keywordsRes = R.string.search_kw_accounts, requiresOnline = true)

        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.theme, R.string.settings, R.drawable.ic_palette, R.string.theme_desc, keywordsRes = R.string.search_kw_theme)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.common, R.string.settings, R.drawable.ic_round_settings_24, R.string.common_desc, keywordsRes = R.string.search_kw_common)

        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.anime, R.string.settings, R.drawable.ic_round_movie_filter_24, R.string.anime_desc, keywordsRes = R.string.search_kw_anime)

        l += SearchableSetting(SettingsMangaActivity::class.java, R.string.manga, R.string.settings, R.drawable.ic_round_import_contacts_24, R.string.manga_desc, keywordsRes = R.string.search_kw_manga)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.extensions, R.string.settings, R.drawable.ic_extension, R.string.extensions_desc, keywordsRes = R.string.search_kw_extensions)

        l += SearchableSetting(SettingsAddonActivity::class.java, R.string.addons, R.string.settings, R.drawable.ic_round_widgets_24, R.string.addons_desc, keywordsRes = R.string.search_kw_addons)

        l += SearchableSetting(SettingsNotificationActivity::class.java, R.string.notifications, R.string.settings, R.drawable.ic_round_notifications_none_24, R.string.notifications_desc, keywordsRes = R.string.search_kw_notifications)

        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.about, R.string.settings, R.drawable.ic_round_info_24, R.string.about_desc, keywordsRes = R.string.search_kw_about)


        // ---- Accounts ----
        l += SearchableSetting(SettingsAccountActivity::class.java, R.string.enable_rpc, R.string.accounts, R.drawable.ic_discord, R.string.enable_rpc_desc, keywordsRes = R.string.search_kw_enable_rpc)

        l += SearchableSetting(AnilistSettingsActivity::class.java, R.string.anilist_settings, R.string.accounts, R.drawable.ic_anilist, R.string.alsettings_desc, keywordsRes = R.string.search_kw_anilist_settings)

        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.connections_settings, R.string.accounts, R.drawable.network_node_24, R.string.connections_desc, keywordsRes = R.string.search_kw_connections_settings)

        l += SearchableSetting(SettingsListSyncActivity::class.java, R.string.list_sync_settings, R.string.accounts, R.drawable.ic_round_sync_24, R.string.list_sync_settings_desc, keywordsRes = R.string.search_kw_list_sync_settings)

        l += SearchableSetting(SettingsAccountActivity::class.java, R.string.login_to_mangabaka, R.string.accounts, IC_ACCOUNT, R.string.mangabaka_login_desc, anchorViewId = R.id.settingsMangaBakaLogin, keywordsRes = R.string.search_kw_login_to_mangabaka)

        l += SearchableSetting(SettingsAccountActivity::class.java, R.string.comments_button, R.string.accounts, R.drawable.ic_round_comment_24, R.string.comments_button_desc, keywordsRes = R.string.search_kw_comments_button)


        // ---- Anilist account settings ----
        l += SearchableSetting(AnilistSettingsActivity::class.java, R.string.airing_notifications, R.string.anilist_settings, R.drawable.ic_round_notifications_active_24, R.string.airing_notifications_desc, keywordsRes = R.string.search_kw_airing_notifications)

        l += SearchableSetting(AnilistSettingsActivity::class.java, R.string.display_adult_content, R.string.anilist_settings, R.drawable.ic_round_nsfw_24, R.string.display_adult_content_desc, keywordsRes = R.string.search_kw_display_adult_content)

        l += SearchableSetting(AnilistSettingsActivity::class.java, R.string.restrict_messages, R.string.anilist_settings, R.drawable.ic_round_comments_disabled_24, R.string.restrict_messages_desc, keywordsRes = R.string.search_kw_restrict_messages)


        // MangaUpdates had a screen of its own holding only these three rows; they now sit with the
        // other services in Connections. The old "MangaUpdates tab" wording is gone with it — the
        // row it named writes the same enable-the-service preference as its four neighbours.
        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.disable_mangaupdates, R.string.connections_settings, R.drawable.ic_round_mangaupdates_24, R.string.disable_mangaupdates_desc, keywordsRes = R.string.search_kw_disable_mangaupdates)

        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.mu_list_fetch_enabled, R.string.connections_settings, R.drawable.ic_round_mangaupdates_list_24, R.string.mu_list_fetch_enabled_desc, keywordsRes = R.string.search_kw_mu_list_fetch_enabled)

        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.mu_custom_list_mapping, R.string.connections_settings, R.drawable.ic_round_mangaupdates_mapping_24, R.string.mu_custom_list_mapping_desc, keywordsRes = R.string.search_kw_mu_custom_list_mapping)


        // ---- Connections ----
        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.disable_comick, R.string.connections_settings, R.drawable.ic_round_comick_24, R.string.disable_comick_desc, keywordsRes = R.string.search_kw_disable_comick)

        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.disable_mal, R.string.connections_settings, R.drawable.ic_myanimelist, R.string.disable_mal_desc, keywordsRes = R.string.search_kw_disable_mal)

        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.disable_malsync, R.string.connections_settings, R.drawable.ic_malsync, R.string.disable_malsync_desc, keywordsRes = R.string.search_kw_disable_malsync)

        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.malsync_exclude_manage, R.string.connections_settings, R.drawable.ic_round_malsync_exclude_24, R.string.malsync_exclude_manage_desc, keywordsRes = R.string.search_kw_malsync_exclude_manage)

        // Lives in the dialog behind the MALSync row's settings icon, so that row is what gets
        // flashed — there is no row of its own to land on.
        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.unread_sort_label, R.string.connections_settings, IC_ACCOUNT, R.string.unread_sort_desc, rowTitleRes = R.string.disable_malsync, keywordsRes = R.string.search_kw_unread_sort_label)

        l += SearchableSetting(SettingsConnectionsActivity::class.java, R.string.customize_info_tabs, R.string.connections_settings, R.drawable.ic_round_view_array_24, R.string.customize_info_tabs_desc, keywordsRes = R.string.search_kw_customize_info_tabs)


        // ---- List sync ----
        l += SearchableSetting(ListSyncCompareActivity::class.java, R.string.compare_lists, R.string.list_sync_settings, R.drawable.ic_round_compare_arrows_24, R.string.compare_lists_desc, keywordsRes = R.string.search_kw_compare_lists)

        l += SearchableSetting(SettingsListSyncActivity::class.java, R.string.auto_list_sync, R.string.list_sync_settings, R.drawable.ic_round_compare_schedule_24, R.string.auto_list_sync_desc, keywordsRes = R.string.search_kw_auto_list_sync)

        l += SearchableSetting(SettingsListSyncActivity::class.java, R.string.auto_list_sync_removals, R.string.list_sync_settings, R.drawable.ic_round_delete_sweep_24, R.string.auto_list_sync_removals_desc, keywordsRes = R.string.search_kw_auto_list_sync_removals)

        l += SearchableSetting(SettingsListSyncActivity::class.java, R.string.mal_list_sync, R.string.list_sync_settings, R.drawable.ic_round_mal_sync_24, R.string.mal_list_sync_desc, keywordsRes = R.string.search_kw_mal_list_sync)

        l += SearchableSetting(SettingsListSyncActivity::class.java, R.string.mangabaka_list_sync, R.string.list_sync_settings, R.drawable.ic_round_mangabaka_sync_24, R.string.mangabaka_list_sync_desc, keywordsRes = R.string.search_kw_mangabaka_list_sync)


        // ---- Theme ----
        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.theme, R.string.theme, R.drawable.ic_palette, anchorViewId = R.id.themeSwitcher, keywordsRes = R.string.search_kw_theme_2)

        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.oled_theme_variant, R.string.theme, R.drawable.ic_round_brightness_4_24, R.string.oled_theme_variant_desc, keywordsRes = R.string.search_kw_oled_theme_variant)

        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.use_material_you, R.string.theme, R.drawable.ic_round_auto_awesome_24, R.string.use_material_you_desc, keywordsRes = R.string.search_kw_use_material_you)

        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.use_unique_theme_for_each_item, R.string.theme, R.drawable.ic_palette, R.string.use_unique_theme_for_each_item_desc, keywordsRes = R.string.search_kw_use_unique_theme_for_each_item)

        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.use_custom_theme, R.string.theme, R.drawable.ic_round_color_24, R.string.use_custom_theme_desc, keywordsRes = R.string.search_kw_use_custom_theme)

        l += SearchableSetting(SettingsThemeActivity::class.java, R.string.color_picker, R.string.theme, R.drawable.ic_round_color_picker_24, R.string.color_picker_desc, keywordsRes = R.string.search_kw_color_picker)


        // ---- Common ----
        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.language_setting, R.string.common, R.drawable.ic_round_language_24, keywordsRes = R.string.search_kw_language_setting)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.ui_settings, R.string.common, R.drawable.ic_round_grid_view_24, R.string.ui_settings_desc, keywordsRes = R.string.search_kw_ui_settings)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.app_lock, R.string.common, R.drawable.ic_baseline_screen_lock_portrait_24, R.string.app_lock_desc, keywordsRes = R.string.search_kw_app_lock)

        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.backup_sync, R.string.common, R.drawable.backup_restore, R.string.backup_sync_desc, keywordsRes = R.string.search_kw_backup_sync)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.always_continue_content, R.string.common, R.drawable.ic_round_resume_24, R.string.always_continue_content_desc, keywordsRes = R.string.search_kw_always_continue_content)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.handoff_discovery_setting, R.string.common, R.drawable.ic_round_cast_24, R.string.handoff_discovery_setting_desc, keywordsRes = R.string.search_kw_handoff_discovery_setting)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.capture_defaults, R.string.common, R.drawable.ic_round_screenshot_frame_24, R.string.capture_defaults_desc, keywordsRes = R.string.search_kw_capture_defaults)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.hide_private, R.string.common, R.drawable.ic_round_visibility_off_24, R.string.hide_private_desc, keywordsRes = R.string.search_kw_hide_private)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.search_source_list, R.string.common, R.drawable.ic_round_manage_search_24, R.string.search_source_list_desc, keywordsRes = R.string.search_kw_search_source_list)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.recentlyListOnly, R.string.common, R.drawable.ic_round_history_24, R.string.recentlyListOnly_desc, keywordsRes = R.string.search_kw_recentlyListOnly)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.adult_only_content, R.string.common, R.drawable.ic_round_nsfw_24, R.string.adult_only_content_desc, keywordsRes = R.string.search_kw_adult_only_content)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.hidden_from_lists_manage, R.string.common, R.drawable.ic_round_playlist_remove_24, R.string.hidden_from_lists_manage_desc, keywordsRes = R.string.search_kw_hidden_from_lists_manage)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.selected_dns, R.string.common, IC_COMMON, anchorViewId = R.id.settingsExtensionDns, keywordsRes = R.string.search_kw_selected_dns)

        l += SearchableSetting(SettingsCommonActivity::class.java, R.string.startUpTab, R.string.common, IC_COMMON, anchorViewId = R.id.uiSettingsHome, keywordsRes = R.string.search_kw_startUpTab)


        // ---- Downloads ---- (live inside DownloadActivity, not a Settings screen: most are in
        // the settings dialog opened via its cog icon, which these entries open automatically;
        // only the download location stays inline, on the Manage tab)
        l += SearchableSetting(DownloadActivity::class.java, R.string.download_manager_select, R.string.downloads, R.drawable.ic_round_download_manager_24, R.string.download_manager_select_desc, keywordsRes = R.string.search_kw_download_manager_select)

        l += SearchableSetting(DownloadActivity::class.java, R.string.allow_metered_downloads, R.string.downloads, R.drawable.ic_round_download_metered_24, R.string.allow_metered_downloads_desc, keywordsRes = R.string.search_kw_allow_metered_downloads)

        l += SearchableSetting(DownloadActivity::class.java, R.string.change_download_location, R.string.downloads, IC_DOWNLOAD, R.string.change_download_location_desc, intentTab = 1, keywordsRes = R.string.search_kw_change_download_location)

        l += SearchableSetting(DownloadActivity::class.java, R.string.purge_anime_downloads, R.string.downloads, R.drawable.ic_round_purge_anime_24, R.string.purge_anime_downloads_desc, keywordsRes = R.string.search_kw_purge_anime_downloads)

        l += SearchableSetting(DownloadActivity::class.java, R.string.purge_manga_downloads, R.string.downloads, R.drawable.ic_round_purge_manga_24, R.string.purge_manga_downloads_desc, keywordsRes = R.string.search_kw_purge_manga_downloads)

        l += SearchableSetting(DownloadActivity::class.java, R.string.purge_novel_downloads, R.string.downloads, R.drawable.ic_round_purge_novel_24, R.string.purge_novel_downloads_desc, keywordsRes = R.string.search_kw_purge_novel_downloads)


        // ---- Backup & sync ----
        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.backup_restore, R.string.backup_sync, R.drawable.backup_restore, R.string.backup_restore_desc, keywordsRes = R.string.search_kw_backup_restore)

        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.cloud_sync, R.string.backup_sync, R.drawable.ic_round_cloud_sync_24, R.string.cloud_sync_desc, keywordsRes = R.string.search_kw_cloud_sync, requiresOnline = true)

        // That row is titled "Set up sync" until a code has been linked, and "Sync code" after.
        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.sync_code_title, R.string.backup_sync, R.drawable.ic_round_cloud_lock_24, R.string.sync_code_desc, rowTitleRes = R.string.sync_setup_title, keywordsRes = R.string.search_kw_sync_code_title, requiresOnline = true)

        // Described by what it does rather than by the row's live subtitle ("Last synced 3 minutes
        // ago"), which tells someone searching nothing about what they'd be tapping.
        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.cloud_sync_now, R.string.backup_sync, R.drawable.ic_round_sync_24, R.string.cloud_sync_now_desc, keywordsRes = R.string.search_kw_cloud_sync_now, requiresOnline = true)

        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.sync_extensions, R.string.backup_sync, R.drawable.ic_round_extension_cloud_24, R.string.sync_extensions_desc, keywordsRes = R.string.search_kw_sync_extensions, requiresOnline = true)

        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.sync_extensions_now, R.string.backup_sync, R.drawable.ic_round_extension_sync_24, R.string.sync_extensions_now_desc, keywordsRes = R.string.search_kw_sync_extensions_now, requiresOnline = true)

        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.sync_extension_settings, R.string.backup_sync, R.drawable.ic_round_extension_settings_24, R.string.sync_extension_settings_desc, keywordsRes = R.string.search_kw_sync_extension_settings, requiresOnline = true)

        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.force_upload, R.string.backup_sync, R.drawable.ic_round_cloud_upload_24, R.string.force_upload_desc, keywordsRes = R.string.search_kw_force_upload, requiresOnline = true)

        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.force_download, R.string.backup_sync, R.drawable.ic_round_cloud_download_24, R.string.force_download_desc, keywordsRes = R.string.search_kw_force_download, requiresOnline = true)

        l += SearchableSetting(SettingsBackupSyncActivity::class.java, R.string.cloud_wipe, R.string.backup_sync, R.drawable.ic_round_delete_forever_24, R.string.cloud_wipe_desc, keywordsRes = R.string.search_kw_cloud_wipe, requiresOnline = true)


        // ---- User Interface ----
        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.immersive_mode, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsImmersive, keywordsRes = R.string.search_kw_immersive_mode)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.ui_show_system_bars, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsShowSystemBarsUI, keywordsRes = R.string.search_kw_ui_show_system_bars)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.hide_notification_dot, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsHideRedDot, keywordsRes = R.string.search_kw_hide_notification_dot)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.home_layout_show, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsHomeLayout, keywordsRes = R.string.search_kw_home_layout_show)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.home_stats_select, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsHomeStats, keywordsRes = R.string.search_kw_home_stats_select)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.small_view, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsSmallView, keywordsRes = R.string.search_kw_small_view)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.show_anime_tab, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsShowAnimeTab, keywordsRes = R.string.search_kw_show_anime_tab)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.show_manga_tab, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsShowMangaTab, keywordsRes = R.string.search_kw_show_manga_tab)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.banner_animations, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsBannerAnimation, keywordsRes = R.string.search_kw_banner_animations)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.layout_animations, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsLayoutAnimation, keywordsRes = R.string.search_kw_layout_animations)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.trending_scroller, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsTrendingScroller, keywordsRes = R.string.search_kw_trending_scroller)

        l += SearchableSetting(UserInterfaceSettingsActivity::class.java, R.string.blur_banners, R.string.ui_settings, IC_UI, anchorViewId = R.id.uiSettingsBlurBanners, keywordsRes = R.string.search_kw_blur_banners)


        // ---- Anime ----
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.player_settings, R.string.anime, R.drawable.ic_round_video_settings_24, R.string.player_settings_desc, keywordsRes = R.string.search_kw_player_settings)

        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.prefer_dub, R.string.anime, R.drawable.ic_anime_dub_24, R.string.prefer_dub_desc, keywordsRes = R.string.search_kw_prefer_dub)

        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.show_yt, R.string.anime, R.drawable.format_youtube_24, R.string.show_yt_desc, keywordsRes = R.string.search_kw_show_yt)

        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.include_list, R.string.anime, R.drawable.view_list_24, R.string.include_list_anime_desc, keywordsRes = R.string.search_kw_include_list)

        l += SearchableSetting(SettingsAnimeActivity::class.java, R.string.default_ep_view, R.string.anime, IC_ANIME, anchorViewId = R.id.settingsEpList, keywordsRes = R.string.search_kw_default_ep_view)


        // ---- Manga ----
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.reader_settings, R.string.manga, R.drawable.ic_round_reader_settings, R.string.reader_settings_desc, keywordsRes = R.string.search_kw_reader_settings)

        l += SearchableSetting(SettingsMangaActivity::class.java, R.string.include_list, R.string.manga, R.drawable.view_list_24, R.string.include_list_desc, keywordsRes = R.string.search_kw_include_list_2)

        l += SearchableSetting(SettingsMangaActivity::class.java, R.string.default_chp_view, R.string.manga, IC_MANGA, anchorViewId = R.id.settingsChpList, keywordsRes = R.string.search_kw_default_chp_view)


        // ---- Player settings (XML) ----
        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.default_playback_speed, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsSpeed, keywordsRes = R.string.search_kw_default_playback_speed)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.cursed_speeds, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsCursedSpeeds, keywordsRes = R.string.search_kw_cursed_speeds)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.resize_mode_button, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerResizeMode, keywordsRes = R.string.search_kw_resize_mode_button)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.subtitle_toggle, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.subSwitch, keywordsRes = R.string.search_kw_subtitle_toggle)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.primary_sub_color_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubColorPrimary, keywordsRes = R.string.search_kw_primary_sub_color_select)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.secondary_sub_color_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubColorSecondary, keywordsRes = R.string.search_kw_secondary_sub_color_select)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.secondary_sub_outline_type_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubOutline, keywordsRes = R.string.search_kw_secondary_sub_outline_type_select)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.sub_background_color_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubColorBackground, keywordsRes = R.string.search_kw_sub_background_color_select)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.sub_window_color_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubColorWindow, keywordsRes = R.string.search_kw_sub_window_color_select)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.sub_alpha, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubAlphaButton, keywordsRes = R.string.search_kw_sub_alpha)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.textview_sub, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.subTextSwitch, keywordsRes = R.string.search_kw_textview_sub)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.textview_sub_stroke, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubStrokeButton, keywordsRes = R.string.search_kw_textview_sub_stroke)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.textview_sub_bottom_margin, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubBottomMarginButton, keywordsRes = R.string.search_kw_textview_sub_bottom_margin)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.sub_font_select, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubFont, keywordsRes = R.string.search_kw_sub_font_select)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.subtitle_font_size, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.subtitle_font_size_text, keywordsRes = R.string.search_kw_subtitle_font_size)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.subtitle_langauge, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.videoSubLanguage, keywordsRes = R.string.search_kw_subtitle_langauge)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.always_load_time_stamps, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsTimeStamps, keywordsRes = R.string.search_kw_always_load_time_stamps)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.timestamp_proxy, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsTimeStampsProxy, keywordsRes = R.string.search_kw_timestamp_proxy)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.show_skip_time_stamp_button, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsShowTimeStamp, keywordsRes = R.string.search_kw_show_skip_time_stamp_button)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_hide_time_stamps, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsTimeStampsAutoHide, keywordsRes = R.string.search_kw_auto_hide_time_stamps)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_skip_op_ed, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAutoSkipOpEd, keywordsRes = R.string.search_kw_auto_skip_op_ed)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_skip_recap, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAutoSkipRecap, keywordsRes = R.string.search_kw_auto_skip_recap)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_play_next_episode, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAutoPlay, keywordsRes = R.string.search_kw_auto_play_next_episode)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.auto_skip_fillers, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAutoSkip, keywordsRes = R.string.search_kw_auto_skip_fillers)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.ask_update_progress_anime, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAskUpdateProgress, keywordsRes = R.string.search_kw_ask_update_progress_anime)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.ask_update_progress_chapter_zero, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAskChapterZero, keywordsRes = R.string.search_kw_ask_update_progress_chapter_zero)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.ask_update_progress_hentai, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAskUpdateHentai, keywordsRes = R.string.search_kw_ask_update_progress_hentai)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.always_continue, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAlwaysContinue, keywordsRes = R.string.search_kw_always_continue)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.pause_video_focus, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsPauseVideo, keywordsRes = R.string.search_kw_pause_video_focus)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.gestures, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsVerticalGestures, keywordsRes = R.string.search_kw_gestures)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.fast_forward, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsFastForward, keywordsRes = R.string.search_kw_fast_forward)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.double_tap, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsDoubleTap, keywordsRes = R.string.search_kw_double_tap)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.picture_in_picture, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsPiP, keywordsRes = R.string.search_kw_picture_in_picture)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.show_cast_button, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsCast, keywordsRes = R.string.search_kw_show_cast_button)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.show_rotate_button, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsRotate, keywordsRes = R.string.search_kw_show_rotate_button)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.try_internal_cast_experimental, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsInternalCast, keywordsRes = R.string.search_kw_try_internal_cast_experimental)

        l += SearchableSetting(PlayerSettingsActivity::class.java, R.string.use_additional_codec, R.string.player_settings, IC_PLAYER, anchorViewId = R.id.playerSettingsAdditionalCodec, keywordsRes = R.string.search_kw_use_additional_codec)


        // ---- Reader settings (XML) ----
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.auto_detect_webtoon, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAutoWebToon, keywordsRes = R.string.search_kw_auto_detect_webtoon)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.over_scroll, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsOverscroll, keywordsRes = R.string.search_kw_over_scroll)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.true_colors, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsTrueColors, keywordsRes = R.string.search_kw_true_colors)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.image_rotation, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsImageRotation, keywordsRes = R.string.search_kw_image_rotation)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.crop_borders, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsCropBorders, keywordsRes = R.string.search_kw_crop_borders)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.spaced_pages, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsPadding, keywordsRes = R.string.search_kw_spaced_pages)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.hide_scroll_bar, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsHideScrollBar, keywordsRes = R.string.search_kw_hide_scroll_bar)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.hide_page_numbers, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsHidePageNumbers, keywordsRes = R.string.search_kw_hide_page_numbers)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.horizontal_scroll_bar, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsHorizontalScrollBar, keywordsRes = R.string.search_kw_horizontal_scroll_bar)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.keep_screen_on, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsKeepScreenOn, keywordsRes = R.string.search_kw_keep_screen_on)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.lock_screen_rotation, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsLockRotation, keywordsRes = R.string.search_kw_lock_screen_rotation)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.autoscroll, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAutoscrollEnabled, keywordsRes = R.string.search_kw_autoscroll)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.volume_buttons, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsVolumeButton, keywordsRes = R.string.search_kw_volume_buttons)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.wrap_images, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsWrapImages, keywordsRes = R.string.search_kw_wrap_images)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.image_long_clicking, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsLongClickImage, keywordsRes = R.string.search_kw_image_long_clicking)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.preload_amount, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsPreloadAmount, keywordsRes = R.string.search_kw_preload_amount)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.layout, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsLayoutText, keywordsRes = R.string.search_kw_layout)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.direction, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsDirectionText, keywordsRes = R.string.search_kw_direction)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.dual_page, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsDualPageText, keywordsRes = R.string.search_kw_dual_page)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.source_info, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsSourceName, keywordsRes = R.string.search_kw_source_info)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.continuous_multi_chapter, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsContinuousMultiChapter, keywordsRes = R.string.search_kw_continuous_multi_chapter)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.show_system_bars, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsSystemBars, keywordsRes = R.string.search_kw_show_system_bars)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.ask_update_progress_manga, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAskUpdateProgress, keywordsRes = R.string.search_kw_ask_update_progress_manga)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.ask_update_progress_chapter_zero, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAskChapterZero, keywordsRes = R.string.search_kw_ask_update_progress_chapter_zero_2)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.ask_update_progress_doujin, R.string.reader_settings, IC_READER, anchorViewId = R.id.readerSettingsAskUpdateDoujins, keywordsRes = R.string.search_kw_ask_update_progress_doujin)

        // Novel reader sub-section
        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.use_dark_theme, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNuseDarkTheme, keywordsRes = R.string.search_kw_use_dark_theme)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.use_oled_theme, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNuseOledTheme, keywordsRes = R.string.search_kw_use_oled_theme)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.keep_screen_on, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNkeepScreenOn, keywordsRes = R.string.search_kw_keep_screen_on_2)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.volume_buttons, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNvolumeButton, keywordsRes = R.string.search_kw_volume_buttons_2)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.layout, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNlayoutText, keywordsRes = R.string.search_kw_layout_2)

        l += SearchableSetting(ReaderSettingsActivity::class.java, R.string.dual_page, R.string.reader_settings, IC_READER, anchorViewId = R.id.LNdualPageText, keywordsRes = R.string.search_kw_dual_page_2)


        // ---- Extensions ----
        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.anime_add_repository, R.string.extensions, R.drawable.ic_round_github_anime_24, R.string.anime_add_repository_desc, keywordsRes = R.string.search_kw_anime_add_repository)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.manga_add_repository, R.string.extensions, R.drawable.ic_round_github_manga_24, R.string.manga_add_repository_desc, keywordsRes = R.string.search_kw_manga_add_repository)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.novel_add_repository, R.string.extensions, R.drawable.ic_round_github_novel_24, R.string.novel_add_repository_desc, keywordsRes = R.string.search_kw_novel_add_repository)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.extension_test, R.string.extensions, R.drawable.ic_round_science_24, R.string.extension_test_desc, keywordsRes = R.string.search_kw_extension_test)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.user_agent, R.string.extensions, R.drawable.ic_globe_24, R.string.user_agent_desc, keywordsRes = R.string.search_kw_user_agent)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.proxy, R.string.extensions, R.drawable.vpn_key_24, R.string.proxy_desc, keywordsRes = R.string.search_kw_proxy)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.proxy_setup, R.string.extensions, R.drawable.lan_24, R.string.proxy_setup_desc, keywordsRes = R.string.search_kw_proxy_setup)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.force_legacy_installer, R.string.extensions, R.drawable.ic_round_history_24, R.string.force_legacy_installer_desc, keywordsRes = R.string.search_kw_force_legacy_installer)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.skip_loading_extension_icons, R.string.extensions, R.drawable.ic_round_no_icon_24, R.string.skip_loading_extension_icons_desc, keywordsRes = R.string.search_kw_skip_loading_extension_icons)

        l += SearchableSetting(SettingsExtensionsActivity::class.java, R.string.NSFWExtention, R.string.extensions, R.drawable.ic_round_nsfw_24, R.string.NSFWExtention_desc, keywordsRes = R.string.search_kw_NSFWExtention)


        // ---- Add-ons ----
        l += SearchableSetting(SettingsAddonActivity::class.java, R.string.anime_downloader_addon, R.string.addons, R.drawable.ic_round_addon_download_24, keywordsRes = R.string.search_kw_anime_downloader_addon)

        l += SearchableSetting(SettingsAddonActivity::class.java, R.string.torrent_addon, R.string.addons, R.drawable.ic_round_magnet_24, keywordsRes = R.string.search_kw_torrent_addon)

        l += SearchableSetting(SettingsAddonActivity::class.java, R.string.enable_torrent, R.string.addons, R.drawable.ic_round_dns_24, R.string.enable_torrent_desc, keywordsRes = R.string.search_kw_enable_torrent)


        // ---- Notifications ----
        l += SearchableSetting(SettingsSubscriptionNotificationActivity::class.java, R.string.subscription_notifications, R.string.notifications, R.drawable.ic_round_notif_subscriptions_24, R.string.subscription_notifications_desc, keywordsRes = R.string.search_kw_subscription_notifications)

        l += SearchableSetting(SettingsUnreadChapterNotificationActivity::class.java, R.string.unread_chapter_notifications, R.string.notifications, R.drawable.ic_round_notif_unread_24, R.string.unread_chapter_notifications_desc, keywordsRes = R.string.search_kw_unread_chapter_notifications)

        l += SearchableSetting(SettingsAnilistNotificationActivity::class.java, R.string.anilist_notifications, R.string.notifications, R.drawable.ic_round_notif_anilist_24, R.string.anilist_notifications_desc, keywordsRes = R.string.search_kw_anilist_notifications)

        l += SearchableSetting(SettingsCommentNotificationActivity::class.java, R.string.comment_notifications, R.string.notifications, R.drawable.ic_round_notif_comments_24, R.string.comment_notifications_desc, keywordsRes = R.string.search_kw_comment_notifications)

        l += SearchableSetting(SettingsMuNotificationActivity::class.java, R.string.mu_notifications, R.string.notifications, R.drawable.ic_round_notif_mangaupdates_24, R.string.mu_notifications_desc, keywordsRes = R.string.search_kw_mu_notifications)

        l += SearchableSetting(SettingsNotificationActivity::class.java, R.string.use_alarm_manager_reliable, R.string.notifications, R.drawable.ic_round_alarm_24, R.string.use_alarm_manager_reliable_desc, keywordsRes = R.string.search_kw_use_alarm_manager_reliable)

        // Notification children
        // The "how often" row of each screen. Indexed against the value-less form of its label,
        // since each of these rows renders its current setting into its own title.
        l += SearchableSetting(SettingsAnilistNotificationActivity::class.java, R.string.anilist_notifications_checking_time_label, R.string.anilist_notifications, R.drawable.ic_round_notif_anilist_24, R.string.anilist_notifications_checking_time_desc, keywordsRes = R.string.search_kw_anilist_notifications_checking_time_label)

        l += SearchableSetting(SettingsCommentNotificationActivity::class.java, R.string.comment_notification_checking_time_label, R.string.comment_notifications, R.drawable.ic_round_notif_comments_24, R.string.comment_notification_checking_time_desc, keywordsRes = R.string.search_kw_comment_notification_checking_time_label)

        l += SearchableSetting(SettingsSubscriptionNotificationActivity::class.java, R.string.subscriptions_checking_time, R.string.subscription_notifications, R.drawable.ic_round_notif_subscriptions_24, keywordsRes = R.string.search_kw_subscriptions_checking_time)

        l += SearchableSetting(SettingsUnreadChapterNotificationActivity::class.java, R.string.unread_chapter_notification_checking_time_label, R.string.unread_chapter_notifications, R.drawable.ic_round_notif_unread_24, R.string.unread_chapter_notification_checking_time_desc, keywordsRes = R.string.search_kw_unread_chapter_notification_checking_time_label)

        l += SearchableSetting(SettingsMuNotificationActivity::class.java, R.string.mu_notification_interval_label, R.string.mu_notifications, R.drawable.ic_round_notif_mangaupdates_24, R.string.mu_notification_interval_desc, keywordsRes = R.string.search_kw_mu_notification_interval_label)

        l += SearchableSetting(SettingsAnilistNotificationActivity::class.java, R.string.anilist_notification_filters, R.string.anilist_notifications, R.drawable.ic_anilist, R.string.anilist_notification_filters_desc, keywordsRes = R.string.search_kw_anilist_notification_filters)

        l += SearchableSetting(SettingsSubscriptionNotificationActivity::class.java, R.string.notification_for_checking_subscriptions, R.string.subscription_notifications, R.drawable.ic_round_notif_progress_24, R.string.notification_for_checking_subscriptions_desc, keywordsRes = R.string.search_kw_notification_for_checking_subscriptions)

        l += SearchableSetting(SettingsSubscriptionNotificationActivity::class.java, R.string.view_subscriptions, R.string.subscription_notifications, R.drawable.ic_round_subscriptions_24, R.string.view_subscriptions_desc, keywordsRes = R.string.search_kw_view_subscriptions)

        l += SearchableSetting(SettingsUnreadChapterNotificationActivity::class.java, R.string.unread_chapter_check_progress_notification, R.string.unread_chapter_notifications, R.drawable.ic_round_notif_progress_24, R.string.unread_chapter_check_progress_notification_desc, keywordsRes = R.string.search_kw_unread_chapter_check_progress_notification)

        l += SearchableSetting(SettingsUnreadChapterNotificationActivity::class.java, R.string.clear_unread_chapter_history, R.string.unread_chapter_notifications, R.drawable.ic_round_delete_sweep_24, R.string.clear_unread_chapter_history_desc, keywordsRes = R.string.search_kw_clear_unread_chapter_history)

        l += SearchableSetting(SettingsMuNotificationActivity::class.java, R.string.mu_notifications_enabled, R.string.mu_notifications, R.drawable.ic_round_mangaupdates_24, R.string.mu_notifications_enabled_desc, keywordsRes = R.string.search_kw_mu_notifications_enabled)


        // ---- About ----
        l += SearchableSetting(FAQActivity::class.java, R.string.faq, R.string.about, R.drawable.ic_round_quiz_24, R.string.faq_desc, keywordsRes = R.string.search_kw_faq)

        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.check_app_updates, R.string.about, R.drawable.ic_round_new_releases_24, R.string.check_app_updates_desc, keywordsRes = R.string.search_kw_check_app_updates)

        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.share_username_in_crash_reports, R.string.about, R.drawable.ic_round_badge_24, R.string.share_username_in_crash_reports_desc, keywordsRes = R.string.search_kw_share_username_in_crash_reports)

        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.disable_crash_reports, R.string.about, R.drawable.ic_round_bug_report_24, R.string.disable_crash_reports_desc, keywordsRes = R.string.search_kw_disable_crash_reports)

        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.log_to_file, R.string.about, R.drawable.ic_round_description_24, R.string.logging_warning, keywordsRes = R.string.search_kw_log_to_file)

        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.devs, R.string.about, R.drawable.ic_round_group_24, R.string.devs_desc, keywordsRes = R.string.search_kw_devs)

        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.disclaimer, R.string.about, R.drawable.ic_round_gavel_24, R.string.disclaimer_desc, keywordsRes = R.string.search_kw_disclaimer)

        l += SearchableSetting(SettingsAboutActivity::class.java, R.string.privacy_policy, R.string.about, R.drawable.ic_shield, R.string.privacy_policy_desc, keywordsRes = R.string.search_kw_privacy_policy)


        return l
    }

    /**
     * Lowercased and stripped of accents, so a query typed without them still matches.
     *
     * Both sides go through this: "frequence" has to reach "fréquence", and a label is no more
     * likely to be typed with its diacritics than without. Matching on the raw strings meant a
     * French user could see a setting on screen and fail to find it by name.
     */
    private fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase()

    /** Returns the settings matching [raw], ranked with the closest title matches first. */
    fun query(context: Context, raw: String): List<SearchableSetting> {
        val q = fold(raw.trim())
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(" ").filter { it.isNotBlank() }
        // Offline, the entire Accounts section (login/connections/list-sync) is non-functional,
        // so exclude it from search too — otherwise it'd be reachable despite being hidden from
        // the top-level list.
        val offline = !ani.dantotsu.isOnline(context) ||
                ani.dantotsu.settings.saving.PrefManager.getVal<Boolean>(ani.dantotsu.settings.saving.PrefName.OfflineMode)
        return index.mapNotNull { e ->
            if (offline && (e.sectionRes == R.string.accounts || e.requiresOnline)) return@mapNotNull null
            val title = fold(context.getString(e.titleRes))
            val desc = if (e.descRes != 0) fold(context.getString(e.descRes)) else ""
            val section = fold(context.getString(e.sectionRes))
            val keywords = if (e.keywordsRes != 0) fold(context.getString(e.keywordsRes)) else ""
            val haystack = "$title $desc $section $keywords"
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
    const val EXTRA_ANCHOR_TITLE_ALT = "ani.dantotsu.settings.ANCHOR_TITLE_ALT"

    fun open(context: Context, setting: SearchableSetting) {
        val intent = Intent(context, setting.dest)
        if (setting.anchorViewId != 0) {
            intent.putExtra(EXTRA_ANCHOR_VIEW, setting.anchorViewId)
        } else {
            intent.putExtra(EXTRA_ANCHOR_TITLE, setting.titleRes)
            if (setting.rowTitleRes != 0) {
                intent.putExtra(EXTRA_ANCHOR_TITLE_ALT, setting.rowTitleRes)
            }
        }
        if (setting.intentTab >= 0) intent.putExtra("tab", setting.intentTab)
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
        val altTitleRes = activity.intent.getIntExtra(EXTRA_ANCHOR_TITLE_ALT, 0)
        when {
            viewId != 0 -> {
                val target = activity.findViewById<View>(viewId) ?: return
                expandSections(target)
                target.doOnPreDraw { scrollToAndFlash(target) }
            }

            titleRes != 0 && recyclers.isNotEmpty() -> {
                // Both candidates are tried because only one can be right and which one depends on
                // the screen's state. A title that matches nothing costs nothing: the lookup simply
                // finds no row and stops.
                val titles = listOfNotNull(titleRes, altTitleRes.takeIf { it != 0 })
                    .map { activity.getString(it) }
                recyclers.forEach { recycler ->
                    titles.forEach { title -> scheduleListHighlight(recycler, title, attempts = 12) }
                }
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
