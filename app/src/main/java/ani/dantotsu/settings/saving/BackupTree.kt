package ani.dantotsu.settings.saving

import ani.dantotsu.R
import ani.dantotsu.settings.saving.internal.Location

/**
 * Something a backup can carry that isn't a [PrefName] — it lives outside the preference files and
 * so can't be named by one, but still deserves a row the user can untick.
 */
enum class BackupSection { ExtensionSettings }

data class BackupItem(
    val pref: PrefName? = null,
    val titleRes: Int? = null,
    val section: BackupSection? = null,
) {
    /** Stable identity for selection, whichever kind of thing this row stands for. */
    val id: String get() = pref?.name ?: section!!.name
}

data class BackupSubCategory(
    val id: String,
    val titleRes: Int,
    val descRes: Int? = null,
    val items: List<BackupItem>,
)

data class BackupCategory(
    val id: String,
    val titleRes: Int,
    val descRes: Int? = null,
    val containsProtected: Boolean = false,
    val subCategories: List<BackupSubCategory>,
)

object BackupTree {

    val categories: List<BackupCategory> = listOf(
        BackupCategory(
            id = "general",
            titleRes = R.string.backup_cat_general,
            descRes = R.string.backup_cat_general_desc,
            subCategories = listOf(
                BackupSubCategory(
                    "general_app", R.string.backup_sub_app_behavior,
                    R.string.backup_sub_app_behavior_desc,
                    listOf(
                        BackupItem(PrefName.AppLanguage, R.string.language_setting),
                        BackupItem(PrefName.ContinueMedia, R.string.always_continue_content),
                        BackupItem(PrefName.SearchSources, R.string.search_source_list),
                        BackupItem(PrefName.RecentlyListOnly, R.string.recentlyListOnly),
                        BackupItem(PrefName.AdultOnly, R.string.adult_only_content),
                        BackupItem(PrefName.NSFWExtension, R.string.NSFWExtention_desc),
                        BackupItem(PrefName.IncludeAnimeList, R.string.backup_include_anime_list),
                        BackupItem(PrefName.IncludeMangaList, R.string.backup_include_manga_list),
                        BackupItem(PrefName.AniMangaSearchDirect, R.string.open_animanga_directly),
                        BackupItem(PrefName.HidePrivate, R.string.hide_private),
                        BackupItem(PrefName.SettingsPreferDub, R.string.prefer_dub),
                        BackupItem(PrefName.SharedUserID, R.string.backup_shared_user_id),
                        BackupItem(PrefName.CommentsEnabled, R.string.comments_button),
                    )
                ),
                BackupSubCategory(
                    "general_capture", R.string.backup_sub_capture,
                    R.string.backup_sub_capture_desc,
                    listOf(
                        // The card toggles are shared by the screenshot and clip composers.
                        BackupItem(PrefName.ScreenshotShowMediaInfo, R.string.screenshot_show_media_info),
                        BackupItem(PrefName.ScreenshotShowDate, R.string.screenshot_show_date),
                        BackupItem(PrefName.ScreenshotShowSource, R.string.screenshot_show_source),
                        BackupItem(PrefName.ScreenshotShowUserInfo, R.string.screenshot_show_user_info),
                        BackupItem(PrefName.ScreenshotShowAppLogo, R.string.screenshot_show_app_icon),
                        BackupItem(PrefName.ScreenshotShowFrame, R.string.screenshot_show_frame),
                        BackupItem(PrefName.ScreenshotShowRoundedCorners, R.string.screenshot_show_rounded_corners),
                        BackupItem(PrefName.ClipDurationSeconds, R.string.clip_duration),
                        BackupItem(PrefName.ClipBurnSubtitles, R.string.clip_burn_subtitles),
                        BackupItem(PrefName.ClipExportAsGif, R.string.clip_default_gif),
                        BackupItem(PrefName.ClipGifFps, R.string.clip_gif_fps),
                        BackupItem(PrefName.ClipGifWidth, R.string.clip_gif_width),
                    )
                ),
                BackupSubCategory(
                    "general_downloads", R.string.backup_sub_downloads,
                    R.string.backup_sub_downloads_desc,
                    listOf(
                        BackupItem(PrefName.DownloadManager, R.string.download_manager_select),
                        BackupItem(PrefName.AllowMeteredDownloads, R.string.allow_metered_downloads),
                        BackupItem(PrefName.OfflineView, R.string.offline_mode),
                    )
                ),
                BackupSubCategory(
                    "general_notif", R.string.backup_sub_notifications,
                    R.string.backup_sub_notifications_desc,
                    listOf(
                        BackupItem(PrefName.AnilistNotificationInterval, R.string.anilist_notification_frequency),
                        BackupItem(PrefName.CommentNotificationInterval, R.string.comment_notification_frequency),
                        BackupItem(PrefName.SubscriptionNotificationInterval, R.string.backup_subscription_interval_hours),
                        BackupItem(PrefName.SubscriptionNotificationIntervalMinutes, R.string.backup_subscription_interval_minutes),
                        BackupItem(PrefName.UnreadChapterNotificationInterval, R.string.unread_chapter_notification_frequency),
                        BackupItem(PrefName.SubscriptionCheckingNotifications, R.string.checking_subscriptions),
                        BackupItem(PrefName.UnreadChapterCheckingNotifications, R.string.unread_chapter_notifications),
                        BackupItem(PrefName.UnreadMangaNotificationsEnabled, R.string.unread_manga_notifications),
                        BackupItem(PrefName.UnreadEpisodeNotificationsEnabled, R.string.unread_episode_notifications),
                        BackupItem(PrefName.AnilistFilteredTypes, R.string.anilist_notification_filters),
                        BackupItem(PrefName.UseAlarmManager, R.string.use_alarm_manager_reliable),
                    )
                ),
                BackupSubCategory(
                    "general_network", R.string.backup_sub_network,
                    R.string.backup_sub_network_desc,
                    listOf(
                        BackupItem(PrefName.DohProvider, R.string.selected_dns),
                        BackupItem(PrefName.DefaultUserAgent, R.string.user_agent),
                        BackupItem(PrefName.EnableSocks5Proxy, R.string.proxy),
                        BackupItem(PrefName.ProxyAuthEnabled, R.string.authentication),
                    )
                ),
                BackupSubCategory(
                    "general_updates", R.string.backup_sub_updates,
                    R.string.backup_sub_updates_desc,
                    listOf(
                        BackupItem(PrefName.CheckUpdate, R.string.check_app_updates),
                        BackupItem(PrefName.VerboseLogging, R.string.log_to_file),
                        BackupItem(PrefName.DisableCrashReports, R.string.disable_crash_reports),
                    )
                ),
                BackupSubCategory(
                    "general_connections", R.string.backup_sub_connections,
                    R.string.backup_sub_connections_desc,
                    listOf(
                        BackupItem(PrefName.ComickEnabled, R.string.disable_comick),
                        BackupItem(PrefName.MalEnabled, R.string.disable_mal),
                        BackupItem(PrefName.MangaUpdatesEnabled, R.string.disable_mangaupdates),
                        BackupItem(PrefName.MangaUpdatesListEnabled, R.string.mu_list_fetch_enabled),
                        BackupItem(PrefName.MangaBakaListSyncEnabled, R.string.mangabaka_list_sync),
                        BackupItem(PrefName.MangaBakaTagWeightFilter, R.string.mangabaka_tag_weight),
                        BackupItem(PrefName.MalListSyncEnabled, R.string.mal_list_sync),
                        BackupItem(PrefName.KitsuListSyncEnabled, R.string.kitsu_list_sync),
                        BackupItem(PrefName.SimklListSyncEnabled, R.string.simkl_list_sync),
                        BackupItem(PrefName.AutoListSyncInterval, R.string.auto_list_sync_frequency),
                        BackupItem(PrefName.AutoListSyncRemovals, R.string.auto_list_sync_removals_label),
                        BackupItem(PrefName.MalSyncInfoEnabled, R.string.disable_malsync),
                        BackupItem(PrefName.MalSyncCheckMode, R.string.malsync_checks_dialog_title),
                        BackupItem(PrefName.UnreadChaptersSort, R.string.unread_sort_label),
                        BackupItem(PrefName.MuCustomListMapping, R.string.mu_custom_list_mapping),
                        BackupItem(PrefName.MuCustomListTitles, R.string.backup_mu_custom_list_titles),
                    )
                ),
                BackupSubCategory(
                    "general_sources", R.string.backup_sub_sources,
                    R.string.backup_sub_sources_desc,
                    listOf(
                        BackupItem(PrefName.AnimeExtensionRepos, R.string.backup_anime_extension_repos),
                        BackupItem(PrefName.MangaExtensionRepos, R.string.backup_manga_extension_repos),
                        BackupItem(PrefName.NovelExtensionRepos, R.string.backup_novel_extension_repos),
                        // The plugin records, not the bundles: those are downloaded JavaScript
                        // files, and a restore fetches them again from the URL each record keeps.
                        BackupItem(PrefName.LNReaderRepos, R.string.backup_lnreader_repos),
                        BackupItem(PrefName.LNReaderInstalled, R.string.backup_lnreader_installed),
                        BackupItem(PrefName.AnimeSourcesOrder, R.string.backup_anime_sources_order),
                        BackupItem(PrefName.MangaSourcesOrder, R.string.backup_manga_sources_order),
                        BackupItem(PrefName.NovelSourcesOrder, R.string.backup_novel_sources_order),
                    )
                ),
                BackupSubCategory(
                    "general_history", R.string.backup_sub_search_history,
                    R.string.backup_sub_search_history_desc,
                    listOf(
                        BackupItem(PrefName.SortedAnimeSH, R.string.backup_anime_search_history),
                        BackupItem(PrefName.SortedMangaSH, R.string.backup_manga_search_history),
                        BackupItem(PrefName.SortedCharacterSH, R.string.backup_character_search_history),
                        BackupItem(PrefName.SortedStaffSH, R.string.backup_staff_search_history),
                        BackupItem(PrefName.SortedStudioSH, R.string.backup_studio_search_history),
                        BackupItem(PrefName.SortedUserSH, R.string.backup_user_search_history),
                    )
                ),
                BackupSubCategory(
                    "general_filters", R.string.backup_sub_saved_filters,
                    R.string.backup_sub_saved_filters_desc,
                    listOf(
                        BackupItem(PrefName.SavedAniMangaFilters, R.string.backup_saved_animanga_filters),
                        BackupItem(PrefName.SavedMUFilters, R.string.backup_saved_mu_filters),
                        BackupItem(PrefName.SavedComickFilters, R.string.backup_saved_comick_filters),
                        BackupItem(PrefName.SavedListFilters, R.string.backup_saved_list_filters),
                        BackupItem(PrefName.SavedExtensionFilters, R.string.backup_saved_extension_filters),
                    )
                ),
            )
        ),
        BackupCategory(
            id = "ui",
            titleRes = R.string.backup_cat_ui,
            descRes = R.string.backup_cat_ui_desc,
            subCategories = listOf(
                BackupSubCategory(
                    "ui_theme", R.string.backup_sub_theme,
                    R.string.backup_sub_theme_desc,
                    listOf(
                        BackupItem(PrefName.UseOLED, R.string.oled_theme_variant),
                        BackupItem(PrefName.UseCustomTheme, R.string.use_custom_theme),
                        BackupItem(PrefName.CustomThemeInt, R.string.custom_theme),
                        BackupItem(PrefName.UseSourceTheme, R.string.use_unique_theme_for_each_item),
                        BackupItem(PrefName.UseMaterialYou, R.string.use_material_you),
                        BackupItem(PrefName.Theme, R.string.theme_),
                        BackupItem(PrefName.DarkMode, R.string.use_dark_theme),
                        BackupItem(PrefName.SkipExtensionIcons, R.string.skip_loading_extension_icons),
                    )
                ),
                BackupSubCategory(
                    "ui_layout", R.string.backup_sub_layout,
                    R.string.backup_sub_layout_desc,
                    listOf(
                        BackupItem(PrefName.DefaultStartUpTab, R.string.startUpTab),
                        BackupItem(PrefName.HomeLayout, R.string.home_layout_show),
                        BackupItem(PrefName.HomeLayoutOrder, R.string.home_layout_show),
                        BackupItem(PrefName.QuickTileOrder, R.string.backup_quick_tiles),
                        BackupItem(PrefName.SearchTileOrder, R.string.backup_search_tiles),
                        BackupItem(PrefName.ShowAnimeTab, R.string.show_anime_tab),
                        BackupItem(PrefName.ShowMangaTab, R.string.show_manga_tab),
                        BackupItem(PrefName.HomeStat1, R.string.home_stats_stat1),
                        BackupItem(PrefName.HomeStat2, R.string.home_stats_stat2),
                        BackupItem(PrefName.FollowerLayout, R.string.backup_follower_layout),
                    )
                ),
                BackupSubCategory(
                    "ui_anim", R.string.backup_sub_animations,
                    R.string.backup_sub_animations_desc,
                    listOf(
                        BackupItem(PrefName.BannerAnimations, R.string.banner_animations),
                        BackupItem(PrefName.LayoutAnimations, R.string.layout_animations),
                        BackupItem(PrefName.TrendingScroller, R.string.trending_scroller),
                        BackupItem(PrefName.AnimationSpeed, R.string.animation_speed),
                    )
                ),
                BackupSubCategory(
                    "ui_blur", R.string.backup_sub_blur,
                    R.string.backup_sub_blur_desc,
                    listOf(
                        BackupItem(PrefName.BlurBanners, R.string.blur_banners),
                        BackupItem(PrefName.BlurRadius, R.string.blur_radius),
                        BackupItem(PrefName.BlurSampling, R.string.blur_sampling),
                    )
                ),
                BackupSubCategory(
                    "ui_lists", R.string.backup_sub_list_display,
                    R.string.backup_sub_list_display_desc,
                    listOf(
                        BackupItem(PrefName.AnimeDefaultView, R.string.default_ep_view),
                        BackupItem(PrefName.MangaDefaultView, R.string.default_chp_view),
                        BackupItem(PrefName.ListGrid, R.string.list_settings),
                        BackupItem(PrefName.PopularMangaList, R.string.backup_popular_manga_list),
                        BackupItem(PrefName.PopularAnimeList, R.string.backup_popular_anime_list),
                        BackupItem(PrefName.AnimeListSortOrder, R.string.backup_anime_list_sort),
                        BackupItem(PrefName.MangaListSortOrder, R.string.backup_manga_list_sort),
                        BackupItem(PrefName.CommentSortOrder, R.string.backup_comment_sort_order),
                        BackupItem(PrefName.SmallView, R.string.small_view),
                    )
                ),
                BackupSubCategory(
                    "ui_misc", R.string.backup_sub_ui_misc,
                    R.string.backup_sub_ui_misc_desc,
                    listOf(
                        BackupItem(PrefName.ShowYtButton, R.string.show_yt),
                        BackupItem(PrefName.ImmersiveMode, R.string.immersive_mode),
                        BackupItem(PrefName.ShowSystemBarsUI, R.string.ui_show_system_bars),
                        BackupItem(PrefName.ShowNotificationRedDot, R.string.hide_notification_dot),
                    )
                ),
            )
        ),
        BackupCategory(
            id = "player",
            titleRes = R.string.backup_cat_player,
            descRes = R.string.backup_cat_player_desc,
            subCategories = listOf(
                BackupSubCategory(
                    "player_playback", R.string.backup_sub_playback,
                    R.string.backup_sub_playback_desc,
                    listOf(
                        BackupItem(PrefName.DefaultSpeed, R.string.default_speed),
                        BackupItem(PrefName.CursedSpeeds, R.string.cursed_speeds),
                        BackupItem(PrefName.Resize, R.string.resize_mode_button),
                        BackupItem(PrefName.AutoPlay, R.string.auto_play_next_episode),
                        BackupItem(PrefName.AlwaysContinue, R.string.always_continue),
                        BackupItem(PrefName.FocusPause, R.string.pause_video_focus),
                        BackupItem(PrefName.WatchPercentage, R.string.watch_complete_percentage),
                        BackupItem(PrefName.AskIndividualPlayer, R.string.ask_update_progress_anime),
                        BackupItem(PrefName.ChapterZeroPlayer, R.string.ask_update_progress_episode_zero),
                        BackupItem(PrefName.UpdateForHPlayer, R.string.ask_update_progress_hentai_anime),
                    )
                ),
                BackupSubCategory(
                    "player_subs", R.string.backup_sub_subtitles,
                    R.string.backup_sub_subtitles_desc,
                    listOf(
                        BackupItem(PrefName.Subtitles, R.string.subtitle_toggle),
                        BackupItem(PrefName.TextviewSubtitles, R.string.textview_sub),
                        BackupItem(PrefName.SubLanguage, R.string.subtitle_langauge),
                        BackupItem(PrefName.PrimaryColor, R.string.primary_sub_color_select),
                        BackupItem(PrefName.SecondaryColor, R.string.secondary_sub_color_select),
                        BackupItem(PrefName.Outline, R.string.secondary_sub_outline_type_select),
                        BackupItem(PrefName.SubBackground, R.string.sub_background_color_select),
                        BackupItem(PrefName.SubWindow, R.string.sub_window_color_select),
                        BackupItem(PrefName.SubAlpha, R.string.sub_alpha),
                        BackupItem(PrefName.SubStroke, R.string.textview_sub_stroke),
                        BackupItem(PrefName.SubBottomMargin, R.string.textview_sub_bottom_margin),
                        BackupItem(PrefName.Font, R.string.sub_font_select),
                        BackupItem(PrefName.FontSize, R.string.subtitle_font_size),
                        BackupItem(PrefName.Locale, R.string.backup_audio_language),
                    )
                ),
                BackupSubCategory(
                    "player_skip", R.string.backup_sub_skip,
                    R.string.backup_sub_skip_desc,
                    listOf(
                        BackupItem(PrefName.TimeStampsEnabled, R.string.timestamps),
                        BackupItem(PrefName.AutoHideTimeStamps, R.string.auto_hide_time_stamps),
                        BackupItem(PrefName.UseProxyForTimeStamps, R.string.timestamp_proxy),
                        BackupItem(PrefName.ShowTimeStampButton, R.string.show_skip_time_stamp_button),
                        BackupItem(PrefName.AutoSkipOPED, R.string.auto_skip_op_ed),
                        BackupItem(PrefName.AutoSkipRecap, R.string.auto_skip_recap),
                        BackupItem(PrefName.AutoSkipFiller, R.string.auto_skip_fillers),
                        BackupItem(PrefName.SkipTime, R.string.skip_time),
                        BackupItem(PrefName.SeekTime, R.string.seek_time),
                    )
                ),
                BackupSubCategory(
                    "player_gestures", R.string.backup_sub_gestures,
                    R.string.backup_sub_gestures_desc,
                    listOf(
                        BackupItem(PrefName.Gestures, R.string.gestures),
                        BackupItem(PrefName.DoubleTap, R.string.double_tap),
                        BackupItem(PrefName.FastForward, R.string.fast_forward),
                    )
                ),
                BackupSubCategory(
                    "player_advanced", R.string.backup_sub_cast,
                    R.string.backup_sub_cast_desc,
                    listOf(
                        BackupItem(PrefName.Cast, R.string.show_cast_button),
                        BackupItem(PrefName.UseInternalCast, R.string.try_internal_cast_experimental),
                        BackupItem(PrefName.Pip, R.string.picture_in_picture),
                        BackupItem(PrefName.RotationPlayer, R.string.lock_screen_rotation),
                        BackupItem(PrefName.TorrentEnabled, R.string.enable_torrent),
                        BackupItem(PrefName.UseAdditionalCodec, R.string.use_additional_codec),
                    )
                ),
            )
        ),
        BackupCategory(
            id = "reader",
            titleRes = R.string.backup_cat_reader,
            descRes = R.string.backup_cat_reader_desc,
            subCategories = listOf(
                BackupSubCategory(
                    "reader_display", R.string.backup_sub_reader_display,
                    R.string.backup_sub_reader_display_desc,
                    listOf(
                        BackupItem(PrefName.Direction, R.string.direction),
                        BackupItem(PrefName.LayoutReader, R.string.layout),
                        BackupItem(PrefName.DualPageModeReader, R.string.dual_page),
                        BackupItem(PrefName.OverScrollMode, R.string.over_scroll),
                        BackupItem(PrefName.TrueColors, R.string.true_colors),
                        BackupItem(PrefName.Rotation, R.string.image_rotation),
                        BackupItem(PrefName.Padding, R.string.backup_page_padding),
                        BackupItem(PrefName.WrapImages, R.string.wrap_images),
                    )
                ),
                BackupSubCategory(
                    "reader_ui", R.string.backup_sub_reader_ui,
                    R.string.backup_sub_reader_ui_desc,
                    listOf(
                        BackupItem(PrefName.ShowSource, R.string.source_info),
                        BackupItem(PrefName.ShowSystemBars, R.string.show_system_bars),
                        BackupItem(PrefName.HideScrollBar, R.string.hide_scroll_bar),
                        BackupItem(PrefName.HidePageNumbers, R.string.hide_page_numbers),
                        BackupItem(PrefName.HorizontalScrollBar, R.string.horizontal_scroll_bar),
                        BackupItem(PrefName.KeepScreenOn, R.string.keep_screen_on),
                    )
                ),
                BackupSubCategory(
                    "reader_behavior", R.string.backup_sub_reader_behavior,
                    R.string.backup_sub_reader_behavior_desc,
                    listOf(
                        BackupItem(PrefName.AskIndividualReader, R.string.ask_update_progress_manga),
                        BackupItem(PrefName.ChapterZeroReader, R.string.ask_update_progress_chapter_zero),
                        BackupItem(PrefName.UpdateForHReader, R.string.ask_update_progress_hentai_manga),
                        BackupItem(PrefName.AutoDetectWebtoon, R.string.auto_detect_webtoon),
                        BackupItem(PrefName.VolumeButtonsReader, R.string.volume_buttons),
                        BackupItem(PrefName.LongClickImage, R.string.image_long_clicking),
                        BackupItem(PrefName.ContinuousMultiChapter, R.string.continuous_multi_chapter),
                        BackupItem(PrefName.PreloadAmount, R.string.preload_amount),
                    )
                ),
                BackupSubCategory(
                    "reader_autoscroll", R.string.backup_sub_autoscroll,
                    R.string.backup_sub_autoscroll_desc,
                    listOf(
                        BackupItem(PrefName.AutoScrollEnabled, R.string.autoscroll),
                        BackupItem(PrefName.AutoScrollSpeed, R.string.autoscroll_speed),
                    )
                ),
                BackupSubCategory(
                    "reader_crop", R.string.backup_sub_crop,
                    R.string.backup_sub_crop_desc,
                    listOf(
                        BackupItem(PrefName.CropBorders, R.string.crop_borders),
                        BackupItem(PrefName.CropBorderThreshold, R.string.backup_crop_sensitivity),
                    )
                ),
            )
        ),
        BackupCategory(
            id = "novel",
            titleRes = R.string.backup_cat_novel,
            descRes = R.string.backup_cat_novel_desc,
            subCategories = listOf(
                BackupSubCategory(
                    "novel_display", R.string.backup_sub_novel_display,
                    R.string.backup_sub_novel_display_desc,
                    listOf(
                        BackupItem(PrefName.CurrentThemeName, R.string.backup_reader_theme),
                        BackupItem(PrefName.LayoutNovel, R.string.layout),
                        BackupItem(PrefName.DualPageModeNovel, R.string.dual_page),
                        BackupItem(PrefName.UseDarkThemeNovel, R.string.use_dark_theme),
                        BackupItem(PrefName.UseOledThemeNovel, R.string.use_oled_theme),
                        BackupItem(PrefName.Invert, R.string.backup_invert_colors),
                    )
                ),
                BackupSubCategory(
                    "novel_text", R.string.backup_sub_novel_text,
                    R.string.backup_sub_novel_text_desc,
                    listOf(
                        BackupItem(PrefName.LineHeight, R.string.backup_line_height),
                        BackupItem(PrefName.Margin, R.string.backup_page_margin),
                        BackupItem(PrefName.Justify, R.string.backup_justify_text),
                        BackupItem(PrefName.Hyphenation, R.string.backup_hyphenation),
                        BackupItem(PrefName.MaxInlineSize, R.string.backup_max_inline_size),
                        BackupItem(PrefName.MaxBlockSize, R.string.backup_max_block_size),
                    )
                ),
                BackupSubCategory(
                    "novel_ui", R.string.backup_sub_novel_ui,
                    R.string.backup_sub_novel_ui_desc,
                    listOf(
                        BackupItem(PrefName.HorizontalScrollBarNovel, R.string.backup_horizontal_scrollbar_novel),
                        BackupItem(PrefName.KeepScreenOnNovel, R.string.keep_screen_on),
                        BackupItem(PrefName.VolumeButtonsNovel, R.string.volume_buttons),
                        BackupItem(PrefName.LockRotationNovel, R.string.lock_screen_rotation),
                        BackupItem(PrefName.HidePageNumbersNovel, R.string.hide_page_numbers),
                    )
                ),
                BackupSubCategory(
                    "novel_tts", R.string.novel_tts,
                    R.string.novel_tts_desc,
                    listOf(
                        BackupItem(PrefName.NovelTtsEngine, R.string.novel_tts_engine),
                        BackupItem(PrefName.NovelTtsVoice, R.string.novel_tts_voice),
                        BackupItem(PrefName.NovelTtsSpeed, R.string.speed),
                        BackupItem(PrefName.NovelTtsPitch, R.string.novel_tts_pitch),
                        BackupItem(PrefName.NovelTtsAutoNextChapter, R.string.novel_tts_auto_next_chapter),
                        BackupItem(PrefName.NovelTtsFollowText, R.string.novel_tts_follow_text),
                    )
                ),
                BackupSubCategory(
                    "novel_downloads", R.string.backup_sub_novel_downloads,
                    R.string.backup_sub_novel_downloads_desc,
                    listOf(
                        BackupItem(PrefName.NovelDownloadEpub, R.string.download_as_epub),
                        BackupItem(PrefName.NovelDownloadOneFile, R.string.one_file_download_novel),
                    )
                ),
            )
        ),
        BackupCategory(
            id = "accounts",
            titleRes = R.string.backup_cat_accounts,
            descRes = R.string.backup_cat_accounts_desc,
            containsProtected = true,
            subCategories = listOf(
                BackupSubCategory(
                    "accounts_anilist", R.string.backup_sub_anilist,
                    R.string.backup_sub_anilist_desc,
                    listOf(
                        BackupItem(PrefName.AnilistToken, R.string.backup_anilist_token),
                        BackupItem(PrefName.AnilistUserName, R.string.backup_anilist_username),
                        BackupItem(PrefName.AnilistUserId, R.string.backup_anilist_user_id),
                    )
                ),
                BackupSubCategory(
                    "accounts_mal", R.string.backup_sub_mal,
                    R.string.backup_sub_mal_desc,
                    listOf(
                        BackupItem(PrefName.MALUserName, R.string.backup_mal_username),
                        BackupItem(PrefName.MALCodeChallenge, R.string.backup_mal_code_challenge),
                        BackupItem(PrefName.MALToken, R.string.backup_mal_token),
                    )
                ),
                BackupSubCategory(
                    "accounts_mu", R.string.backup_sub_mu,
                    R.string.backup_sub_mu_desc,
                    listOf(
                        BackupItem(PrefName.MangaUpdatesUsername, R.string.backup_mu_username),
                        BackupItem(PrefName.MangaUpdatesPassword, R.string.backup_mu_password),
                        BackupItem(PrefName.MangaUpdatesToken, R.string.backup_mu_token),
                    )
                ),
                BackupSubCategory(
                    "accounts_mangabaka", R.string.backup_sub_mangabaka,
                    R.string.backup_sub_mangabaka_desc,
                    listOf(
                        BackupItem(PrefName.MangaBakaToken, R.string.backup_mangabaka_token),
                        BackupItem(PrefName.MangaBakaOAuthToken, R.string.backup_mangabaka_token),
                        BackupItem(PrefName.MangaBakaUserName, R.string.backup_mangabaka_username),
                        BackupItem(PrefName.MangaBakaUserId, R.string.backup_mangabaka_user_id),
                    )
                ),
                BackupSubCategory(
                    "accounts_kitsu", R.string.backup_sub_kitsu,
                    R.string.backup_sub_kitsu_desc,
                    listOf(
                        BackupItem(PrefName.KitsuToken, R.string.backup_kitsu_token),
                        BackupItem(PrefName.KitsuUserName, R.string.backup_kitsu_username),
                        BackupItem(PrefName.KitsuUserId, R.string.backup_kitsu_user_id),
                        BackupItem(PrefName.KitsuSlug, R.string.backup_kitsu_slug),
                        BackupItem(PrefName.KitsuAvatar, R.string.backup_kitsu_avatar),
                    )
                ),
                BackupSubCategory(
                    "accounts_simkl", R.string.backup_sub_simkl,
                    R.string.backup_sub_simkl_desc,
                    listOf(
                        BackupItem(PrefName.SimklToken, R.string.backup_simkl_token),
                        BackupItem(PrefName.SimklUserName, R.string.backup_simkl_username),
                        BackupItem(PrefName.SimklUserId, R.string.backup_simkl_user_id),
                        BackupItem(PrefName.SimklAvatar, R.string.backup_simkl_avatar),
                    )
                ),
                BackupSubCategory(
                    "accounts_discord", R.string.backup_sub_discord,
                    R.string.backup_sub_discord_desc,
                    listOf(
                        BackupItem(PrefName.DiscordToken, R.string.backup_discord_token),
                        BackupItem(PrefName.DiscordId, R.string.backup_discord_id),
                        BackupItem(PrefName.DiscordUserName, R.string.backup_discord_username),
                        BackupItem(PrefName.DiscordAvatar, R.string.backup_discord_avatar),
                        BackupItem(PrefName.rpcEnabled, R.string.enable_rpc),
                        BackupItem(PrefName.DiscordRPCModeAnime, R.string.discord_anime_presence),
                        BackupItem(PrefName.DiscordRPCModeManga, R.string.discord_manga_presence),
                        BackupItem(PrefName.DiscordRPCShowIconAnime, R.string.discord_rpc_show_icon_anime),
                        BackupItem(PrefName.DiscordRPCShowIconManga, R.string.discord_rpc_show_icon_manga),
                    )
                ),
                BackupSubCategory(
                    "accounts_lock", R.string.backup_sub_app_lock,
                    R.string.backup_sub_app_lock_desc,
                    listOf(
                        BackupItem(PrefName.AppPassword, R.string.app_lock),
                        BackupItem(PrefName.BiometricToken, R.string.backup_biometric_token),
                        BackupItem(PrefName.OverridePassword, R.string.backup_override_password),
                    )
                ),
                BackupSubCategory(
                    "accounts_proxy", R.string.backup_sub_proxy,
                    R.string.backup_sub_proxy_desc,
                    listOf(
                        BackupItem(PrefName.Socks5ProxyHost, R.string.host),
                        BackupItem(PrefName.Socks5ProxyPort, R.string.port),
                        BackupItem(PrefName.Socks5ProxyUsername, R.string.backup_proxy_username),
                        BackupItem(PrefName.Socks5ProxyPassword, R.string.backup_proxy_password),
                    )
                ),
                // Carried so restoring a backup onto a new device leaves it already linked, which
                // is the second-device case this whole flow exists for. It belongs here rather
                // than with the sync settings because it is a credential, and this section is the
                // one already marked as holding them.
                BackupSubCategory(
                    "accounts_cloud_sync", R.string.backup_sub_cloud_sync,
                    R.string.backup_sub_cloud_sync_desc,
                    listOf(
                        BackupItem(PrefName.CloudSyncKey, R.string.backup_cloud_sync_key),
                    )
                ),
                // Each source's own preferences, which the archive used to carry whether or not
                // the user wanted them — and some sources keep logins in there, so it sits in this
                // category and exporting it asks for a password like everything else here.
                BackupSubCategory(
                    "accounts_extension_settings", R.string.backup_sub_extension_settings,
                    R.string.backup_sub_extension_settings_desc,
                    listOf(
                        BackupItem(
                            titleRes = R.string.backup_extension_settings,
                            section = BackupSection.ExtensionSettings,
                        ),
                    )
                ),
            )
        ),
    )

    private val allItems: List<BackupItem> by lazy {
        categories.flatMap { it.subCategories.flatMap { sub -> sub.items } }
    }

    /** Preference files the tree draws from. Section rows have no preference and no location. */
    val involvedLocations: List<Location> by lazy {
        allItems.mapNotNull { it.pref?.data?.prefLocation }.distinct()
    }

    fun keysFor(prefs: Collection<PrefName>): Set<String> = prefs.map { it.name }.toSet()

    /**
     * The human label this tree already carries for a preference, by its stored name.
     *
     * The backup screen has spent the effort of naming most settings the way the user sees them, so
     * anything else that has to show a raw preference name — the sync conflict prompt, currently —
     * can borrow it rather than inventing a second set or falling back to camel case.
     */
    private val titlesByPrefName: Map<String, Int> by lazy {
        allItems.mapNotNull { item ->
            val pref = item.pref ?: return@mapNotNull null
            item.titleRes?.let { pref.name to it }
        }.toMap()
    }

    /**
     * Names for the things cloud sync carries that this tree doesn't.
     *
     * A row in the tree is a row on the backup screen — a promise that the setting can be ticked and
     * included in a `.ani` file — so these can't simply be added there: they are synced but not
     * backed up, and the two sets have never been the same. What they still need is a name, because
     * the sync conflict prompt has to say which setting the two devices disagree about, and its
     * fallback is the stored key with its camel case split into words. That produced rows reading
     * "Mal Sync Exclude List", "Info Tab Visibility Anilist Anime" and "stack Show Novels", which
     * name an internal field rather than anything the user has ever seen.
     *
     * Keyed by stored name rather than by [PrefName] because three of them have none: they are
     * written through `setCustomVal`, which takes a bare string.
     */
    private val extraTitles: Map<String, Int> = mapOf(
        PrefName.AllowOpeningLinks.name to R.string.pref_allow_opening_links,
        PrefName.AskDownloadPdf.name to R.string.pref_ask_download_pdf,
        PrefName.DiscordShowButtons.name to R.string.pref_discord_show_buttons,
        PrefName.DiscordStatus.name to R.string.pref_discord_status,
        PrefName.HandoffDiscoveryEnabled.name to R.string.handoff_discovery_setting,
        PrefName.HiddenFromLists.name to R.string.pref_hidden_from_lists,
        PrefName.InfoTabOrderAnilistAnime.name to R.string.pref_info_tab_order_anime,
        PrefName.InfoTabOrderAnilistManga.name to R.string.pref_info_tab_order_manga,
        PrefName.InfoTabOrderMangaUpdates.name to R.string.pref_info_tab_order_mangaupdates,
        PrefName.InfoTabVisibilityAnilistAnime.name to R.string.pref_info_tab_visibility_anime,
        PrefName.InfoTabVisibilityAnilistManga.name to R.string.pref_info_tab_visibility_manga,
        PrefName.InfoTabVisibilityMangaUpdates.name to R.string.pref_info_tab_visibility_mangaupdates,
        PrefName.InfoTabOrderAnilistNovel.name to R.string.pref_info_tab_order_novel,
        PrefName.InfoTabVisibilityAnilistNovel.name to R.string.pref_info_tab_visibility_novel,
        PrefName.InfoTabOrderMangaUpdatesNovel.name to R.string.pref_info_tab_order_mangaupdates_novel,
        PrefName.InfoTabVisibilityMangaUpdatesNovel.name to R.string.pref_info_tab_visibility_mangaupdates_novel,
        PrefName.LangSort.name to R.string.pref_lang_sort,
        PrefName.LockRotation.name to R.string.pref_lock_rotation,
        PrefName.MakeDefault.name to R.string.pref_make_default,
        PrefName.MalSyncExcludeList.name to R.string.pref_malsync_exclude_list,
        PrefName.MalSyncLanguagePreferences.name to R.string.pref_malsync_languages,
        // Named "disable_*" but worded "Enable *", which is what the pref stores.
        PrefName.MangaBakaInfoEnabled.name to R.string.disable_mangabaka,
        PrefName.KitsuInfoEnabled.name to R.string.disable_kitsu,
        PrefName.SimklInfoEnabled.name to R.string.disable_simkl,
        PrefName.MangaDownloadPdf.name to R.string.download_as_pdf,
        PrefName.MangaUpdatesNotificationInterval.name to R.string.mu_notification_interval_title,
        PrefName.MangaUpdatesNotificationsEnabled.name to R.string.pref_mu_notifications,
        PrefName.SavedComickListFilters.name to R.string.pref_saved_comick_filters,
        PrefName.SavedMangaBakaFilters.name to R.string.pref_saved_mangabaka_filters,
        PrefName.SavedKitsuFilters.name to R.string.pref_saved_kitsu_filters,
        PrefName.SearchStyle.name to R.string.pref_search_style,
        PrefName.SearchStyleSupporting.name to R.string.pref_search_style_supporting,
        "subscriptions" to R.string.subscriptions,
        "mediaView" to R.string.pref_media_view,
        "stackShowNovels" to R.string.pref_stack_show_novels,
    )

    fun titleResFor(prefName: String): Int? =
        titlesByPrefName[prefName] ?: extraTitles[prefName]
}
