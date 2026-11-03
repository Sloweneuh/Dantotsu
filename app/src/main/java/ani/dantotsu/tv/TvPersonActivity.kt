package ani.dantotsu.tv

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.others.getSerialized
import ani.dantotsu.initActivity
import ani.dantotsu.media.Author
import ani.dantotsu.media.Character
import ani.dantotsu.media.Media
import ani.dantotsu.media.Studio
import ani.dantotsu.themes.ThemeManager
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.Serializable

/**
 * A character, a member of staff or a studio, on the TV, as the phone's pages have them: the
 * picture, name and facts, the description, then rows of what they're in — a character's titles
 * and voice actors, a person's works and the characters they voiced, a studio's works by year.
 * Every card opens its own page.
 */
class TvPersonActivity : AppCompatActivity(), TvScreen {

    private val character = androidx.compose.runtime.mutableStateOf<Character?>(null)
    private val author = androidx.compose.runtime.mutableStateOf<Author?>(null)
    private val studio = androidx.compose.runtime.mutableStateOf<Studio?>(null)
    private val loading = androidx.compose.runtime.mutableStateOf(true)

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(TvMode.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager(this).applyTheme()
        super.onCreate(savedInstanceState)
        initActivity(this)
        character.value = intent.getSerialized(EXTRA_CHARACTER)
        author.value = intent.getSerialized(EXTRA_AUTHOR)
        studio.value = intent.getSerialized(EXTRA_STUDIO)
        if (character.value == null && author.value == null && studio.value == null) return finish()
        setContent {
            TvTheme {
                val opener = Opener(this)
                when {
                    character.value != null -> CharacterPage(character.value!!, loading.value, opener)
                    author.value != null -> AuthorPage(author.value!!, loading.value, opener)
                    studio.value != null -> StudioPage(studio.value!!, loading.value, opener)
                }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                character.value?.let { character.value = Anilist.query.getCharacterDetails(it) }
                author.value?.let { author.value = Anilist.query.getAuthorDetails(it) }
                studio.value?.let { studio.value = Anilist.query.getStudioDetails(it) }
            }
            loading.value = false
        }
    }

    companion object {
        private const val EXTRA_CHARACTER = "character"
        private const val EXTRA_AUTHOR = "author"
        private const val EXTRA_STUDIO = "studio"

        fun open(context: Context, character: Character) =
            context.startActivity(Intent(context, TvPersonActivity::class.java).putExtra(EXTRA_CHARACTER, character as Serializable))

        fun open(context: Context, author: Author) =
            context.startActivity(Intent(context, TvPersonActivity::class.java).putExtra(EXTRA_AUTHOR, author as Serializable))

        fun open(context: Context, studio: Studio) =
            context.startActivity(Intent(context, TvPersonActivity::class.java).putExtra(EXTRA_STUDIO, studio as Serializable))
    }
}

/** Where each kind of card goes. */
private class Opener(val context: Context) {
    fun media(media: Media) =
        context.startActivity(Intent(context, TvMediaActivity::class.java).putExtra("media", media as Serializable))
    fun character(character: Character) = TvPersonActivity.open(context, character)
    fun author(author: Author) = TvPersonActivity.open(context, author)
}

@Composable
private fun CharacterPage(character: Character, loading: Boolean, open: Opener) {
    val facts = listOfNotNull(
        character.age?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.tv_age) to it },
        character.gender?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.tv_gender) to it },
        character.dateOfBirth?.takeIf { !it.isEmpty() }?.toStringOrEmpty()?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.tv_birthday) to it },
    )
    // One voice actor once, whatever number of titles they voice the character in.
    val voices = character.voiceActor.orEmpty().distinctBy { it.id }
    PersonPage(
        image = character.image,
        round = true,
        name = character.name.orEmpty(),
        facts = facts,
        description = character.description,
        loading = loading,
    ) {
        character.roles?.takeIf { it.isNotEmpty() }?.let { roles ->
            item(key = "roles") {
                CardRow(R.string.tv_appears_in) {
                    items(roles.distinctBy { it.id }, key = { it.id }) { media ->
                        PosterCard(
                            media = media,
                            onClick = { open.media(media) },
                            onFocused = {},
                            subtitle = media.relation?.lowercase()?.replaceFirstChar { it.titlecase() },
                        )
                    }
                }
            }
        }
        if (voices.isNotEmpty()) item(key = "voices") {
            CardRow(R.string.tv_voice_actors) {
                items(voices, key = { it.id }) { person ->
                    Portrait(person.image, person.name.orEmpty(), person.role.orEmpty()) { open.author(person) }
                }
            }
        }
    }
}

@Composable
private fun AuthorPage(author: Author, loading: Boolean, open: Opener) {
    val facts = listOfNotNull(
        author.age?.let { stringResource(R.string.tv_age) to it.toString() },
        author.dateOfBirth?.let { stringResource(R.string.tv_birthday) to it },
        author.dateOfDeath?.let { stringResource(R.string.tv_date_of_death) to it },
        author.homeTown?.let { stringResource(R.string.tv_home_town) to it },
        author.yearsActive?.takeIf { it.isNotEmpty() }?.let { years ->
            stringResource(R.string.tv_years_active) to if (years.size == 1) "${years[0]} –" else years.joinToString(" – ")
        },
    )
    PersonPage(
        image = author.image,
        round = true,
        name = author.name.orEmpty(),
        facts = facts,
        description = author.description,
        loading = loading,
    ) {
        author.character?.takeIf { it.isNotEmpty() }?.let { characters ->
            item(key = "characters") {
                CardRow(R.string.tv_characters_voiced) {
                    items(characters.distinctBy { it.id }, key = { it.id }) { character ->
                        Portrait(character.image, character.name.orEmpty(), "") { open.character(character) }
                    }
                }
            }
        }
        worksByYear(author.yearMedia, open, roleOf = { it.relation })
    }
}

@Composable
private fun StudioPage(studio: Studio, loading: Boolean, open: Opener) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // AniList has no picture of a studio; MAL has its logo, found by name.
    val logo = androidx.compose.runtime.produceState(studio.imageUrl, studio.name) {
        if (value == null) value = ani.dantotsu.others.MalStudios.logo(context, studio.name)
    }.value
    PersonPage(
        image = logo,
        logo = true,
        round = false,
        name = studio.name,
        facts = listOfNotNull(studio.favourites?.let { stringResource(R.string.favourites) to "%,d".format(it) }),
        description = null,
        loading = loading,
    ) {
        worksByYear(studio.yearMedia, open, roleOf = { null })
    }
}

/** A row a year, newest first (the order AniList gives), "TBA" and cancelled last as it puts them. */
private fun androidx.compose.foundation.lazy.LazyListScope.worksByYear(
    byYear: Map<String, List<Media>>?,
    open: Opener,
    roleOf: (Media) -> String?,
) {
    byYear.orEmpty().forEach { (year, works) ->
        if (works.isEmpty()) return@forEach
        item(key = "year-$year") {
            YearRow(if (year == "CANCELLED") stringResource(R.string.tv_cancelled) else year) {
                items(works.distinctBy { "${it.id}-${roleOf(it)}" }, key = { "${it.id}-${roleOf(it)}" }) { media ->
                    PosterCard(media = media, onClick = { open.media(media) }, onFocused = {}, subtitle = roleOf(media))
                }
            }
        }
    }
}

@Composable
private fun YearRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
        FocusRestingAt(8.dp) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(start = 8.dp, end = 48.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}

/**
 * The page all three share: picture beside name and facts at the top, the description beneath as
 * a block the D-pad stops on, then [rows]. Spoilers in AniList's descriptions stay hidden.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun PersonPage(
    image: String?,
    logo: Boolean = false,
    round: Boolean,
    name: String,
    facts: List<Pair<String, String>>,
    description: String?,
    loading: Boolean,
    rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val top = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { top.requestFocus() }
    }
    val text = remember(description) { description?.let(::plainText)?.takeIf { it.isNotBlank() } }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // A logo makes no backdrop: blown up and blurred, it's a smear.
        Backdrop(image.takeIf { !logo }, Modifier.fillMaxSize())
        // The picture and name can't take focus, so focus coming back up to the description takes
        // the page to its very top, as the media page does: resting it at the usual line left the
        // picture and name above it scrolled out of reach.
        val pageState = androidx.compose.foundation.lazy.rememberLazyListState()
        val restPx = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
        // Known by its own focus rather than by where the list has it: with many rows (a studio's
        // years, a person's works) the top section was already out of the list's window by the time
        // the description took focus coming back up, wasn't recognised, and rested at the usual line.
        val aboutFocused = remember { androidx.compose.runtime.mutableStateOf(false) }
        // How far the description sits below the list's top: the top padding, the picture and
        // name, and the gap between them.
        val aboveAboutPx = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val pageSpec = remember(restPx) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                    // Focus the mouse gave stays where it is, as in FocusRestingAt: scrolled up with
                    // the wheel, the row arriving under the pointer took focus and pulled the page back.
                    TvPointer.drivesFocus -> 0f
                    aboutFocused.value -> offset - aboveAboutPx.floatValue
                    else -> offset - restPx
                }
            }
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pageSpec) {
            LazyColumn(
                state = pageState,
                contentPadding = PaddingValues(start = 48.dp, top = 36.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "head") { Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 48.dp).onSizeChanged {
                            aboveAboutPx.floatValue = it.height + with(density) { (36.dp + 20.dp).toPx() }
                        },
                    ) {
                        if (logo) Box(
                            // Light whatever the theme: logos are mostly dark lettering on nothing.
                            Modifier.size(140.dp).clip(CARD_SHAPE).background(androidx.compose.ui.graphics.Color.White).padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlideImage(
                                model = image,
                                contentDescription = name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                                failure = com.bumptech.glide.integration.compose.placeholder(R.drawable.ic_round_animation_24),
                            )
                        } else GlideImage(
                            model = image,
                            contentDescription = name,
                            contentScale = if (round) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.size(140.dp).clip(if (round) CircleShape else CARD_SHAPE),
                        )
                        Column(Modifier.padding(start = 28.dp)) {
                            Text(name, style = MaterialTheme.typography.headlineMedium)
                            // Wrapping onto a second line when there are many (a person's five).
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            if (facts.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                                Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(32.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                facts.forEach { (label, value) -> Fact(label, value) }
                            }
                        }
                    }
                    Box(Modifier.focusRequester(top).onFocusChanged { aboutFocused.value = it.hasFocus }) {
                        InfoBlock(R.string.tv_about) {
                            Text(
                                text ?: stringResource(if (loading) R.string.loading else R.string.tv_no_description),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } }
                rows()
                if (loading) item(key = "loading") {
                    Text(stringResource(R.string.loading), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * AniList's description as plain text: HTML read, its markdown links and emphasis stripped, and
 * spoilers (~! … !~) left out.
 */
private fun plainText(raw: String): String {
    val noSpoilers = raw.replace(Regex("~!.*?!~", RegexOption.DOT_MATCHES_ALL), "")
    val noLinks = noSpoilers.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
    val text = HtmlCompat.fromHtml(noLinks.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(Regex("__(.*?)__"), "$1").replace(Regex("(?<![\\w*])\\*\\*?([^*]+)\\*\\*?"), "$1")
        .replace(Regex("\n{3,}"), "\n\n").trim()
}
