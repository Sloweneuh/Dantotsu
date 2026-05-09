package ani.dantotsu.settings.saving

import ani.dantotsu.R
import ani.dantotsu.settings.saving.internal.Location

data class BackupItem(
    val pref: PrefName,
    val titleRes: Int? = null,
)

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
                        BackupItem(PrefName.AppLanguage),
                        BackupItem(PrefName.ContinueMedia),
                        BackupItem(PrefName.SearchSources),
                        BackupItem(PrefName.RecentlyListOnly),
                        BackupItem(PrefName.AdultOnly),
                        BackupItem(PrefName.NSFWExtension),
                        BackupItem(PrefName.IncludeAnimeList),
                        BackupItem(PrefName.IncludeMangaList),
                        BackupItem(PrefName.AniMangaSearchDirect),
                        BackupItem(PrefName.HidePrivate),
                        BackupItem(PrefName.SettingsPreferDub),
                        BackupItem(PrefName.SharedUserID),
                    )
                ),
                BackupSubCategory(
                    "general_downloads", R.string.backup_sub_downloads,
                    R.string.backup_sub_downloads_desc,
                    listOf(
                        BackupItem(PrefName.DownloadManager),
                        BackupItem(PrefName.AllowMeteredDownloads),
                        BackupItem(PrefName.OfflineView),
                    )
                ),
                BackupSubCategory(
                    "general_notif", R.string.backup_sub_notifications,
                    R.string.backup_sub_notifications_desc,
                    listOf(
                        BackupItem(PrefName.AnilistNotificationInterval),
                        BackupItem(PrefName.CommentNotificationInterval),
                        BackupItem(PrefName.SubscriptionNotificationInterval),
                        BackupItem(PrefName.SubscriptionNotificationIntervalMinutes),
                        BackupItem(PrefName.UnreadChapterNotificationInterval),
                        BackupItem(PrefName.SubscriptionCheckingNotifications),
                        BackupItem(PrefName.UnreadChapterCheckingNotifications),
                        BackupItem(PrefName.AnilistFilteredTypes),
                        BackupItem(PrefName.UseAlarmManager),
                    )
                ),
                BackupSubCategory(
                    "general_network", R.string.backup_sub_network,
                    R.string.backup_sub_network_desc,
                    listOf(
                        BackupItem(PrefName.DohProvider),
                        BackupItem(PrefName.DefaultUserAgent),
                        BackupItem(PrefName.EnableSocks5Proxy),
                        BackupItem(PrefName.ProxyAuthEnabled),
                    )
                ),
                BackupSubCategory(
                    "general_updates", R.string.backup_sub_updates,
                    R.string.backup_sub_updates_desc,
                    listOf(
                        BackupItem(PrefName.CheckUpdate),
                        BackupItem(PrefName.VerboseLogging),
                        BackupItem(PrefName.DisableCrashReports),
                    )
                ),
                BackupSubCategory(
                    "general_connections", R.string.backup_sub_connections,
                    R.string.backup_sub_connections_desc,
                    listOf(
                        BackupItem(PrefName.ComickEnabled),
                        BackupItem(PrefName.MalEnabled),
                        BackupItem(PrefName.MangaUpdatesEnabled),
                        BackupItem(PrefName.MangaUpdatesListEnabled),
                        BackupItem(PrefName.MalSyncInfoEnabled),
                        BackupItem(PrefName.MalSyncCheckMode),
                        BackupItem(PrefName.CommentsEnabled),
                        BackupItem(PrefName.MuCustomListMapping),
                        BackupItem(PrefName.MuCustomListTitles),
                    )
                ),
                BackupSubCategory(
                    "general_sources", R.string.backup_sub_sources,
                    R.string.backup_sub_sources_desc,
                    listOf(
                        BackupItem(PrefName.AnimeExtensionRepos),
                        BackupItem(PrefName.MangaExtensionRepos),
                        BackupItem(PrefName.NovelExtensionRepos),
                        BackupItem(PrefName.AnimeSourcesOrder),
                        BackupItem(PrefName.MangaSourcesOrder),
                        BackupItem(PrefName.NovelSourcesOrder),
                    )
                ),
                BackupSubCategory(
                    "general_history", R.string.backup_sub_search_history,
                    R.string.backup_sub_search_history_desc,
                    listOf(
                        BackupItem(PrefName.SortedAnimeSH),
                        BackupItem(PrefName.SortedMangaSH),
                        BackupItem(PrefName.SortedCharacterSH),
                        BackupItem(PrefName.SortedStaffSH),
                        BackupItem(PrefName.SortedStudioSH),
                        BackupItem(PrefName.SortedUserSH),
                    )
                ),
                BackupSubCategory(
                    "general_filters", R.string.backup_sub_saved_filters,
                    R.string.backup_sub_saved_filters_desc,
                    listOf(
                        BackupItem(PrefName.SavedAniMangaFilters),
                        BackupItem(PrefName.SavedMUFilters),
                        BackupItem(PrefName.SavedComickFilters),
                        BackupItem(PrefName.SavedListFilters),
                        BackupItem(PrefName.SavedExtensionFilters),
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
                        BackupItem(PrefName.UseOLED),
                        BackupItem(PrefName.UseCustomTheme),
                        BackupItem(PrefName.CustomThemeInt),
                        BackupItem(PrefName.UseSourceTheme),
                        BackupItem(PrefName.UseMaterialYou),
                        BackupItem(PrefName.Theme),
                        BackupItem(PrefName.DarkMode),
                        BackupItem(PrefName.SkipExtensionIcons),
                    )
                ),
                BackupSubCategory(
                    "ui_layout", R.string.backup_sub_layout,
                    R.string.backup_sub_layout_desc,
                    listOf(
                        BackupItem(PrefName.DefaultStartUpTab),
                        BackupItem(PrefName.HomeLayout),
                        BackupItem(PrefName.HomeLayoutOrder),
                        BackupItem(PrefName.ShowAnimeTab),
                        BackupItem(PrefName.ShowMangaTab),
                        BackupItem(PrefName.HomeStat1),
                        BackupItem(PrefName.HomeStat2),
                        BackupItem(PrefName.FollowerLayout),
                    )
                ),
                BackupSubCategory(
                    "ui_anim", R.string.backup_sub_animations,
                    R.string.backup_sub_animations_desc,
                    listOf(
                        BackupItem(PrefName.BannerAnimations),
                        BackupItem(PrefName.LayoutAnimations),
                        BackupItem(PrefName.TrendingScroller),
                        BackupItem(PrefName.AnimationSpeed),
                    )
                ),
                BackupSubCategory(
                    "ui_blur", R.string.backup_sub_blur,
                    R.string.backup_sub_blur_desc,
                    listOf(
                        BackupItem(PrefName.BlurBanners),
                        BackupItem(PrefName.BlurRadius),
                        BackupItem(PrefName.BlurSampling),
                    )
                ),
                BackupSubCategory(
                    "ui_lists", R.string.backup_sub_list_display,
                    R.string.backup_sub_list_display_desc,
                    listOf(
                        BackupItem(PrefName.AnimeDefaultView),
                        BackupItem(PrefName.MangaDefaultView),
                        BackupItem(PrefName.ListGrid),
                        BackupItem(PrefName.PopularMangaList),
                        BackupItem(PrefName.PopularAnimeList),
                        BackupItem(PrefName.AnimeListSortOrder),
                        BackupItem(PrefName.MangaListSortOrder),
                        BackupItem(PrefName.CommentSortOrder),
                        BackupItem(PrefName.SmallView),
                    )
                ),
                BackupSubCategory(
                    "ui_misc", R.string.backup_sub_ui_misc,
                    R.string.backup_sub_ui_misc_desc,
                    listOf(
                        BackupItem(PrefName.ShowYtButton),
                        BackupItem(PrefName.ImmersiveMode),
                        BackupItem(PrefName.ShowSystemBarsUI),
                        BackupItem(PrefName.ShowNotificationRedDot),
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
                        BackupItem(PrefName.DefaultSpeed),
                        BackupItem(PrefName.CursedSpeeds),
                        BackupItem(PrefName.Resize),
                        BackupItem(PrefName.AutoPlay),
                        BackupItem(PrefName.AlwaysContinue),
                        BackupItem(PrefName.FocusPause),
                        BackupItem(PrefName.WatchPercentage),
                        BackupItem(PrefName.AskIndividualPlayer),
                        BackupItem(PrefName.ChapterZeroPlayer),
                        BackupItem(PrefName.UpdateForHPlayer),
                    )
                ),
                BackupSubCategory(
                    "player_subs", R.string.backup_sub_subtitles,
                    R.string.backup_sub_subtitles_desc,
                    listOf(
                        BackupItem(PrefName.Subtitles),
                        BackupItem(PrefName.TextviewSubtitles),
                        BackupItem(PrefName.SubLanguage),
                        BackupItem(PrefName.PrimaryColor),
                        BackupItem(PrefName.SecondaryColor),
                        BackupItem(PrefName.Outline),
                        BackupItem(PrefName.SubBackground),
                        BackupItem(PrefName.SubWindow),
                        BackupItem(PrefName.SubAlpha),
                        BackupItem(PrefName.SubStroke),
                        BackupItem(PrefName.SubBottomMargin),
                        BackupItem(PrefName.Font),
                        BackupItem(PrefName.FontSize),
                        BackupItem(PrefName.Locale),
                    )
                ),
                BackupSubCategory(
                    "player_skip", R.string.backup_sub_skip,
                    R.string.backup_sub_skip_desc,
                    listOf(
                        BackupItem(PrefName.TimeStampsEnabled),
                        BackupItem(PrefName.AutoHideTimeStamps),
                        BackupItem(PrefName.UseProxyForTimeStamps),
                        BackupItem(PrefName.ShowTimeStampButton),
                        BackupItem(PrefName.AutoSkipOPED),
                        BackupItem(PrefName.AutoSkipRecap),
                        BackupItem(PrefName.AutoSkipFiller),
                        BackupItem(PrefName.SkipTime),
                        BackupItem(PrefName.SeekTime),
                    )
                ),
                BackupSubCategory(
                    "player_gestures", R.string.backup_sub_gestures,
                    R.string.backup_sub_gestures_desc,
                    listOf(
                        BackupItem(PrefName.Gestures),
                        BackupItem(PrefName.DoubleTap),
                        BackupItem(PrefName.FastForward),
                    )
                ),
                BackupSubCategory(
                    "player_advanced", R.string.backup_sub_cast,
                    R.string.backup_sub_cast_desc,
                    listOf(
                        BackupItem(PrefName.Cast),
                        BackupItem(PrefName.UseInternalCast),
                        BackupItem(PrefName.Pip),
                        BackupItem(PrefName.RotationPlayer),
                        BackupItem(PrefName.TorrentEnabled),
                        BackupItem(PrefName.UseAdditionalCodec),
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
                        BackupItem(PrefName.Direction),
                        BackupItem(PrefName.LayoutReader),
                        BackupItem(PrefName.DualPageModeReader),
                        BackupItem(PrefName.OverScrollMode),
                        BackupItem(PrefName.TrueColors),
                        BackupItem(PrefName.Rotation),
                        BackupItem(PrefName.Padding),
                        BackupItem(PrefName.WrapImages),
                    )
                ),
                BackupSubCategory(
                    "reader_ui", R.string.backup_sub_reader_ui,
                    R.string.backup_sub_reader_ui_desc,
                    listOf(
                        BackupItem(PrefName.ShowSource),
                        BackupItem(PrefName.ShowSystemBars),
                        BackupItem(PrefName.HideScrollBar),
                        BackupItem(PrefName.HidePageNumbers),
                        BackupItem(PrefName.HorizontalScrollBar),
                        BackupItem(PrefName.KeepScreenOn),
                    )
                ),
                BackupSubCategory(
                    "reader_behavior", R.string.backup_sub_reader_behavior,
                    R.string.backup_sub_reader_behavior_desc,
                    listOf(
                        BackupItem(PrefName.AskIndividualReader),
                        BackupItem(PrefName.ChapterZeroReader),
                        BackupItem(PrefName.UpdateForHReader),
                        BackupItem(PrefName.AutoDetectWebtoon),
                        BackupItem(PrefName.VolumeButtonsReader),
                        BackupItem(PrefName.LongClickImage),
                        BackupItem(PrefName.ContinuousMultiChapter),
                    )
                ),
                BackupSubCategory(
                    "reader_autoscroll", R.string.backup_sub_autoscroll,
                    R.string.backup_sub_autoscroll_desc,
                    listOf(
                        BackupItem(PrefName.AutoScrollEnabled),
                        BackupItem(PrefName.AutoScrollSpeed),
                    )
                ),
                BackupSubCategory(
                    "reader_crop", R.string.backup_sub_crop,
                    R.string.backup_sub_crop_desc,
                    listOf(
                        BackupItem(PrefName.CropBorders),
                        BackupItem(PrefName.CropBorderThreshold),
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
                        BackupItem(PrefName.CurrentThemeName),
                        BackupItem(PrefName.LayoutNovel),
                        BackupItem(PrefName.DualPageModeNovel),
                        BackupItem(PrefName.UseDarkThemeNovel),
                        BackupItem(PrefName.UseOledThemeNovel),
                        BackupItem(PrefName.Invert),
                    )
                ),
                BackupSubCategory(
                    "novel_text", R.string.backup_sub_novel_text,
                    R.string.backup_sub_novel_text_desc,
                    listOf(
                        BackupItem(PrefName.LineHeight),
                        BackupItem(PrefName.Margin),
                        BackupItem(PrefName.Justify),
                        BackupItem(PrefName.Hyphenation),
                        BackupItem(PrefName.MaxInlineSize),
                        BackupItem(PrefName.MaxBlockSize),
                    )
                ),
                BackupSubCategory(
                    "novel_ui", R.string.backup_sub_novel_ui,
                    R.string.backup_sub_novel_ui_desc,
                    listOf(
                        BackupItem(PrefName.HorizontalScrollBarNovel),
                        BackupItem(PrefName.KeepScreenOnNovel),
                        BackupItem(PrefName.VolumeButtonsNovel),
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
                        BackupItem(PrefName.AnilistToken),
                        BackupItem(PrefName.AnilistUserName),
                        BackupItem(PrefName.AnilistUserId),
                    )
                ),
                BackupSubCategory(
                    "accounts_mal", R.string.backup_sub_mal,
                    R.string.backup_sub_mal_desc,
                    listOf(
                        BackupItem(PrefName.MALUserName),
                        BackupItem(PrefName.MALCodeChallenge),
                        BackupItem(PrefName.MALToken),
                    )
                ),
                BackupSubCategory(
                    "accounts_mu", R.string.backup_sub_mu,
                    R.string.backup_sub_mu_desc,
                    listOf(
                        BackupItem(PrefName.MangaUpdatesUsername),
                        BackupItem(PrefName.MangaUpdatesPassword),
                        BackupItem(PrefName.MangaUpdatesToken),
                    )
                ),
                BackupSubCategory(
                    "accounts_discord", R.string.backup_sub_discord,
                    R.string.backup_sub_discord_desc,
                    listOf(
                        BackupItem(PrefName.DiscordToken),
                        BackupItem(PrefName.DiscordId),
                        BackupItem(PrefName.DiscordUserName),
                        BackupItem(PrefName.DiscordAvatar),
                    )
                ),
                BackupSubCategory(
                    "accounts_lock", R.string.backup_sub_app_lock,
                    R.string.backup_sub_app_lock_desc,
                    listOf(
                        BackupItem(PrefName.AppPassword),
                        BackupItem(PrefName.BiometricToken),
                        BackupItem(PrefName.OverridePassword),
                    )
                ),
                BackupSubCategory(
                    "accounts_proxy", R.string.backup_sub_proxy,
                    R.string.backup_sub_proxy_desc,
                    listOf(
                        BackupItem(PrefName.Socks5ProxyHost),
                        BackupItem(PrefName.Socks5ProxyPort),
                        BackupItem(PrefName.Socks5ProxyUsername),
                        BackupItem(PrefName.Socks5ProxyPassword),
                    )
                ),
            )
        ),
    )

    private val allItems: List<BackupItem> by lazy {
        categories.flatMap { it.subCategories.flatMap { sub -> sub.items } }
    }

    val involvedLocations: List<Location> by lazy {
        allItems.map { it.pref.data.prefLocation }.distinct()
    }

    fun keysFor(prefs: Collection<PrefName>): Set<String> = prefs.map { it.name }.toSet()
}
