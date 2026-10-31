package ani.dantotsu.tv

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.initActivity
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import kotlin.math.roundToInt
import ani.dantotsu.media.anime.PlayerSources
import ani.dantotsu.util.LanguageHelper
import androidx.compose.foundation.layout.heightIn

/**
 * TV mode's own settings: only what matters from the couch — the account, how episodes play, the
 * languages, the sources, and TV mode itself. Everything else is set from the phone interface, and cloud sync
 * carries it here.
 */
class TvSettingsActivity : AppCompatActivity(), TvScreen {

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme { TvSettings(onExit = if (TvMode.canExit(this)) ({ TvMode.exit(this) }) else null) }
        }
    }
}

/**
 * The settings, a category at a time: the categories down the left, the focused one's settings on
 * the right — Android TV's own settings work the same way. One long list ran to some forty rows.
 */
private enum class Category(val title: Int) {
    ACCOUNT(R.string.tv_settings_account),
    PLAYBACK(R.string.tv_settings_playback),
    PROGRESS(R.string.tv_settings_progress),
    SUBTITLES(R.string.subtitles),
    APPEARANCE(R.string.tv_settings_appearance),
    EXTENSIONS(R.string.tv_extensions),
    INTERFACE(R.string.tv_settings_interface),
    ABOUT(R.string.tv_settings_about),
}

@Composable
private fun TvSettings(onExit: (() -> Unit)?) {
    // On a TV, TV mode is the only interface there is: nothing to choose, nothing to leave for.
    val categories = Category.entries.filter { it != Category.INTERFACE || onExit != null }
    var current by remember { mutableStateOf(Category.PLAYBACK) }
    // One requester a category: left from its settings goes back to it, not to whichever category
    // happens to sit level with the row being left.
    val categoryFocus = remember { Category.entries.associateWith { FocusRequester() } }
    // Right into a category's settings starts at its first row (then back where it was left), not on
    // whichever row happens to sit level with the category.
    val contentStart = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val start = Modifier.focusRequester(contentStart)
    LaunchedEffect(Unit) { runCatching { categoryFocus.getValue(Category.PLAYBACK).requestFocus() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 36.dp)) {
            Column(Modifier.width(260.dp)) {
                Text(
                    stringResource(R.string.settings),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(start = 12.dp, bottom = 20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categories.forEach { category ->
                        val pick = {
                            current = category
                            // Into its settings, once they're drawn.
                            scope.launch {
                                androidx.compose.runtime.withFrameNanos { }
                                runCatching { contentStart.requestFocus() }
                            }
                            Unit
                        }
                        ListItem(
                            selected = category == current,
                            onClick = pick,
                            headlineContent = { Text(stringResource(category.title)) },
                            modifier = Modifier
                                .focusRequester(categoryFocus.getValue(category))
                                .onFocusChanged { if (it.isFocused) current = category }
                                .pointerClick(onClick = pick),
                        )
                    }
                }
            }
            Spacer(Modifier.width(40.dp))
            // A focused row comes to rest well clear of the edge, with rows above and below it to see.
            FocusRestingAt(SETTINGS_FOCUS_REST) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .focusProperties {
                            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                            exit = { direction ->
                                if (direction == androidx.compose.ui.focus.FocusDirection.Left) categoryFocus.getValue(current)
                                else FocusRequester.Default
                            }
                        }
                        .focusRestorer(contentStart),
                    contentPadding = PaddingValues(top = 72.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (current) {
                        Category.ACCOUNT -> accountSettings(start)
                        Category.PLAYBACK -> playbackSettings(start)
                        Category.PROGRESS -> progressSettings(start)
                        Category.SUBTITLES -> subtitleSettings(start)
                        Category.APPEARANCE -> appearanceSettings(start)
                        Category.EXTENSIONS -> extensionSettings(start)
                        Category.INTERFACE -> if (onExit != null) interfaceSettings(start, onExit)
                        Category.ABOUT -> aboutSettings(start)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.accountSettings(start: Modifier) {
    item { androidx.compose.foundation.layout.Box(start) {
        val name = Anilist.username
        if (Anilist.token != null && !name.isNullOrBlank()) Info(stringResource(R.string.tv_signed_in_as, name))
        else {
            val context = androidx.compose.ui.platform.LocalContext.current
            val signIn = { context.startActivity(android.content.Intent(context, TvSignInActivity::class.java)) }
            ListItem(
                selected = false,
                onClick = signIn,
                headlineContent = { Text(stringResource(R.string.tv_sign_in)) },
                supportingContent = { Text(stringResource(R.string.tv_not_signed_in)) },
                modifier = Modifier.pointerClick(onClick = signIn),
            )
        }
    } }
    item { AppLanguageChoice() }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playbackSettings(start: Modifier) {
    item { Toggle(R.string.auto_play_next_episode, PrefName.AutoPlay, start) }
    item { Toggle(R.string.next_episode_countdown, PrefName.NextEpisodeCountdown) }
    item { Toggle(R.string.auto_skip_op_ed, PrefName.AutoSkipOPED) }
    item { Toggle(R.string.auto_skip_recap, PrefName.AutoSkipRecap) }
    item { Toggle(R.string.auto_skip_fillers, PrefName.AutoSkipFiller) }
    item { Toggle(R.string.prefer_dub, PrefName.SettingsPreferDub) }
    item { Toggle(R.string.skip_outro_to_next, PrefName.SkipOutroToNext) }
    item { Toggle(R.string.tv_skip_segments, PrefName.TimeStampsEnabled) }
    item { SpeedChoice() }
    item {
        IntChoice(
            R.string.default_resize_mode, PrefName.Resize, listOf(0, 1, 2),
            listOf(stringResource(R.string.tv_resize_fit), stringResource(R.string.tv_resize_zoom), stringResource(R.string.tv_resize_stretch)),
        )
    }
    item {
        val seconds = listOf(5, 10, 15, 20, 30, 45) // within the phone slider's 1-50
        IntChoice(R.string.seek_time, PrefName.SeekTime, seconds, seconds.map { stringResource(R.string.tv_seconds, it) })
    }
}

/** When an episode counts as watched, and how one is picked up again. */
private fun androidx.compose.foundation.lazy.LazyListScope.progressSettings(start: Modifier) {
    item { androidx.compose.foundation.layout.Box(start) { WatchedAtChoice() } }
    item { Toggle(R.string.tv_ask_update_progress, PrefName.AskIndividualPlayer) }
    item { Toggle(R.string.always_continue, PrefName.AlwaysContinue) }
    item { Toggle(R.string.tv_focus_pause, PrefName.FocusPause) }
    // Watch without anything reaching the list: no progress, no status, no start date.
    item { Toggle(R.string.incognito_mode, PrefName.Incognito) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.subtitleSettings(start: Modifier) {
    item { Toggle(R.string.tv_subtitles_on, PrefName.Subtitles, start) }
    item { SubtitleLanguageChoice() }
    item {
        val sizes = listOf(14, 16, 18, 20, 24, 28, 32, 40, 48)
        IntChoice(R.string.sub_size, PrefName.FontSize, sizes, sizes.map { it.toString() })
    }
    item { IntChoice(R.string.sub_font, PrefName.Font, SUBTITLE_FONTS.indices.toList(), SUBTITLE_FONTS) }
    item { ColorChoice(R.string.sub_text_color, PrefName.PrimaryColor) }
    item {
        IntChoice(
            R.string.sub_outline_style, PrefName.Outline, listOf(0, 1, 2, 3),
            listOf(
                stringResource(R.string.sub_outline_outline), stringResource(R.string.sub_outline_shine),
                stringResource(R.string.sub_outline_shadow), stringResource(R.string.sub_outline_none),
            ),
        )
    }
    item { ColorChoice(R.string.sub_outline_color, PrefName.SecondaryColor) }
    item { ColorChoice(R.string.sub_background_color, PrefName.SubBackground) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appearanceSettings(start: Modifier) {
    item { androidx.compose.foundation.layout.Box(start) { ThemeChoice() } }
    item { Toggle(R.string.use_oled_theme, PrefName.UseOLED, onChanged = ::restartTvMode) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.extensionSettings(start: Modifier) {
    item { androidx.compose.foundation.layout.Box(start) {
        val context = androidx.compose.ui.platform.LocalContext.current
        // Downloads are a source too (OfflineAnimeParser), but not one to count here.
        val count = AnimeSources.names.count { it != "Offline" && it != "Downloaded" }
        val open = { context.startActivity(android.content.Intent(context, TvExtensionsActivity::class.java)) }
        ListItem(
            selected = false,
            onClick = open,
            headlineContent = { Text(stringResource(R.string.tv_ext_manage)) },
            supportingContent = {
                Text(
                    if (count == 0) stringResource(R.string.tv_no_extensions)
                    else androidx.compose.ui.res.pluralStringResource(R.plurals.tv_ext_installed_count, count, count)
                )
            },
            modifier = Modifier.pointerClick(onClick = open),
        )
    } }
    item { Toggle(R.string.tv_ext_show_nsfw, PrefName.NSFWExtension) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.interfaceSettings(start: Modifier, onExit: () -> Unit) {
    item { androidx.compose.foundation.layout.Box(start) { TvModeChoice() } }
    item {
        ListItem(
            selected = false,
            onClick = onExit,
            headlineContent = { Text(stringResource(R.string.tv_exit)) },
            modifier = Modifier.pointerClick(onClick = onExit),
        )
    }
}

/** The version, and the app's updater: checked by hand here, or at every launch. */
private fun androidx.compose.foundation.lazy.LazyListScope.aboutSettings(start: Modifier) {
    item { Info(stringResource(R.string.tv_version, ani.dantotsu.BuildConfig.VERSION_NAME)) }
    item { androidx.compose.foundation.layout.Box(start) {
        val activity = androidx.compose.ui.platform.LocalContext.current as androidx.fragment.app.FragmentActivity
        val check = {
            activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                ani.dantotsu.others.AppUpdater.check(activity, post = true)
            }
            Unit
        }
        ListItem(
            selected = false,
            onClick = check,
            headlineContent = { Text(stringResource(R.string.check_app_updates)) },
            modifier = Modifier.pointerClick(onClick = check),
        )
    } }
    item { Toggle(R.string.tv_update_on_launch, PrefName.CheckUpdate) }
}

private val SETTINGS_FOCUS_REST = 140.dp

@Composable
private fun Header(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp, start = 16.dp),
    )
}

/** A line of information: something to read, not to change. */
@Composable
private fun Info(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A setting that's on or off: the whole row flips it. */
@Composable
private fun Toggle(title: Int, pref: PrefName, modifier: Modifier = Modifier, onChanged: ((android.content.Context) -> Unit)? = null) {
    var on by remember { mutableStateOf(PrefManager.getVal<Boolean>(pref)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val flip = {
        on = !on
        PrefManager.setVal(pref, on)
        onChanged?.invoke(context)
        Unit
    }
    ListItem(
        selected = false,
        onClick = flip,
        headlineContent = { Text(stringResource(title)) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = modifier.pointerClick(onClick = flip),
    )
}

/** TV mode itself: automatic (on for Android TV), on, or off — off leaves for the phone interface. */
@Composable
private fun TvModeChoice() {
    var mode by remember { mutableIntStateOf(PrefManager.getVal<Int>(PrefName.BigPictureMode)) }
    androidx.compose.foundation.layout.Column {
        listOf(
            TvMode.AUTOMATIC to R.string.tv_big_picture_automatic,
            TvMode.ON to R.string.tv_big_picture_on,
            TvMode.OFF to R.string.tv_big_picture_off,
        ).forEach { (value, label) ->
            val pick = {
                mode = value
                PrefManager.setVal(PrefName.BigPictureMode, value)
            }
            ListItem(
                selected = mode == value,
                onClick = pick,
                modifier = Modifier.pointerClick(onClick = pick),
                headlineContent = { Text(stringResource(R.string.tv_big_picture) + " · " + stringResource(label)) },
                trailingContent = { RadioButton(selected = mode == value, onClick = null) },
            )
        }
    }
}

/**
 * The app's language. Applying it means starting the screens again, so TV mode opens afresh in it —
 * on a TV there's no other interface to switch through to see it take effect.
 */
@Composable
private fun AppLanguageChoice() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val languages = remember { LanguageHelper.getSupportedLanguages() }
    val current = LanguageHelper.getCurrentLanguageCode()
    ChoiceRow(
        title = stringResource(R.string.language_setting),
        options = languages.map { it.getDisplayName() },
        selected = languages.indexOfFirst { it.code == current }.coerceAtLeast(0),
    ) { which ->
        val picked = languages[which].code
        if (picked == current) return@ChoiceRow
        LanguageHelper.setLanguage(context, picked)
        context.startActivity(
            android.content.Intent(context, TvHomeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}

/** The subtitle track the player picks first, as the phone's player settings set it. */
@Composable
private fun SubtitleLanguageChoice() {
    var selected by remember { mutableIntStateOf(PrefManager.getVal<Int>(PrefName.SubLanguage)) }
    ChoiceRow(
        title = stringResource(R.string.tv_subtitle_language),
        options = PlayerSources.SUB_LANGUAGES.toList(),
        selected = selected,
    ) { which ->
        selected = which
        PrefManager.setVal(PrefName.SubLanguage, which)
    }
}

/** A setting with one value of several: the row shows it, and opens the list to pick another. */
@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    ListItem(
        selected = false,
        onClick = { picking = true },
        headlineContent = { Text(title) },
        trailingContent = {
            // The row's own content colour, which flips with focus: the accent colour vanished on a
            // focused row's light background.
            Text(
                options.getOrNull(selected).orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.75f),
            )
        },
        modifier = Modifier.pointerClick { picking = true },
    )
    if (picking) TvDialog(onDismissRequest = { picking = false }) {
        val current = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { current.requestFocus() } }
        androidx.tv.material3.Surface(shape = CARD_SHAPE, modifier = Modifier.width(460.dp)) {
            androidx.compose.foundation.layout.Column(Modifier.padding(24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                FocusRestingAt(48.dp) {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(options.size) { index ->
                            val pick = {
                                picking = false
                                onPick(index)
                            }
                            ListItem(
                                selected = index == selected,
                                onClick = pick,
                                headlineContent = { Text(options[index]) },
                                trailingContent = { RadioButton(selected = index == selected, onClick = null) },
                                modifier = (if (index == selected) Modifier.focusRequester(current) else Modifier)
                                    .pointerClick(onClick = pick),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The subtitle fonts, in the order the player's [PrefName.Font] index counts them (as the phone lists them). */
private val SUBTITLE_FONTS = listOf(
    "Poppins Semi Bold", "Poppins Bold", "Poppins", "Poppins Thin", "Century Gothic", "Levenim MT Bold", "Blocky",
)

/** The player's speeds, as [PrefName.DefaultSpeed] indexes them (the phone's lists, cursed ones included). */
private val SPEEDS = listOf(0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 2f)
private val CURSED_SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f, 10f, 25f, 50f)

/**
 * Colours a remote can pick between, in place of the phone's colour wheel: what subtitles are
 * usually set to, and transparent for "nothing behind the text".
 */
private val SUBTITLE_COLORS = listOf(
    android.graphics.Color.WHITE to R.string.tv_color_white,
    android.graphics.Color.BLACK to R.string.tv_color_black,
    android.graphics.Color.YELLOW to R.string.tv_color_yellow,
    android.graphics.Color.CYAN to R.string.tv_color_cyan,
    android.graphics.Color.LTGRAY to R.string.tv_color_light_gray,
    android.graphics.Color.DKGRAY to R.string.tv_color_dark_gray,
    android.graphics.Color.TRANSPARENT to R.string.sub_color_transparent,
)

/** Theme and OLED apply as each screen starts: TV mode opens afresh on them. */
private fun restartTvMode(context: android.content.Context) {
    context.startActivity(
        android.content.Intent(context, TvHomeActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
}

/**
 * An Int setting picked from [values], named by [labels]. A value set elsewhere that isn't among
 * them (a slider on the phone) is offered too, as itself, rather than shown as something it isn't.
 */
@Composable
private fun IntChoice(title: Int, pref: PrefName, values: List<Int>, labels: List<String>) {
    var value by remember { mutableIntStateOf(PrefManager.getVal<Int>(pref)) }
    val shownValues = if (value in values) values else values + value
    val shownLabels = if (value in values) labels else labels + value.toString()
    ChoiceRow(stringResource(title), shownLabels, shownValues.indexOf(value)) { i ->
        value = shownValues[i]
        PrefManager.setVal(pref, value)
    }
}

@Composable
private fun SpeedChoice() {
    val speeds = if (PrefManager.getVal<Boolean>(PrefName.CursedSpeeds)) CURSED_SPEEDS else SPEEDS
    IntChoice(R.string.default_speed, PrefName.DefaultSpeed, speeds.indices.toList(), speeds.map { "${it}x" })
}

/** How much of an episode has to be watched for it to count (the phone's slider, in steps of ten). */
@Composable
private fun WatchedAtChoice() {
    var percent by remember { mutableIntStateOf((PrefManager.getVal<Float>(PrefName.WatchPercentage) * 100).roundToInt()) }
    val steps = (50..100 step 10).toList().let { if (percent in it) it else (it + percent).sorted() }
    ChoiceRow(stringResource(R.string.tv_watched_at), steps.map { "$it%" }, steps.indexOf(percent)) { i ->
        percent = steps[i]
        PrefManager.setVal(PrefName.WatchPercentage, percent / 100f)
    }
}

@Composable
private fun ColorChoice(title: Int, pref: PrefName) {
    var color by remember { mutableIntStateOf(PrefManager.getVal<Int>(pref)) }
    val known = SUBTITLE_COLORS.map { it.first }
    val colors = if (color in known) known else known + color
    val labels = SUBTITLE_COLORS.map { stringResource(it.second) } +
        if (color in known) emptyList() else listOf(String.format("#%06X", color and 0xFFFFFF))
    ChoiceRow(stringResource(title), labels, colors.indexOf(color)) { i ->
        color = colors[i]
        PrefManager.setVal(pref, color)
    }
}

/** The app's colour theme, which TV mode's colours come from. */
@Composable
private fun ThemeChoice() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val themes = ani.dantotsu.themes.ThemeManager.Companion.Theme.entries
    val current = ani.dantotsu.themes.ThemeManager.Companion.Theme.fromString(PrefManager.getVal(PrefName.Theme))
    ChoiceRow(
        stringResource(R.string.theme),
        themes.map { it.theme.lowercase().replaceFirstChar { c -> c.titlecase() } },
        themes.indexOf(current),
    ) { i ->
        if (themes[i] == current) return@ChoiceRow
        PrefManager.setVal(PrefName.Theme, themes[i].theme)
        restartTvMode(context)
    }
}
