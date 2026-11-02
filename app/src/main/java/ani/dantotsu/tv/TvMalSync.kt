package ani.dantotsu.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.malsync.LanguageMapper
import ani.dantotsu.connections.malsync.LanguageOption

/**
 * What MALSync says of a title, as the media page shows it: the newest episode out in the release
 * language the title follows (not which site had it, which says nothing for anime). [shown] is false when that number says
 * nothing the page doesn't (all of it out, or behind the user) — the language button stays, so
 * another language can still be picked.
 */
class TvMalSync(val lastEpisode: Int, val language: LanguageOption, val shown: Boolean)

/** "English Dub · up to episode 7", behind its dub or sub icon. */
@Composable
fun MalSyncReleaseLine(info: TvMalSync, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(info.language.iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            LanguageMapper.displayWithType(info.language.id) + "  ·  " + stringResource(R.string.tv_malsync_up_to, info.lastEpisode),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** The release language button's face: its dub or sub icon and two-letter code. */
@Composable
fun MalSyncLanguageLabel(language: LanguageOption) {
    Icon(painterResource(language.iconRes), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
    Text(LanguageMapper.shortCode(language.id))
}

/**
 * The release languages MALSync knows for the title, with how far each has got, the one followed
 * first in focus; picking one follows it from now on, for this title. Asked for as it opens.
 */
@Composable
fun MalSyncLanguageDialog(
    current: String,
    load: suspend () -> List<LanguageOption>,
    onPick: (LanguageOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var options by remember { mutableStateOf<List<LanguageOption>?>(null) }
    LaunchedEffect(Unit) { options = load() }
    val selected = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(460.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.tv_malsync_language),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.tv_malsync_language_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                val list = options
                when {
                    list == null -> Text(stringResource(R.string.loading), modifier = Modifier.padding(16.dp))
                    list.isEmpty() -> Text(stringResource(R.string.tv_malsync_no_languages), modifier = Modifier.padding(16.dp))
                    else -> FocusRestingAt(48.dp) {
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(list, key = { it.id }) { option ->
                                val pick = { onPick(option) }
                                val isCurrent = option.id == current
                                ListItem(
                                    selected = isCurrent,
                                    onClick = pick,
                                    headlineContent = { Text(LanguageMapper.displayWithType(option.id)) },
                                    supportingContent = option.episodeCount?.let { count ->
                                        { Text(pluralStringResource(R.plurals.tv_episode_count, count, count)) }
                                    },
                                    leadingContent = { Icon(painterResource(option.iconRes), contentDescription = null, modifier = Modifier.size(24.dp)) },
                                    trailingContent = { RadioButton(selected = isCurrent, onClick = null) },
                                    modifier = (if (isCurrent || (list.none { it.id == current } && option == list.first())) Modifier.focusRequester(selected) else Modifier)
                                        .pointerClick(onClick = pick),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(options) {
        if (options.isNullOrEmpty()) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching { selected.requestFocus() }
    }
}
