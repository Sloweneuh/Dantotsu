package ani.dantotsu.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import ani.dantotsu.R
import ani.dantotsu.connections.handoff.HandoffQr

/**
 * Opens [url] in an installed app that claims it (a streaming platform's own), never in a
 * browser: a TV seldom has one, and when it does a site made for a mouse is no use from the
 * couch. False when no such app is there, for the caller to offer the phone instead.
 */
fun openInApp(context: Context, url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val intent = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // The system's own "an app for this link, or nothing".
        intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
    } else {
        // Before it: whatever takes this link but not any link at all.
        val pm = context.packageManager
        fun takers(i: Intent) = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
        val browsers = takers(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE))
        val app = (takers(intent) - browsers - context.packageName).firstOrNull() ?: return false
        intent.setPackage(app)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** No app here takes the link: its code, for the phone's camera to open it there. */
@Composable
fun LinkQrDialog(name: String, url: String, onDismiss: () -> Unit) {
    val qr = remember(url) { HandoffQr.encode(url, 600)?.asImageBitmap() }
    val close = remember { FocusRequester() }
    TvDialog(onDismissRequest = onDismiss) {
        Surface(shape = CARD_SHAPE) {
            Row(
                Modifier.padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(340.dp)) {
                    Text(stringResource(R.string.tv_link_on_phone, name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.tv_link_on_phone_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.padding(top = 20.dp).focusRequester(close).pointerClick(onClick = onDismiss),
                    ) { Text(stringResource(R.string.close)) }
                }
                // Dark on white whatever the theme: a scanner needs the contrast.
                Box(
                    Modifier.size(260.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (qr != null) Image(qr, contentDescription = url, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { close.requestFocus() }
    }
}
