package ani.dantotsu.others

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
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
import ani.dantotsu.px
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

        binding.appUpdateLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.appUpdateBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.appUpdateVersion.text =
            getString(R.string.update_version_change, BuildConfig.VERSION_NAME, version)

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
                    .headingBreakHeight(0) // no rule under the section titles
                    .headingTextSizeMultipliers(floatArrayOf(1.35f, 1.2f, 1.1f, 1f, 1f, 1f))
                    .bulletWidth(5f.px)
                    .listItemColor(getThemeColor(com.google.android.material.R.attr.colorSecondary))
                    .blockMargin(20f.px)
            }
        }
        val markwon = try {
            buildMarkwon(this, false, plugins = listOf(themed))
        } catch (e: IllegalArgumentException) {
            binding.appUpdatePatchNotes.text = changelog
            return
        }
        markwon.setMarkdown(binding.appUpdatePatchNotes, sectionsToHeadings(changelog))
    }

    /**
     * Releases are written as a bullet per section with the items nested under it:
     *
     * ```
     * - **Changes:**
     *   - Added a thing
     * ```
     *
     * Rendered literally that puts a bullet on the section title and indents every item twice, which
     * wastes most of a phone's width. This promotes those title bullets to headings and pulls their
     * items up a level. Anything that isn't in that shape is returned untouched.
     */
    private fun sectionsToHeadings(raw: String): String {
        val title = Regex("""^ {0,3}[-*+] +\*\*\s*(.+?)\s*:?\s*\*\*:?\s*$""")
        val lines = raw.replace("\r\n", "\n").lines()
        if (lines.none { title.matches(it) }) return raw

        val out = StringBuilder()
        var itemIndent = -1
        for (line in lines) {
            val match = title.find(line)
            when {
                match != null -> {
                    out.append("\n## ").append(match.groupValues[1]).append("\n\n")
                    itemIndent = -1
                }
                line.isBlank() -> out.append('\n')
                else -> {
                    val indent = line.takeWhile { it == ' ' }.length
                    // Dedent by the section's own first-item indent, so deeper nesting is kept.
                    if (indent > 0 && itemIndent < 0) itemIndent = indent
                    out.append(line.drop(minOf(indent, itemIndent.coerceAtLeast(0)))).append('\n')
                }
            }
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
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

        fun newIntent(context: Context, version: String, changelog: String, repo: String): Intent =
            Intent(context, AppUpdateActivity::class.java)
                .putExtra(EXTRA_VERSION, version)
                .putExtra(EXTRA_CHANGELOG, changelog)
                .putExtra(EXTRA_REPO, repo)
    }
}
