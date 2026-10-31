package ani.dantotsu.tv

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import ani.dantotsu.R
import com.google.android.material.color.MaterialColors

/**
 * Dantotsu's own look on the TV: the colours the app's theme resolves to (so the user's chosen theme,
 * OLED variant and all, carries over) and Poppins, sized for a screen across the room.
 *
 * [fill]: the whole screen, as TV screens are; off for TV content inside something else (a sheet).
 */
@Composable
fun TvTheme(rateLimit: Boolean = true, fill: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = remember(context) { themeColors(context) }
    MaterialTheme(colorScheme = colors, typography = TvTypography) {
        // Text outside a Surface took the library's default (near black) on the dark background.
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalDensity provides tvDensity(),
        ) {
            androidx.compose.foundation.layout.Box((if (fill) Modifier.fillMaxSize() else Modifier).tvInputTracking()) {
                content()
                // Over every TV screen but the player, as the phone's pill keeps off the player too.
                if (rateLimit) TvRateLimitIndicator(Modifier.align(Alignment.BottomEnd).padding(28.dp))
            }
        }
    }
}

/**
 * A dialog drawn at TV mode's scale. A dialog is a window of its own, and its content got the
 * system's density back — every dialog came out smaller than the screen it opened over.
 */
@Composable
fun TvDialog(onDismissRequest: () -> Unit, fullWidth: Boolean = false, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        // The platform's width cap is in its own dp, which no longer match the dialog's.
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            if (fullWidth) androidx.compose.foundation.layout.Box(Modifier.tvInputTracking()) { content() }
            else androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().tvInputTracking().pointerClick(hoverFocus = false, onClick = onDismissRequest),
                contentAlignment = Alignment.Center,
            ) {
                // A click on the dialog itself, not a button, mustn't fall through and close it.
                androidx.compose.foundation.layout.Box(Modifier.pointerClick(hoverFocus = false) {}) { content() }
            }
        }
    }
}

/**
 * The width TV mode is laid out for. A TV gives an app 960dp (1920 px at its usual density), seen
 * from across the room; everywhere else TV mode is switched on — a desktop window, a tablet — the
 * screen is closer, so the same layout gets a wider canvas and comes out smaller. Either way every
 * screen scales to fill its width, rather than sitting small in the middle of a large one.
 */
private const val TV_CANVAS_WIDTH_DP = 960f
private const val NEAR_CANVAS_WIDTH_DP = 1280f

@Composable
private fun tvDensity(): Density {
    val configuration = LocalConfiguration.current
    val system = LocalDensity.current
    val context = LocalContext.current
    val canvas = if (TvMode.isTvDevice(context)) TV_CANVAS_WIDTH_DP else NEAR_CANVAS_WIDTH_DP
    val widthPx = configuration.screenWidthDp * system.density
    val scaled = (widthPx / canvas).coerceAtLeast(0.5f)
    return remember(scaled) { Density(scaled, fontScale = 1f) }
}

private fun themeColors(context: Context): ColorScheme {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    fun attr(id: Int, fallback: Long) = Color(MaterialColors.getColor(context, id, fallback.toInt()))
    val primary = attr(androidx.appcompat.R.attr.colorPrimary, 0xFFFF8A00)
    val onPrimary = attr(com.google.android.material.R.attr.colorOnPrimary, 0xFF000000)
    val background = attr(android.R.attr.colorBackground, if (night) 0xFF1C1B1E else 0xFFFFFBFF)
    val onBackground = attr(com.google.android.material.R.attr.colorOnBackground, if (night) 0xFFE6E1E5 else 0xFF1C1B1E)
    val surface = attr(com.google.android.material.R.attr.colorSurface, if (night) 0xFF1C1B1E else 0xFFFFFBFF)
    val onSurface = attr(com.google.android.material.R.attr.colorOnSurface, if (night) 0xFFE6E1E5 else 0xFF1C1B1E)
    val surfaceVariant = attr(com.google.android.material.R.attr.colorSurfaceVariant, if (night) 0xFF49454F else 0xFFE7E0EC)
    val onSurfaceVariant = attr(com.google.android.material.R.attr.colorOnSurfaceVariant, if (night) 0xFFCAC4D0 else 0xFF49454F)
    val secondary = attr(com.google.android.material.R.attr.colorSecondary, 0xFFFF8A00)
    val onSecondary = attr(com.google.android.material.R.attr.colorOnSecondary, 0xFF000000)
    return if (night) darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        secondary = secondary, onSecondary = onSecondary,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        border = primary,
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        secondary = secondary, onSecondary = onSecondary,
        background = background, onBackground = onBackground,
        surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
        border = primary,
    )
}

private val Poppins = FontFamily(
    Font(R.font.poppins, FontWeight.Normal),
    Font(R.font.poppins_semi_bold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

private val TvTypography = Typography(
    displaySmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Poppins, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Poppins, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Poppins, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)
