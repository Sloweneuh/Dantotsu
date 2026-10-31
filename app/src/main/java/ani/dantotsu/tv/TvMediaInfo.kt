package ani.dantotsu.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.media.Media
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

/**
 * What the phone's AniList info page shows, below the episodes: the synopsis in full, the facts
 * (format, length, dates, studio, scores…), genres and tags, the other titles, and the related,
 * characters, staff and recommended rows. Each block of text takes focus, outlined, so the D-pad
 * stops on it on the way down — a list only scrolls to what can be focused, and would otherwise
 * jump over the text between one row of cards and the next.
 */
fun LazyListScope.tvMediaInfo(media: Media, onOpen: (Media) -> Unit) {
    val synopsis = plainDescription(media)
    if (synopsis.isNotBlank()) item(key = "about") {
        InfoBlock(R.string.description) {
            Text(synopsis, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item(key = "details") {
        val facts = facts(media)
        if (facts.isNotEmpty()) InfoBlock(R.string.tv_info_details) {
            // Two columns of label and value.
            facts.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    pair.forEach { (label, value) -> Fact(label, value, Modifier.weight(1f)) }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
    if (media.genres.isNotEmpty() || media.tags.isNotEmpty()) item(key = "genres") {
        InfoBlock(R.string.genres) {
            Chips(media.genres)
            if (media.tags.isNotEmpty()) {
                Text(
                    stringResource(R.string.tags),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
                )
                Chips(media.tags.take(MAX_TAGS))
            }
        }
    }
    item(key = "titles") {
        InfoBlock(R.string.tv_info_titles) {
            Fact(stringResource(R.string.name_romaji), media.nameRomaji, Modifier.padding(vertical = 5.dp))
            media.name?.takeIf { it.isNotBlank() && it != media.nameRomaji }?.let {
                Fact(stringResource(R.string.tv_info_english), it, Modifier.padding(vertical = 5.dp))
            }
            if (media.synonyms.isNotEmpty()) Fact(stringResource(R.string.synonyms), media.synonyms.joinToString("\n"), Modifier.padding(vertical = 5.dp))
        }
    }
    media.relations?.filter { it.anime != null || it.manga != null }?.takeIf { it.isNotEmpty() }?.let { relations ->
        item(key = "relations") {
            CardRow(R.string.relations) {
                items(relations, key = { it.id }) { related ->
                    PosterCard(
                        media = related,
                        onClick = { onOpen(related) },
                        onFocused = {},
                        subtitle = related.relation?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.titlecase() },
                    )
                }
            }
        }
    }
    media.characters?.takeIf { it.isNotEmpty() }?.let { characters ->
        item(key = "characters") {
            CardRow(R.string.characters) {
                items(characters, key = { it.id }) { character ->
                    Portrait(character.image, character.name.orEmpty(), character.role.lowercase().replaceFirstChar { it.titlecase() })
                }
            }
        }
    }
    media.staff?.takeIf { it.isNotEmpty() }?.let { staff ->
        item(key = "staff") {
            CardRow(R.string.staff) {
                items(staff, key = { "${it.id}-${it.role}" }) { person ->
                    Portrait(person.image, person.name.orEmpty(), person.role.orEmpty())
                }
            }
        }
    }
    media.recommendations?.filter { it.anime != null }?.takeIf { it.isNotEmpty() }?.let { recommended ->
        item(key = "recommended") {
            CardRow(R.string.recommended) {
                items(recommended, key = { it.id }) { rec ->
                    PosterCard(media = rec, onClick = { onOpen(rec) }, onFocused = {})
                }
            }
        }
    }
}

private const val MAX_TAGS = 20
private val ACRONYMS = setOf("TV", "OVA", "ONA")

/** The facts the phone's info page lists, those this title has, each as label and value. */
@Composable
private fun facts(media: Media): List<Pair<String, String>> {
    val anime = media.anime
    // "LIGHT_NOVEL" → "Light novel", but "TV_SHORT" → "TV short": the acronyms stay as they are.
    fun pretty(raw: String) = raw.split('_').mapIndexed { i, word ->
        if (word in ACRONYMS) word else word.lowercase().let { if (i == 0) it.replaceFirstChar { c -> c.titlecase() } else it }
    }.joinToString(" ")
    val season = anime?.season?.let { season ->
        val year = anime.seasonYear ?: media.startDate?.year
        when (season) {
            "WINTER" -> R.string.tv_season_WINTER
            "SPRING" -> R.string.tv_season_SPRING
            "SUMMER" -> R.string.tv_season_SUMMER
            "FALL" -> R.string.tv_season_FALL
            else -> null
        }?.let { if (year != null) stringResource(it, year) else null }
    }
    val next = anime?.nextAiringEpisode?.let { episode ->
        anime.nextAiringEpisodeTime?.let { at ->
            val seconds = at - System.currentTimeMillis() / 1000
            if (seconds > 0) stringResource(R.string.tv_info_next_in, episode.toString(), untilText(seconds)) else null
        }
    }
    return listOfNotNull(
        media.format?.let { stringResource(R.string.format) to pretty(it) },
        airingStatus(media)?.let { stringResource(R.string.tv_info_status) to it },
        anime?.totalEpisodes?.let { stringResource(R.string.total_eps) to it.toString() },
        anime?.episodeDuration?.let { stringResource(R.string.tv_info_duration) to stringResource(R.string.tv_minutes, it) },
        next?.let { stringResource(R.string.tv_info_next) to it },
        season?.let { stringResource(R.string.season) to it },
        media.startDate?.takeIf { !it.isEmpty() }?.let { stringResource(R.string.start_date) to it.toStringOrEmpty() },
        media.endDate?.takeIf { !it.isEmpty() }?.let { stringResource(R.string.end_date) to it.toStringOrEmpty() },
        anime?.mainStudio?.name?.let { stringResource(R.string.main_studio) to it },
        media.source?.let { stringResource(R.string.source) to pretty(it) },
        media.meanScore?.let { stringResource(R.string.mean_score) to "$it%" },
        media.popularity?.let { stringResource(R.string.popularity) to "%,d".format(it) },
        media.favourites?.let { stringResource(R.string.favourites) to "%,d".format(it) },
    )
}

/** "3 d 4 h": how long until the next episode, at most two units. */
private fun untilText(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days} d ${hours} h"
        hours > 0 -> "${hours} h ${minutes} min"
        else -> "${minutes.coerceAtLeast(1)} min"
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(values: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * A titled block of text the D-pad can stop on: outlined while focused, so it's clear where the
 * remote is while reading.
 */
@Composable
private fun InfoBlock(title: Int, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier
            .padding(end = 48.dp, top = 8.dp)
            .fillMaxWidth()
            .border(
                BorderStroke(FOCUS_OUTLINE, if (focused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
                CARD_SHAPE,
            )
            .focusable(interactionSource = interaction)
            .padding(16.dp)
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

/** A titled row of cards, as the home's are. */
@Composable
private fun CardRow(title: Int, content: LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
        FocusRestingAt(8.dp) {
            LazyRow(
                contentPadding = PaddingValues(start = 8.dp, end = 48.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}

/** A character or a member of staff: a round picture, the name, and their role beneath. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Portrait(image: String?, name: String, role: String) {
    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = {},
            modifier = Modifier.size(96.dp),
            shape = CardDefaults.shape(CircleShape),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CircleShape),
            ),
        ) {
            GlideImage(model = image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (role.isNotBlank()) Text(
            role,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
