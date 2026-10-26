package ani.dantotsu.others

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.databinding.ActivityAppUpdateBinding
import ani.dantotsu.formatBytes
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.logError
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.px
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.card.MaterialCardView
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Full screen counterpart to the update bottom sheet: shows the release's patch notes and drives
 * the download (progress, size, speed and ETA). The download itself runs in [AppUpdateService], so
 * leaving this screen leaves it going behind its notification; this activity only renders whatever
 * [AppUpdateDownloader] currently reports.
 */
class AppUpdateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppUpdateBinding

    private lateinit var version: String
    private lateinit var repo: String
    private var changelog: String = ""

    /** APK size looked up before the download starts; 0 until (and unless) the probe answers. */
    private var probedSize = 0L

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        repo = intent.getStringExtra(EXTRA_REPO) ?: getString(R.string.repo)
        changelog = intent.getStringExtra(EXTRA_CHANGELOG).orEmpty()
        if (version.isEmpty()) {
            finish()
            return
        }

        // The globals can be a resource-dimension guess (a three-button bar's height under gesture
        // navigation, say) when this is the first screen opened, as from the update notification;
        // the real insets replace them once they arrive. Padding rather than margin, so the screen's
        // background runs under the bars instead of leaving the window's showing there.
        val actionsBottom = binding.appUpdateActions.paddingBottom
        binding.appUpdateLayout.updatePadding(top = statusBarHeight)
        binding.appUpdateActions.updatePadding(bottom = actionsBottom + navBarHeight)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appUpdateLayout) { _, insets ->
            val status = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            if (status > 0) statusBarHeight = status
            navBarHeight = nav
            binding.appUpdateLayout.updatePadding(top = status.takeIf { it > 0 } ?: statusBarHeight)
            binding.appUpdateActions.updatePadding(bottom = actionsBottom + nav)
            insets
        }
        binding.appUpdateBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Here is where the "update available" notification leads, however it was reached; its
        // View action doesn't dismiss it on its own the way tapping the notification does.
        cancelNotification(Notifications.ID_APP_UPDATE_PROMPT)

        binding.appUpdateCurrentVersion.text = BuildConfig.VERSION_NAME
        binding.appUpdateVersion.text = version
        (binding.appUpdateLogo.drawable as? Animatable)?.start()
        // Only the update check knows the date; reopened from the download notification, it's 0.
        val releasedAt = intent.getLongExtra(EXTRA_RELEASED_AT, 0)
        if (releasedAt > 0) {
            binding.appUpdateReleased.visibility = View.VISIBLE
            binding.appUpdateReleased.text = getString(
                R.string.update_released,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(releasedAt))
            )
        }
        binding.appUpdateGithub.setOnClickListener {
            openLinkInBrowser("https://github.com/$repo/releases")
        }

        renderPatchNotes()

        binding.appUpdateCancel.setOnClickListener { AppUpdateDownloader.cancel(this) }
        binding.appUpdateAction.setOnClickListener {
            when (AppUpdateDownloader.stateOf(this, version).status) {
                UpdateStatus.READY -> install()
                UpdateStatus.DOWNLOADING -> {}
                else -> AppUpdateDownloader.start(this, version, changelog, repo)
            }
        }

        val initial = AppUpdateDownloader.stateOf(this, version)
        render(initial)
        if (initial.status == UpdateStatus.IDLE || initial.status == UpdateStatus.FAILED) probeSize()
        lifecycleScope.launch {
            AppUpdateDownloader.state.collectLatest {
                // The flow also carries downloads for other versions; stateOf() narrows to ours.
                render(AppUpdateDownloader.stateOf(this@AppUpdateActivity, version))
            }
        }
    }

    private fun renderPatchNotes() {
        val themed = object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .headingBreakHeight(0)
                    .headingTextSizeMultipliers(floatArrayOf(1.35f, 1.2f, 1.1f, 1f, 1f, 1f))
                    .bulletWidth(5f.px)
                    .listItemColor(getThemeColor(androidx.appcompat.R.attr.colorPrimary))
                    .blockMargin(16f.px)
            }
        }
        val markwon = try {
            buildMarkwon(this, false, plugins = listOf(themed))
        } catch (e: IllegalArgumentException) {
            binding.appUpdatePatchNotes.text = changelog
            return
        }
        val notes = parseSections(changelog)
        if (notes == null) {
            markwon.setMarkdown(binding.appUpdatePatchNotes, changelog)
            return
        }
        if (notes.intro.isBlank()) binding.appUpdatePatchNotes.visibility = View.GONE
        else markwon.setMarkdown(binding.appUpdatePatchNotes, notes.intro)
        notes.sections.forEach { binding.appUpdateNotes.addView(sectionCard(it, markwon)) }
    }

    private class Item(val depth: Int, val text: StringBuilder)
    private class Section(val title: String, val items: List<Item>)
    private class Notes(val intro: String, val sections: List<Section>)

    /**
     * Releases are written as a bullet per section with the items nested under it:
     *
     * ```
     * - **Changes:**
     *   - Added a thing
     * ```
     *
     * Rendered as markdown that is a bulleted title with every item indented twice, and Markwon's
     * block spacing leaves each title as far from its own items as from the section before. So the
     * sections are pulled out here and drawn as cards. Null when the changelog isn't in that shape.
     */
    private fun parseSections(raw: String): Notes? {
        val title = Regex("""^ {0,3}[-*+] +\*\*\s*(.+?)\s*:?\s*\*\*:?\s*$""")
        val bullet = Regex("""^( *)[-*+] +(.*)$""")
        val lines = raw.replace("\r\n", "\n").lines()
        if (lines.none { title.matches(it) }) return null

        val intro = StringBuilder()
        val sections = mutableListOf<Section>()
        var items: MutableList<Item>? = null
        var baseIndent = -1
        for (line in lines) {
            val heading = title.find(line)
            val item = bullet.find(line)
            when {
                heading != null -> {
                    items = mutableListOf()
                    sections += Section(heading.groupValues[1], items)
                    baseIndent = -1
                }
                items == null -> intro.append(line).append('\n')
                line.isBlank() -> {}
                item != null -> {
                    val indent = item.groupValues[1].length
                    // Depth counts from the section's first item, so deeper nesting is kept.
                    if (baseIndent < 0) baseIndent = indent
                    val depth = ((indent - baseIndent).coerceAtLeast(0) + 1) / 2
                    items += Item(depth.coerceAtMost(2), StringBuilder(item.groupValues[2].trim()))
                }
                // A wrapped line continues the item above it.
                else -> items.lastOrNull()?.text?.append(' ')?.append(line.trim())
                    ?: intro.append(line).append('\n')
            }
        }
        return Notes(intro.toString().trim(), sections.filter { it.items.isNotEmpty() })
    }

    /** One section as a filled card: an accent title, then its items with dot bullets. */
    private fun sectionCard(section: Section, markwon: io.noties.markwon.Markwon): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16f.px, 14f.px, 16f.px, 12f.px)
        }
        body.addView(TextView(this).apply {
            text = section.title
            textSize = 16f
            typeface = ResourcesCompat.getFont(this@AppUpdateActivity, R.font.poppins_bold)
            setTextColor(getThemeColor(androidx.appcompat.R.attr.colorPrimary))
        })
        section.items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(item.depth * 16f.px, 6f.px, 0, 0)
            }
            row.addView(TextView(this).apply {
                text = if (item.depth == 0) "•" else "◦"
                textSize = 14f
                setTextColor(getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            }, LinearLayout.LayoutParams(14f.px, WRAP_CONTENT))
            row.addView(TextView(this).apply {
                textSize = 14f
                typeface = ResourcesCompat.getFont(this@AppUpdateActivity, R.font.poppins)
                setLineSpacing(2f.px.toFloat(), 1f)
                setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                markwon.setMarkdown(this, item.text.toString())
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            body.addView(row)
        }
        return MaterialCardView(this, null, com.google.android.material.R.attr.materialCardViewFilledStyle).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12f.px }
        }
    }

    /** Looks up the APK's size so it can be shown above the button before downloading. */
    private fun probeSize() {
        lifecycleScope.launch {
            val size = AppUpdateDownloader.probeSize(repo, version)
            if (size <= 0) return@launch
            probedSize = size
            render(AppUpdateDownloader.stateOf(this@AppUpdateActivity, version))
        }
    }

    private fun render(download: UpdateDownload) {
        val downloading = download.status == UpdateStatus.DOWNLOADING
        val action = binding.appUpdateAction
        binding.appUpdateCancel.visibility = if (downloading) View.VISIBLE else View.GONE
        // Mid-download the control is a progress bar, so it stops taking taps; cancel is the X.
        action.isClickable = !downloading
        action.setDownloading(downloading)

        when (download.status) {
            UpdateStatus.IDLE -> {
                binding.appUpdateSize.visibility =
                    if (probedSize > 0) View.VISIBLE else View.GONE
                if (probedSize > 0) binding.appUpdateSize.text =
                    getString(R.string.update_download_size, formatBytes(probedSize))
                action.setContent(
                    getString(R.string.download_update),
                    R.drawable.ic_download_tray_24,
                    R.drawable.ic_download_arrow_24
                )
                action.setPercentText(null)
                action.setProgress(0, animate = false)
            }

            UpdateStatus.DOWNLOADING -> {
                // Only the total size stays up here; the percentage and the fill carry the rest.
                val known = download.bytesTotal > 0
                binding.appUpdateSize.visibility = if (known) View.VISIBLE else View.GONE
                if (known) binding.appUpdateSize.text =
                    getString(R.string.update_download_size, formatBytes(download.bytesTotal))
                action.setContent(
                    getString(R.string.update_downloading),
                    R.drawable.ic_download_tray_24,
                    R.drawable.ic_download_arrow_24
                )
                action.setPercentText(
                    if (known) getString(R.string.update_percent, download.percent) else null
                )
                action.setProgress(download.percent)
            }

            UpdateStatus.READY -> {
                binding.appUpdateSize.visibility = View.VISIBLE
                binding.appUpdateSize.text = getString(
                    R.string.update_ready_to_install,
                    formatBytes(AppUpdateDownloader.apkFile(this, version).length())
                )
                action.setContent(
                    getString(R.string.install_update),
                    R.drawable.ic_round_system_update_24
                )
                action.setPercentText(null)
                action.setProgress(0, animate = false)
            }

            UpdateStatus.FAILED -> {
                binding.appUpdateSize.visibility = View.VISIBLE
                binding.appUpdateSize.setText(R.string.update_download_failed)
                action.setContent(getString(R.string.retry), R.drawable.ic_round_refresh_24)
                action.setPercentText(null)
                action.setProgress(0, animate = false)
            }
        }
    }

    private fun install() {
        try {
            startActivity(
                AppUpdateDownloader.installIntent(
                    this, AppUpdateDownloader.apkFile(this, version)
                )
            )
        } catch (e: Exception) {
            logError(e)
        }
    }

    companion object {
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_CHANGELOG = "changelog"
        private const val EXTRA_REPO = "repo"
        private const val EXTRA_RELEASED_AT = "releasedAt"

        fun newIntent(
            context: Context,
            version: String,
            changelog: String,
            repo: String,
            releasedAt: Long = 0,
        ): Intent =
            Intent(context, AppUpdateActivity::class.java)
                .putExtra(EXTRA_VERSION, version)
                .putExtra(EXTRA_CHANGELOG, changelog)
                .putExtra(EXTRA_REPO, repo)
                .putExtra(EXTRA_RELEASED_AT, releasedAt)
    }
}
