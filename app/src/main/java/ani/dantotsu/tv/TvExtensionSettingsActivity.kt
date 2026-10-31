package ani.dantotsu.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.initActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.data.preference.SharedPreferencesDataStore
import eu.kanade.tachiyomi.source.anime.getPreferenceKey
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * An extension's own settings, drawn for a remote. The extension describes them as a preference
 * screen — the phone shows that screen as it is — so it's built the same way here, through the same
 * store, and each kind of preference becomes a TV row: switches toggle, lists and multi-selects open
 * a list to pick from, text opens the keyboard. A change goes through the preference's own change
 * listener first, as the phone's screen would, so an extension that reacts to its settings still does.
 */
class TvExtensionSettingsActivity : AppCompatActivity(), TvScreen {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        val source = Injekt.get<AnimeSourceManager>().get(intent.getLongExtra(EXTRA_SOURCE, -1L)) as? ConfigurableAnimeSource
        val preferences = source?.let { buildPreferences(it) }
        if (source == null || preferences == null) {
            finish()
            return
        }
        setContent { TvTheme { ExtensionSettings(source.name, preferences) } }
    }

    /** The extension's preference screen, flattened: categories become headers, the rest rows. */
    @Suppress("RestrictedApi")
    private fun buildPreferences(source: ConfigurableAnimeSource): List<Preference>? = runCatching {
        val manager = PreferenceManager(this)
        manager.preferenceDataStore = SharedPreferencesDataStore(getSharedPreferences(source.getPreferenceKey(), MODE_PRIVATE))
        val screen = manager.createPreferenceScreen(this)
        source.setupPreferenceScreen(screen)
        buildList {
            fun walk(group: PreferenceGroup) {
                for (i in 0 until group.preferenceCount) {
                    val pref = group.getPreference(i)
                    add(pref)
                    if (pref is PreferenceGroup) walk(pref)
                }
            }
            walk(screen)
        }
    }.onFailure { Logger.log("TvExtensionSettings: couldn't build ${source.name}'s settings: ${it.message}") }.getOrNull()

    companion object {
        const val EXTRA_SOURCE = "tv_extension_source"
    }
}

@Composable
private fun ExtensionSettings(title: String, preferences: List<Preference>) {
    // Bumped after each change: the preferences are plain objects, so a changed value redraws nothing
    // on its own.
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Preference?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val shown = preferences.filter { it.isVisible }
    val firstRow = shown.firstOrNull { it !is PreferenceCategory }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(720.dp).padding(top = 36.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 12.dp))
            if (shown.none { it !is PreferenceCategory }) {
                Text(
                    stringResource(R.string.tv_ext_no_settings),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FocusRestingAt(140.dp) {
                LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { it.key ?: it.hashCode().toString() }) { pref ->
                        version // read, so the rows redraw after a change
                        val modifier = if (pref === firstRow) Modifier.focusRequester(first) else Modifier
                        when (pref) {
                            is PreferenceCategory -> Text(
                                pref.title?.toString().orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 22.dp, bottom = 6.dp, start = 16.dp),
                            )
                            is TwoStatePreference -> {
                                val flip = {
                                    val next = !pref.isChecked
                                    if (pref.callChangeListener(next)) pref.isChecked = next
                                    version += 1
                                }
                                ListItem(
                                    selected = false,
                                    enabled = pref.isEnabled,
                                    onClick = flip,
                                    headlineContent = { Text(pref.title?.toString().orEmpty()) },
                                    supportingContent = pref.summary?.takeIf { it.isNotBlank() }?.let { { Text(it.toString()) } },
                                    trailingContent = { Switch(checked = pref.isChecked, onCheckedChange = null) },
                                    modifier = modifier.pointerClick(onClick = flip),
                                )
                            }
                            else -> {
                                val open = { if (pref.isEnabled) editing = pref }
                                ListItem(
                                    selected = false,
                                    enabled = pref.isEnabled,
                                    onClick = open,
                                    headlineContent = { Text(pref.title?.toString().orEmpty()) },
                                    supportingContent = currentValue(pref)?.let { { Text(it, maxLines = 2) } },
                                    modifier = modifier.pointerClick(onClick = open),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { pref ->
        val done = { editing = null; version += 1 }
        when (pref) {
            is ListPreference -> ListDialog(pref, done)
            is MultiSelectListPreference -> MultiDialog(pref, done)
            is EditTextPreference -> TextDialog(pref, done)
            else -> {
                // Anything else (a plain clickable preference): its own click, as the phone would.
                pref.onPreferenceClickListener?.onPreferenceClick(pref)
                done()
            }
        }
    }
}

/** What the row says under its title: the chosen value, or the extension's own summary. */
private fun currentValue(pref: Preference): String? = when (pref) {
    is ListPreference -> pref.entry?.toString()
    is MultiSelectListPreference -> pref.values.mapNotNull { v ->
        pref.entryValues.indexOf(v).takeIf { it >= 0 }?.let { pref.entries[it].toString() }
    }.joinToString(", ").ifBlank { null }
    is EditTextPreference -> pref.text?.takeIf { it.isNotBlank() }
    else -> null
} ?: pref.summary?.toString()?.takeIf { it.isNotBlank() && !it.contains("%s") }

@Composable
private fun ListDialog(pref: ListPreference, onDone: () -> Unit) {
    val entries = pref.entries.orEmpty().map { it.toString() }
    val values = pref.entryValues.orEmpty().map { it.toString() }
    val selected = values.indexOf(pref.value)
    PickerDialog(pref.title?.toString().orEmpty(), onDone) { current ->
        items(entries.size) { i ->
            val pick = {
                if (pref.callChangeListener(values[i])) pref.value = values[i]
                onDone()
            }
            ListItem(
                selected = i == selected,
                onClick = pick,
                headlineContent = { Text(entries[i]) },
                trailingContent = { RadioButton(selected = i == selected, onClick = null) },
                modifier = (if (i == selected.coerceAtLeast(0)) Modifier.focusRequester(current) else Modifier).pointerClick(onClick = pick),
            )
        }
    }
}

@Composable
private fun MultiDialog(pref: MultiSelectListPreference, onDone: () -> Unit) {
    val entries = pref.entries.orEmpty().map { it.toString() }
    val values = pref.entryValues.orEmpty().map { it.toString() }
    var chosen by remember { mutableStateOf(pref.values.toSet()) }
    val save = {
        if (pref.callChangeListener(chosen)) pref.values = chosen
        onDone()
    }
    PickerDialog(pref.title?.toString().orEmpty(), onDismiss = save, footer = {
        Button(onClick = save, modifier = Modifier.padding(top = 14.dp).pointerClick(onClick = save)) {
            Text(stringResource(R.string.tv_ext_done))
        }
    }) { current ->
        items(entries.size) { i ->
            val on = values[i] in chosen
            val flip = { chosen = if (on) chosen - values[i] else chosen + values[i] }
            ListItem(
                selected = false,
                onClick = flip,
                headlineContent = { Text(entries[i]) },
                trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                modifier = (if (i == 0) Modifier.focusRequester(current) else Modifier).pointerClick(onClick = flip),
            )
        }
    }
}

@Composable
private fun TextDialog(pref: EditTextPreference, onDone: () -> Unit) {
    var text by remember { mutableStateOf(pref.text.orEmpty()) }
    val save = {
        if (pref.callChangeListener(text)) pref.text = text
        onDone()
    }
    TvDialog(onDismissRequest = onDone) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(560.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(pref.title?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall)
                val field = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                TvSearchField(
                    query = text,
                    onQuery = { text = it },
                    hint = pref.dialogMessage?.toString() ?: pref.title?.toString().orEmpty(),
                    onSubmit = save,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).focusRequester(field),
                )
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = save, modifier = Modifier.pointerClick(onClick = save)) { Text(stringResource(R.string.save)) }
                    OutlinedButton(onClick = onDone, modifier = Modifier.pointerClick(onClick = onDone)) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/** A titled list in a dialog, its current entry (handed to [rows] as a requester) taking focus. */
@Composable
private fun PickerDialog(
    title: String,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit = {},
    rows: androidx.compose.foundation.lazy.LazyListScope.(FocusRequester) -> Unit,
) {
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // After the list's first frame: before it, the row to focus isn't there yet.
        androidx.compose.runtime.withFrameNanos { }
        runCatching { current.requestFocus() }
    }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(480.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 10.dp))
                FocusRestingAt(48.dp) {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) { rows(current) }
                }
                footer()
            }
        }
    }
}
