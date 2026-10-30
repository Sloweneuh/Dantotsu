package ani.dantotsu.tv

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // TV mode is dark whatever the system says: a bright screen glares across a dark room.
        delegate.localNightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        setContent {
            TvTheme { TvSettings(onExit = if (TvMode.canExit(this)) ({ TvMode.exit(this) }) else null) }
        }
    }
}

@Composable
private fun TvSettings(onExit: (() -> Unit)?) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.width(680.dp),
            contentPadding = PaddingValues(vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.settings),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            item { Header(R.string.tv_settings_account) }
            item {
                val name = Anilist.username
                Info(
                    if (Anilist.token != null && !name.isNullOrBlank()) stringResource(R.string.tv_signed_in_as, name)
                    else stringResource(R.string.tv_not_signed_in)
                )
            }

            item { Header(R.string.tv_settings_playback) }
            item { Toggle(R.string.auto_play_next_episode, PrefName.AutoPlay, Modifier.focusRequester(first)) }
            item { Toggle(R.string.next_episode_countdown, PrefName.NextEpisodeCountdown) }
            item { Toggle(R.string.auto_skip_op_ed, PrefName.AutoSkipOPED) }
            item { Toggle(R.string.auto_skip_recap, PrefName.AutoSkipRecap) }
            item { Toggle(R.string.auto_skip_fillers, PrefName.AutoSkipFiller) }
            item { Toggle(R.string.prefer_dub, PrefName.SettingsPreferDub) }

            item { Header(R.string.tv_settings_language) }
            item { AppLanguageChoice() }
            item { SubtitleLanguageChoice() }

            item { Header(R.string.tv_settings_sources) }
            // Downloads are a source too (OfflineAnimeParser), but not one to list here.
            val sources = AnimeSources.names.filterNot { it == "Offline" || it == "Downloaded" }
            if (sources.isEmpty()) item { Info(stringResource(R.string.tv_no_extensions)) }
            sources.forEach { name -> item { Info(name) } }
            item { Info(stringResource(R.string.tv_sources_hint), dim = true) }

            // On a TV, TV mode is the only interface there is: nothing to choose, nothing to leave for.
            if (onExit != null) {
                item { Header(R.string.tv_settings_interface) }
                item { TvModeChoice() }
                item {
                    ListItem(
                        selected = false,
                        onClick = onExit,
                        headlineContent = { Text(stringResource(R.string.tv_exit)) },
                        modifier = Modifier.pointerClick(onClick = onExit),
                    )
                }
            }
        }
    }
}

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
private fun Toggle(title: Int, pref: PrefName, modifier: Modifier = Modifier) {
    var on by remember { mutableStateOf(PrefManager.getVal<Boolean>(pref)) }
    val flip = {
        on = !on
        PrefManager.setVal(pref, on)
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
            Text(
                options.getOrNull(selected).orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
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
