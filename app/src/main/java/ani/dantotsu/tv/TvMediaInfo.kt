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
import androidx.compose.runtime.setValue
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
fun LazyListScope.tvMediaInfo(
    media: Media,
    onOpen: (Media) -> Unit,
    themes: List<ani.dantotsu.connections.animethemes.AnimeThemeTrack>? = null,
) {
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
    // Where AniList says it streams: its streaming links only, not the official site or socials.
    // One a site, the first of each (English ones come first).
    val streaming = media.externalLinks
        .filter { it.getOrNull(3) == "STREAMING" }
        .mapNotNull { link ->
            val name = link.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = link.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            name to url
        }
        .distinctBy { it.first }
    if (streaming.isNotEmpty()) item(key = "available") { AvailableOn(streaming) }
    // The trailer, then the openings and endings, as the phone's info page has them.
    if (media.trailer != null || !themes.isNullOrEmpty()) item(key = "clips") {
        Clips(media.trailer, themes.orEmpty(), media.banner ?: media.cover)
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
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Portrait(character.image, character.name.orEmpty(), character.role.lowercase().replaceFirstChar { it.titlecase() }) {
                        TvPersonActivity.open(context, character)
                    }
                }
            }
        }
    }
    // The studio first, then the author when the staff list doesn't already have them, then the staff.
    val studio = media.anime?.mainStudio
    val author = (media.anime?.author ?: media.manga?.author)?.takeIf { a -> media.staff.orEmpty().none { it.id == a.id } }
    val staff = listOfNotNull(author) + media.staff.orEmpty().distinctBy { "${it.id}-${it.role}" }
    if (studio != null || staff.isNotEmpty()) item(key = "staff") {
        val context = androidx.compose.ui.platform.LocalContext.current
        CardRow(R.string.staff) {
            if (studio != null) item(key = "studio") {
                // AniList has no picture of a studio; MAL has its logo, found by name.
                val logo by androidx.compose.runtime.produceState<String?>(null, studio.name) {
                    value = ani.dantotsu.others.MalStudios.logo(context, studio.name)
                }
                Portrait(logo, studio.name, stringResource(R.string.tv_studio), placeholder = R.drawable.ic_round_animation_24, logo = true) {
                    TvPersonActivity.open(context, studio)
                }
            }
            items(staff, key = { "${it.id}-${it.role}" }) { person ->
                Portrait(person.image, person.name.orEmpty(), person.role.orEmpty()) { TvPersonActivity.open(context, person) }
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
internal fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
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
 * The streaming platforms, a button each: into the platform's own app when one here takes the
 * link, else its code for the phone to open it ([LinkQrDialog]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvailableOn(links: List<Pair<String, String>>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var onPhone by remember { androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null) }
    Column(Modifier.padding(end = 48.dp, top = 8.dp).fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.tv_info_available_on), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            links.forEach { link ->
                val open = { if (!openInApp(context, link.second)) onPhone = link }
                androidx.tv.material3.Button(
                    onClick = open,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.pointerClick(onClick = open),
                ) { Text(link.first, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
    onPhone?.let { (name, url) -> LinkQrDialog(name, url) { onPhone = null } }
}

/**
 * A titled block of text the D-pad can stop on: outlined while focused, so it's clear where the
 * remote is while reading.
 */
@Composable
internal fun InfoBlock(title: Int, content: @Composable () -> Unit) {
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
internal fun CardRow(title: Int, content: LazyListScope.() -> Unit) {
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
internal fun Portrait(
    image: String?,
    name: String,
    role: String,
    placeholder: Int? = null,
    /** A logo: fitted whole on a light disc, where a face is cropped to fill it. */
    logo: Boolean = false,
    onClick: () -> Unit = {},
) {
    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            modifier = Modifier.size(96.dp).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(CircleShape),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(FOCUS_OUTLINE, MaterialTheme.colorScheme.primary), shape = CircleShape),
            ),
        ) {
            if (image == null && placeholder != null) Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                androidx.tv.material3.Icon(
                    androidx.compose.ui.res.painterResource(placeholder),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            } else if (logo) Box(
                // Light whatever the theme: logos are mostly dark lettering on nothing.
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlideImage(
                    model = image,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    failure = placeholder?.let { com.bumptech.glide.integration.compose.placeholder(it) },
                )
            } else GlideImage(model = image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
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
