package ani.dantotsu.settings.saving

import android.graphics.Color
import ani.dantotsu.connections.comments.AuthResponse
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.media.SearchHistory
import ani.dantotsu.media.savedfilters.SavedAniMangaFilter
import ani.dantotsu.media.savedfilters.SavedComickFilter
import ani.dantotsu.media.savedfilters.SavedMangaBakaFilter
import ani.dantotsu.media.savedfilters.SavedComickListFilter
import ani.dantotsu.media.savedfilters.SavedExtensionFilterBundle
import ani.dantotsu.media.savedfilters.SavedListFilter
import ani.dantotsu.media.savedfilters.SavedMUFilter
import ani.dantotsu.notifications.comment.CommentStore
import ani.dantotsu.notifications.subscription.SubscriptionStore
import ani.dantotsu.notifications.unread.UnreadChapterStore
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.Pref

enum class PrefName(val data: Pref) {
    //General
    AppLanguage(Pref(Location.General, String::class, "system")),
    SharedUserID(Pref(Location.General, Boolean::class, true)),
    DisableCrashReports(Pref(Location.General, Boolean::class, false)),
    OfflineView(Pref(Location.General, Int::class, 0)),
    DownloadManager(Pref(Location.General, Int::class, 0)),
    AllowMeteredDownloads(Pref(Location.General, Boolean::class, true)),
    MangaDownloadPdf(Pref(Location.General, Boolean::class, false)),
    AskDownloadPdf(Pref(Location.General, Boolean::class, true)),
    // EPUB is the default because it is what the in-app reader opens directly; HTML is for
    // getting the text out to something else.
    NovelDownloadEpub(Pref(Location.General, Boolean::class, true)),
    NovelDownloadOneFile(Pref(Location.General, Boolean::class, false)),
    AskDownloadEpub(Pref(Location.General, Boolean::class, true)),
    NSFWExtension(Pref(Location.General, Boolean::class, true)),
    ContinueMedia(Pref(Location.General, Boolean::class, true)),
    SearchSources(Pref(Location.General, Boolean::class, false)),
    RecentlyListOnly(Pref(Location.General, Boolean::class, false)),
    SettingsPreferDub(Pref(Location.General, Boolean::class, false)),
    SubscriptionCheckingNotifications(Pref(Location.General, Boolean::class, true)),
    UnreadChapterCheckingNotifications(Pref(Location.General, Boolean::class, true)),
    CheckUpdate(Pref(Location.General, Boolean::class, true)),
    VerboseLogging(Pref(Location.General, Boolean::class, false)),
    DohProvider(Pref(Location.General, Int::class, 0)),
    HidePrivate(Pref(Location.General, Boolean::class, false)),
    HideOwnActivityFromFeed(Pref(Location.General, Boolean::class, false)),
    DefaultUserAgent(
        Pref(
            Location.General,
            String::class,
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
        )
    ),
    AnimeExtensionRepos(Pref(Location.General, Set::class, setOf<String>())),
    MangaExtensionRepos(Pref(Location.General, Set::class, setOf<String>())),
    NovelExtensionRepos(Pref(Location.General, Set::class, setOf<String>())),
    // LNReader plugins are JavaScript, not APKs, so they carry their own repository list and
    // installed record rather than sharing the extension ones.
    LNReaderRepos(Pref(Location.General, Set::class, setOf<String>())),
    LNReaderInstalled(Pref(Location.General, Set::class, setOf<String>())),
    LNReaderUpdatesCount(Pref(Location.General, Int::class, 0)),
    AnimeSourcesOrder(Pref(Location.General, List::class, listOf<String>())),
    MangaSourcesOrder(Pref(Location.General, List::class, listOf<String>())),
    SortedAnimeSH(Pref(Location.General, List::class, listOf<SearchHistory>())),
    SortedMangaSH(Pref(Location.General, List::class, listOf<SearchHistory>())),
    SortedCharacterSH(Pref(Location.General, List::class, listOf<SearchHistory>())),
    SortedStaffSH(Pref(Location.General, List::class, listOf<SearchHistory>())),
    SortedStudioSH(Pref(Location.General, List::class, listOf<SearchHistory>())),
    SortedUserSH(Pref(Location.General, List::class, listOf<SearchHistory>())),
    SavedAniMangaFilters(Pref(Location.General, List::class, listOf<SavedAniMangaFilter>())),
    SavedMUFilters(Pref(Location.General, List::class, listOf<SavedMUFilter>())),
    SavedComickFilters(Pref(Location.General, List::class, listOf<SavedComickFilter>())),
    SavedMangaBakaFilters(Pref(Location.General, List::class, listOf<SavedMangaBakaFilter>())),
    SavedComickListFilters(Pref(Location.General, List::class, listOf<SavedComickListFilter>())),
    SavedListFilters(Pref(Location.General, List::class, listOf<SavedListFilter>())),
    SavedExtensionFilters(Pref(Location.General, List::class, listOf<SavedExtensionFilterBundle>())),
    NovelSourcesOrder(Pref(Location.General, List::class, listOf<String>())),
    CommentNotificationInterval(Pref(Location.General, Int::class, 0)),
    AnilistNotificationInterval(Pref(Location.General, Int::class, 3)),
    SubscriptionNotificationInterval(Pref(Location.General, Int::class, 2)), // Legacy: index-based
    SubscriptionNotificationIntervalMinutes(Pref(Location.General, Long::class, 60L)), // New: 480 minutes = 8 hours
    UnreadChapterNotificationInterval(Pref(Location.General, Long::class, 60L)), // 60 minutes = 1 hour
    LastAnilistNotificationId(Pref(Location.General, Int::class, 0)),
    AnilistFilteredTypes(Pref(Location.General, Set::class, setOf<String>())),
    UseAlarmManager(Pref(Location.General, Boolean::class, false)),
    FirebaseToken(Pref(Location.General, String::class, "")),
    LastFirebaseBackgroundCheck(Pref(Location.General, Long::class, 0L)),
    LastUnreadChapterCheck(Pref(Location.General, Long::class, 0L)),
    LastSubscriptionCheck(Pref(Location.General, Long::class, 0L)),
    IncludeAnimeList(Pref(Location.General, Boolean::class, true)),
    IncludeMangaList(Pref(Location.General, Boolean::class, true)),
    AdultOnly(Pref(Location.General, Boolean::class, false)),
    CommentsEnabled(Pref(Location.General, Int::class, 0)),
    EnableSocks5Proxy(Pref(Location.General, Boolean::class, false)),
    ProxyAuthEnabled(Pref(Location.General, Boolean::class, false)),
    AniMangaSearchDirect(Pref(Location.General, Boolean::class, true)),
    // Local "Continue on another device" discovery (Nearby + LAN); QR/sharing-code stay available.
    HandoffDiscoveryEnabled(Pref(Location.General, Boolean::class, true)),
    // Default toggle states for the reader/player screenshot composer.
    ScreenshotShowMediaInfo(Pref(Location.General, Boolean::class, true)),
    ScreenshotShowDate(Pref(Location.General, Boolean::class, true)),
    ScreenshotShowSource(Pref(Location.General, Boolean::class, true)),
    ScreenshotShowUserInfo(Pref(Location.General, Boolean::class, false)),
    ScreenshotShowAppLogo(Pref(Location.General, Boolean::class, true)),
    ScreenshotShowFrame(Pref(Location.General, Boolean::class, true)),
    ScreenshotShowRoundedCorners(Pref(Location.General, Boolean::class, true)),
    // Anime clip capture: how far back from the current position the clip starts, and what it
    // exports as. The trim range in the review sheet always starts at this full duration.
    ClipDurationSeconds(Pref(Location.General, Int::class, 30)),
    ClipExportAsGif(Pref(Location.General, Boolean::class, false)),
    ClipBurnSubtitles(Pref(Location.General, Boolean::class, true)),
    ClipGifFps(Pref(Location.General, Int::class, 15)),
    ClipGifWidth(Pref(Location.General, Int::class, 480)),
    // Master switch for syncing settings across devices via the Anilist account (Firebase RTDB).
    // Off until the user links a device. It used to default on, which read as "your data is
    // syncing" on a screen where nothing was — sync does nothing without a sync code, so the
    // toggle claimed a state the app was not in. Linking switches it on; see SyncIdentity.
    CloudSyncEnabled(Pref(Location.General, Boolean::class, false)),
    // Opt-in: also publish/reconcile the set of installed extensions across devices.
    SyncExtensionsEnabled(Pref(Location.General, Boolean::class, false)),
    // Opt-in: sync per-extension settings (may include source logins) across devices.
    SyncExtensionSettingsEnabled(Pref(Location.General, Boolean::class, false)),
    // Connection toggles (allow disabling external info/tabs)
    ComickEnabled(Pref(Location.General, Boolean::class, true)),
    MalEnabled(Pref(Location.General, Boolean::class, true)),
    MangaUpdatesEnabled(Pref(Location.General, Boolean::class, true)),
    MangaUpdatesListEnabled(Pref(Location.General, Boolean::class, true)),
    MangaBakaInfoEnabled(Pref(Location.General, Boolean::class, true)),
    MangaBakaListSyncEnabled(Pref(Location.General, Boolean::class, true)),
    /** Index into [ani.dantotsu.media.MangaBakaTagWeights.options] — where the tag list starts out. */
    MangaBakaTagWeightFilter(Pref(Location.General, Int::class, 1)),
    MalListSyncEnabled(Pref(Location.General, Boolean::class, true)),

    /**
     * How often the list comparison runs by itself and pushes what it finds, in minutes. 0 is off,
     * which is the default — this one goes out and writes to the user's tracker lists unattended, so
     * it only ever runs because the user asked for it.
     */
    AutoListSyncInterval(Pref(Location.General, Long::class, 0L)),

    /**
     * Whether an automatic run also applies removals — entries on a tracker that are in neither
     * source list. Off by default and separate from the interval: pushing an update the user can see
     * and correct is a different proposition from deleting a list entry while they aren't looking.
     */
    AutoListSyncRemovals(Pref(Location.General, Boolean::class, false)),

    /** When the last automatic run finished (epoch ms, 0 = never), and what it did. */
    AutoListSyncLastRun(Pref(Location.Irrelevant, Long::class, 0L)),
    AutoListSyncLastSynced(Pref(Location.Irrelevant, Int::class, 0)),
    AutoListSyncLastFailed(Pref(Location.Irrelevant, Int::class, 0)),
    MangaUpdatesNotificationsEnabled(Pref(Location.General, Boolean::class, true)),
    MangaUpdatesNotificationInterval(Pref(Location.General, Long::class, 0L)),
    MuCustomListMapping(Pref(Location.General, String::class, "")),
    MuCustomListTitles(Pref(Location.General, String::class, "")),
    MalSyncInfoEnabled(Pref(Location.General, Boolean::class, true)),
    MalSyncCheckMode(Pref(Location.General, String::class, "both")),

    /** How the home Unread Chapters row is ordered: "unread" (fewest unread first) or "recent". */
    UnreadChaptersSort(Pref(Location.General, String::class, "unread")),
    MalSyncExcludeList(Pref(Location.General, Set::class, setOf<String>())),
    HiddenFromLists(Pref(Location.General, Set::class, setOf<String>())),

    // Info tab order/visibility per media context (indices into InfoTabContext.tabs)
    InfoTabOrderAnilistAnime(Pref(Location.UI, List::class, listOf(0, 1, 2))),
    InfoTabVisibilityAnilistAnime(Pref(Location.UI, List::class, listOf(true, true, true))),
    InfoTabOrderAnilistManga(Pref(Location.UI, List::class, listOf(0, 1, 2, 3, 4))),
    InfoTabVisibilityAnilistManga(Pref(Location.UI, List::class, listOf(true, true, true, true, true))),
    InfoTabOrderMangaUpdates(Pref(Location.UI, List::class, listOf(0, 1, 2, 3))),
    InfoTabVisibilityMangaUpdates(Pref(Location.UI, List::class, listOf(true, true, true, true))),
    // Novels get their own contexts rather than sharing manga's: Comick has no novel catalogue, so
    // its tab is not in their list at all, and the saved indices would not line up if it were.
    InfoTabOrderAnilistNovel(Pref(Location.UI, List::class, listOf(0, 1, 2, 3))),
    InfoTabVisibilityAnilistNovel(Pref(Location.UI, List::class, listOf(true, true, true, true))),
    InfoTabOrderMangaUpdatesNovel(Pref(Location.UI, List::class, listOf(0, 1, 2))),
    InfoTabVisibilityMangaUpdatesNovel(Pref(Location.UI, List::class, listOf(true, true, true))),

    //User Interface
    UseOLED(Pref(Location.UI, Boolean::class, false)),
    UseCustomTheme(Pref(Location.UI, Boolean::class, false)),
    CustomThemeInt(Pref(Location.UI, Int::class, Color.parseColor("#6200EE"))),
    UseSourceTheme(Pref(Location.UI, Boolean::class, false)),
    UseMaterialYou(Pref(Location.UI, Boolean::class, false)),
    Theme(Pref(Location.UI, String::class, "PURPLE")),
    SkipExtensionIcons(Pref(Location.UI, Boolean::class, false)),
    DarkMode(Pref(Location.UI, Int::class, 0)),
    ShowYtButton(Pref(Location.UI, Boolean::class, true)),
    AnimeDefaultView(Pref(Location.UI, Int::class, 0)),
    MangaDefaultView(Pref(Location.UI, Int::class, 0)),
    BlurBanners(Pref(Location.UI, Boolean::class, true)),
    BlurRadius(Pref(Location.UI, Float::class, 2f)),
    BlurSampling(Pref(Location.UI, Float::class, 2f)),
    ImmersiveMode(Pref(Location.UI, Boolean::class, false)),
    ShowSystemBarsUI(Pref(Location.UI, Boolean::class, true)),
    SmallView(Pref(Location.UI, Boolean::class, true)),
    DefaultStartUpTab(Pref(Location.UI, Int::class, 1)),
    HomeLayout(
        Pref(
            Location.UI,
            List::class,
            listOf(true, false, false, true, true, false, false, true, true)
        )
    ),
    // Default order changed to: UserStatus, AnimeContinue, AnimeFav, AnimePlanned,
    // MangaContinue, UnreadChapters, MangaFav, MangaPlanned, Recommendation
    HomeLayoutOrder(Pref(Location.UI, List::class, listOf(8,0,1,2,4,3,5,6,7))),
    BannerAnimations(Pref(Location.UI, Boolean::class, true)),
    LayoutAnimations(Pref(Location.UI, Boolean::class, true)),
    TrendingScroller(Pref(Location.UI, Boolean::class, true)),
    AnimationSpeed(Pref(Location.UI, Float::class, 1f)),
    ListGrid(Pref(Location.UI, Boolean::class, true)),
    PopularMangaList(Pref(Location.UI, Boolean::class, true)),
    PopularAnimeList(Pref(Location.UI, Boolean::class, true)),
    AnimeListSortOrder(Pref(Location.UI, String::class, "score")),
    MangaListSortOrder(Pref(Location.UI, String::class, "score")),
    CommentSortOrder(Pref(Location.UI, String::class, "newest")),
    FollowerLayout(Pref(Location.UI, Int::class, 0)),
    ShowNotificationRedDot(Pref(Location.UI, Boolean::class, true)),
    ShowAnimeTab(Pref(Location.UI, Boolean::class, true)),
    ShowMangaTab(Pref(Location.UI, Boolean::class, true)),
    HomeStat1(Pref(Location.UI, Int::class, 1)),
    HomeStat2(Pref(Location.UI, Int::class, 2)),


    //Player
    DefaultSpeed(Pref(Location.Player, Int::class, 5)),
    CursedSpeeds(Pref(Location.Player, Boolean::class, false)),
    Resize(Pref(Location.Player, Int::class, 0)),
    Subtitles(Pref(Location.Player, Boolean::class, true)),
    TextviewSubtitles(Pref(Location.Player, Boolean::class, false)),
    SubLanguage(Pref(Location.Player, Int::class, 9)),
    PrimaryColor(Pref(Location.Player, Int::class, Color.WHITE)),
    SecondaryColor(Pref(Location.Player, Int::class, Color.BLACK)),
    Outline(Pref(Location.Player, Int::class, 0)),
    SubBackground(Pref(Location.Player, Int::class, Color.TRANSPARENT)),
    SubWindow(Pref(Location.Player, Int::class, Color.TRANSPARENT)),
    SubAlpha(Pref(Location.Player, Float::class, 1f)),
    SubStroke(Pref(Location.Player, Float::class, 8f)),
    SubBottomMargin(Pref(Location.Player, Float::class, 1f)),
    Font(Pref(Location.Player, Int::class, 0)),
    FontSize(Pref(Location.Player, Int::class, 20)),
    Locale(Pref(Location.Player, Int::class, 2)),
    TimeStampsEnabled(Pref(Location.Player, Boolean::class, true)),
    AutoHideTimeStamps(Pref(Location.Player, Boolean::class, true)),
    UseProxyForTimeStamps(Pref(Location.Player, Boolean::class, false)),
    ShowTimeStampButton(Pref(Location.Player, Boolean::class, true)),
    AutoSkipOPED(Pref(Location.Player, Boolean::class, false)),
    AutoSkipRecap(Pref(Location.Player, Boolean::class, false)),
    AutoPlay(Pref(Location.Player, Boolean::class, true)),
    AutoSkipFiller(Pref(Location.Player, Boolean::class, false)),
    AskIndividualPlayer(Pref(Location.Player, Boolean::class, true)),
    ChapterZeroPlayer(Pref(Location.Player, Boolean::class, true)),
    UpdateForHPlayer(Pref(Location.Player, Boolean::class, false)),
    WatchPercentage(Pref(Location.Player, Float::class, 0.8f)),
    AlwaysContinue(Pref(Location.Player, Boolean::class, true)),
    FocusPause(Pref(Location.Player, Boolean::class, true)),
    Gestures(Pref(Location.Player, Boolean::class, true)),
    DoubleTap(Pref(Location.Player, Boolean::class, true)),
    FastForward(Pref(Location.Player, Boolean::class, true)),
    SeekTime(Pref(Location.Player, Int::class, 10)),
    SkipTime(Pref(Location.Player, Int::class, 85)),
    Cast(Pref(Location.Player, Boolean::class, true)),
    UseInternalCast(Pref(Location.Player, Boolean::class, false)),
    Pip(Pref(Location.Player, Boolean::class, true)),
    RotationPlayer(Pref(Location.Player, Boolean::class, true)),
    TorrentEnabled(Pref(Location.Player, Boolean::class, false)),
    UseAdditionalCodec(Pref(Location.Player, Boolean::class, false)),

    //Reader
    ShowSource(Pref(Location.Reader, Boolean::class, true)),
    ShowSystemBars(Pref(Location.Reader, Boolean::class, false)),
    AutoDetectWebtoon(Pref(Location.Reader, Boolean::class, true)),
    AskIndividualReader(Pref(Location.Reader, Boolean::class, true)),
    ChapterZeroReader(Pref(Location.Reader, Boolean::class, true)),
    UpdateForHReader(Pref(Location.Reader, Boolean::class, false)),
    Direction(Pref(Location.Reader, Int::class, 0)),
    LayoutReader(Pref(Location.Reader, Int::class, 2)),
    DualPageModeReader(Pref(Location.Reader, Int::class, 1)),
    OverScrollMode(Pref(Location.Reader, Boolean::class, true)),
    TrueColors(Pref(Location.Reader, Boolean::class, false)),
    Rotation(Pref(Location.Reader, Boolean::class, true)),
    Padding(Pref(Location.Reader, Boolean::class, true)),
    HideScrollBar(Pref(Location.Reader, Boolean::class, false)),
    HidePageNumbers(Pref(Location.Reader, Boolean::class, false)),
    HorizontalScrollBar(Pref(Location.Reader, Boolean::class, true)),
    KeepScreenOn(Pref(Location.Reader, Boolean::class, false)),
    VolumeButtonsReader(Pref(Location.Reader, Boolean::class, false)),
    WrapImages(Pref(Location.Reader, Boolean::class, false)),
    LongClickImage(Pref(Location.Reader, Boolean::class, true)),
    CropBorders(Pref(Location.Reader, Boolean::class, false)),
    CropBorderThreshold(Pref(Location.Reader, Int::class, 10)),
    AutoScrollEnabled(Pref(Location.Reader, Boolean::class, false)),
    AutoScrollSpeed(Pref(Location.Reader, Float::class, 1f)),
    ContinuousMultiChapter(Pref(Location.Reader, Boolean::class, false)),
    LockRotation(Pref(Location.Reader, Boolean::class, true)),
    PreloadAmount(Pref(Location.Reader, Int::class, 5)),

    //Novel Reader
    CurrentThemeName(Pref(Location.NovelReader, String::class, "Default")),
    LayoutNovel(Pref(Location.NovelReader, Int::class, 0)),
    DualPageModeNovel(Pref(Location.NovelReader, Int::class, 1)),
    LineHeight(Pref(Location.NovelReader, Float::class, 1.4f)),
    Margin(Pref(Location.NovelReader, Float::class, 0.06f)),
    Justify(Pref(Location.NovelReader, Boolean::class, true)),
    Hyphenation(Pref(Location.NovelReader, Boolean::class, true)),
    UseDarkThemeNovel(Pref(Location.NovelReader, Boolean::class, false)),
    UseOledThemeNovel(Pref(Location.NovelReader, Boolean::class, false)),
    Invert(Pref(Location.NovelReader, Boolean::class, false)),
    MaxInlineSize(Pref(Location.NovelReader, Int::class, 720)),
    MaxBlockSize(Pref(Location.NovelReader, Int::class, 1440)),
    HorizontalScrollBarNovel(Pref(Location.NovelReader, Boolean::class, true)),
    KeepScreenOnNovel(Pref(Location.NovelReader, Boolean::class, false)),
    VolumeButtonsNovel(Pref(Location.NovelReader, Boolean::class, false)),
    LockRotationNovel(Pref(Location.NovelReader, Boolean::class, false)),
    HidePageNumbersNovel(Pref(Location.NovelReader, Boolean::class, false)),

    // Text-to-speech, for reading a novel aloud. Speed and pitch are the engine's own scales, where
    // 1 is the voice's natural rate. Voice and engine are stored by the identifiers the platform
    // reports; a blank one means "whatever the device is set up with", which is what most people
    // want and the only thing that can be assumed to exist.
    NovelTtsSpeed(Pref(Location.NovelReader, Float::class, 1f)),
    NovelTtsPitch(Pref(Location.NovelReader, Float::class, 1f)),
    NovelTtsVoice(Pref(Location.NovelReader, String::class, "")),
    NovelTtsEngine(Pref(Location.NovelReader, String::class, "")),
    NovelTtsAutoNextChapter(Pref(Location.NovelReader, Boolean::class, true)),
    NovelTtsFollowText(Pref(Location.NovelReader, Boolean::class, true)),

    //Irrelevant
    Incognito(Pref(Location.Irrelevant, Boolean::class, false)),
    OfflineMode(Pref(Location.Irrelevant, Boolean::class, false)),

    // Ids of the quick-settings sheet's tiles, in the order the user arranged them. Ids rather
    // than indices so that adding or retiring a tile never silently reshuffles someone's layout;
    // unknown ids are dropped on read. Empty means "never customised" — see QuickTiles.DEFAULT.
    QuickTileOrder(Pref(Location.UI, List::class, listOf<String>())),

    // The search sheet is the same panel over a different catalogue; same storage shape.
    SearchTileOrder(Pref(Location.UI, List::class, listOf<String>())),

    // Times the "long-press a search to pin it" tip has been shown at the top of the search sheet
    // when it was opened from the launcher's Search shortcut. Capped low — a few looks is enough,
    // then it never appears again.
    SearchPinHintShown(Pref(Location.Irrelevant, Int::class, 0)),
    DiscordStatus(Pref(Location.Irrelevant, String::class, "online")),
    MalSyncLanguagePreferences(Pref(Location.Irrelevant, Set::class, setOf<String>())), // Stores "mediaId:language" pairs
    DiscordRPCModeAnime(Pref(Location.Irrelevant, String::class, "dantotsu")),
    DiscordRPCModeManga(Pref(Location.Irrelevant, String::class, "dantotsu")),
    DiscordRPCShowIconAnime(Pref(Location.Irrelevant, Boolean::class, true)),
    DiscordRPCShowIconManga(Pref(Location.Irrelevant, Boolean::class, true)),
    DiscordShowButtons(Pref(Location.Irrelevant, Boolean::class, true)),
    DownloadsKeys(Pref(Location.Irrelevant, String::class, "")),
    NovelLastExtCheck(Pref(Location.Irrelevant, Long::class, 0L)),
    ImageUrl(Pref(Location.Irrelevant, String::class, "")),
    AllowOpeningLinks(Pref(Location.Irrelevant, Boolean::class, false)),
    SearchStyle(Pref(Location.Irrelevant, Int::class, 0)),
    SearchStyleSupporting(Pref(Location.Irrelevant, Int::class, 0)),
    HasUpdatedPrefs(Pref(Location.Irrelevant, Boolean::class, false)),
    LangSort(Pref(Location.Irrelevant, String::class, "all")),
    GenresList(Pref(Location.Irrelevant, Set::class, setOf<String>())),
    TagsListIsAdult(Pref(Location.Irrelevant, Set::class, setOf<String>())),
    TagsListNonAdult(Pref(Location.Irrelevant, Set::class, setOf<String>())),
    MakeDefault(Pref(Location.Irrelevant, Boolean::class, true)),
    FirstComment(Pref(Location.Irrelevant, Boolean::class, true)),
    CommentAuthResponse(Pref(Location.Irrelevant, AuthResponse::class, "")),
    CommentTokenExpiry(Pref(Location.Irrelevant, Long::class, 0L)),
    LogToFile(Pref(Location.Irrelevant, Boolean::class, false)),
    RecentGlobalNotification(Pref(Location.Irrelevant, Int::class, 0)),
    CommentNotificationStore(Pref(Location.Irrelevant, List::class, listOf<CommentStore>())),
    SubscriptionNotificationStore(
        Pref(
            Location.Irrelevant,
            List::class,
            listOf<SubscriptionStore>()
        )
    ),
    UnreadChapterNotificationStore(
        Pref(
            Location.Irrelevant,
            List::class,
            listOf<UnreadChapterStore>()
        )
    ),
    UnreadCommentNotifications(Pref(Location.Irrelevant, Int::class, 0)),
    DownloadsDir(Pref(Location.Irrelevant, String::class, "")),
    DownloadsDirNested(Pref(Location.Irrelevant, Boolean::class, true)),
    OC(Pref(Location.Irrelevant, Boolean::class, false)),
    RefreshStatus(Pref(Location.Irrelevant, Boolean::class, false)),
    rpcEnabled(Pref(Location.Irrelevant, Boolean::class, true)),

    //Protected
    DiscordToken(Pref(Location.Protected, String::class, "")),
    DiscordId(Pref(Location.Protected, String::class, "")),
    DiscordUserName(Pref(Location.Protected, String::class, "")),
    DiscordAvatar(Pref(Location.Protected, String::class, "")),
    AnilistToken(Pref(Location.Protected, String::class, "")),
    AnilistUserName(Pref(Location.Protected, String::class, "")),
    AnilistUserId(Pref(Location.Protected, String::class, "")),
    AnilistAvatar(Pref(Location.Protected, String::class, "")),
    MALUserName(Pref(Location.Protected, String::class, "")),
    MALCodeChallenge(Pref(Location.Protected, String::class, "")),
    MALToken(Pref(Location.Protected, MAL.ResponseToken::class, "")),
    MangaUpdatesUsername(Pref(Location.Protected, String::class, "")),
    MangaUpdatesPassword(Pref(Location.Protected, String::class, "")),
    MangaUpdatesToken(Pref(Location.Protected, String::class, "")),
    MangaBakaToken(Pref(Location.Protected, String::class, "")),
    MangaBakaUserName(Pref(Location.Protected, String::class, "")),
    MangaBakaUserId(Pref(Location.Protected, String::class, "")),
    AppPassword(Pref(Location.Protected, String::class, "")),
    BiometricToken(Pref(Location.Protected, String::class, "")),
    OverridePassword(Pref(Location.Protected, Boolean::class, false)),
    // The cloud-sync secret. Protected, so cloud sync never uploads it — a key that syncs itself
    // to the cloud it protects would be no key at all. Not listed in BackupTree either, so a local
    // backup doesn't carry it and a restored device has to be linked by hand.
    CloudSyncKey(Pref(Location.Protected, String::class, "")),
    Socks5ProxyHost(Pref(Location.Protected, String::class, "")),
    Socks5ProxyPort(Pref(Location.Protected, String::class, "")),
    Socks5ProxyUsername(Pref(Location.Protected, String::class, "")),
    Socks5ProxyPassword(Pref(Location.Protected, String::class, "")),
}
