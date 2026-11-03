package ani.dantotsu.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.connections.discord.PresenceSettings
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage

/** Bumped by every presence setting as it changes, so [DiscordPresencePreview] draws it afresh. */
internal val discordPreviewVersion = mutableIntStateOf(0)

/**
 * The presence as a Discord profile shows it, with the settings above applied: what the phone's
 * Discord sheet previews. In Discord's own dark colours whatever the theme, as it looks there. The
 * buttons read as Discord shows them, in English — that's what the presence sends.
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun DiscordPresencePreview(modifier: Modifier = Modifier) {
    discordPreviewVersion.intValue
    val mediaMode = PresenceSettings.mode == PresenceSettings.MODE_MEDIA
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // About as wide as Discord's own profile card, not the settings column's whole width.
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(DISCORD_CARD)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .padding(16.dp)
    ) {
        Text(
            stringResource(R.string.discord_current_activity).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = DISCORD_MUTED,
        )
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(80.dp)) {
                GlideImage(
                    model = R.mipmap.ic_launcher,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                )
                if (PresenceSettings.showSiteIcon) GlideImage(
                    model = if (mediaMode) Discord.small_Image_AniList else Discord.small_Image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(6.dp, 6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(DISCORD_CARD)
                        .padding(3.dp)
                        .clip(CircleShape),
                )
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    stringResource(R.string.tv_discord_preview_watching, PREVIEW_TITLE),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(stringResource(R.string.tv_discord_preview_details), style = MaterialTheme.typography.bodySmall, color = DISCORD_TEXT)
                Text("Episode 1/28", style = MaterialTheme.typography.bodySmall, color = DISCORD_TEXT)
                // The elapsed bar Discord draws from the timestamps.
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("08:12", style = MaterialTheme.typography.labelSmall, color = DISCORD_MUTED)
                    Box(Modifier.padding(horizontal = 8.dp).width(160.dp).height(4.dp).background(DISCORD_TRACK, RoundedCornerShape(50))) {
                        Box(Modifier.width(56.dp).height(4.dp).background(Color.White, RoundedCornerShape(50)))
                    }
                    Text("24:00", style = MaterialTheme.typography.labelSmall, color = DISCORD_MUTED)
                }
            }
        }
        val buttons = listOfNotNull(
            "View Anime on AniList".takeIf { PresenceSettings.showMediaButton },
            "View Profile".takeIf { PresenceSettings.showProfile },
        )
        if (buttons.isNotEmpty()) Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buttons.forEach { label ->
                Box(
                    Modifier.fillMaxWidth().background(DISCORD_BUTTON, RoundedCornerShape(4.dp)).padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

private const val PREVIEW_TITLE = "Frieren: Beyond Journey's End"
private val DISCORD_CARD = Color(0xFF232428)
private val DISCORD_TEXT = Color(0xFFDBDEE1)
private val DISCORD_MUTED = Color(0xFFB5BAC1)
private val DISCORD_TRACK = Color(0xFF4E5058)
private val DISCORD_BUTTON = Color(0xFF4E5058)
