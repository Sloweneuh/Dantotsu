package ani.dantotsu.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.media.Media

/** The list statuses AniList has, in the order the picker offers them, with their names. */
private val STATUSES = listOf(
    "CURRENT" to R.string.tv_list_current,
    "PLANNING" to R.string.tv_list_planning,
    "COMPLETED" to R.string.tv_list_completed,
    "PAUSED" to R.string.tv_list_paused,
    "DROPPED" to R.string.tv_list_dropped,
    "REPEATING" to R.string.tv_list_repeating,
)

/**
 * The user's entry for [media], from the couch: its status and how many episodes are watched, then
 * save — or take it off the list. Score, notes and dates stay as they are (the phone edits those).
 */
@Composable
fun TvListEditor(
    media: Media,
    onSave: (status: String, progress: Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val onList = media.userStatus != null
    var status by remember { mutableStateOf(media.userStatus ?: "PLANNING") }
    var progress by remember { mutableIntStateOf(media.userProgress ?: 0) }
    val total = media.anime?.totalEpisodes
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { current.requestFocus() } }

    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE, modifier = Modifier.width(460.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.tv_list_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                STATUSES.forEach { (value, label) ->
                    val pick = {
                        status = value
                        // Completed means every episode, when the count is known.
                        if (value == "COMPLETED" && total != null) progress = total
                    }
                    ListItem(
                        selected = status == value,
                        onClick = pick,
                        headlineContent = { Text(stringResource(label)) },
                        trailingContent = { RadioButton(selected = status == value, onClick = null) },
                        modifier = (if (status == value) Modifier.focusRequester(current) else Modifier).pointerClick(onClick = pick),
                    )
                }
                Row(
                    Modifier.padding(top = 14.dp, start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.tv_list_progress), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    val less = { progress = (progress - 1).coerceAtLeast(0) }
                    val more = { progress = (progress + 1).coerceAtMost(total ?: Int.MAX_VALUE) }
                    OutlinedButton(onClick = less, modifier = Modifier.pointerClick(onClick = less)) { Text("−") }
                    Text(
                        if (total != null) "$progress / $total" else "$progress",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = more, modifier = Modifier.pointerClick(onClick = more)) { Text("+") }
                }
                Row(
                    Modifier.padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val save = { onSave(status, progress) }
                    Button(onClick = save, modifier = Modifier.pointerClick(onClick = save)) {
                        Text(stringResource(if (onList) R.string.save else R.string.add_list))
                    }
                    if (onList) OutlinedButton(onClick = onRemove, modifier = Modifier.pointerClick(onClick = onRemove)) {
                        Text(stringResource(R.string.remove))
                    }
                }
            }
        }
    }
}
