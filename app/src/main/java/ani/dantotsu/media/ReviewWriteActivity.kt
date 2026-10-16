package ani.dantotsu.media

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityReviewWriteBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.settings.enableSettingsLongPress
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.util.customAlertDialog
import tachiyomi.core.util.lang.launchIO

class ReviewWriteActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewWriteBinding
    private var mediaId: Int = -1
    private var score: Int = 50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityReviewWriteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AndroidBug5497Workaround.assistActivity(this) {}

        binding.reviewWriteToolbar.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.reviewMarkdownToolbar.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }
        binding.reviewWriteToolbar.formBarTitle.setText(R.string.review_write_title)
        binding.reviewWriteToolbar.formBarPrimary.apply {
            setText(R.string.form_action_post)
            setIconResource(R.drawable.ic_round_send_24)
        }

        mediaId = intent.getIntExtra("mediaId", -1)
        if (mediaId == -1) {
            toast(getString(R.string.error))
            finish()
            return
        }

        updateScoreLabel()
        binding.reviewScoreSeekBar.addOnChangeListener { _, value, _ ->
            score = value.toInt()
            updateScoreLabel()
        }

        // How far the body still is from AniList's minimum, as it's written.
        updateBodyHelper()
        binding.reviewBodyInput.doAfterTextChanged { updateBodyHelper() }

        binding.reviewWriteToolbar.formBarClose.enableSettingsLongPress()
        binding.reviewWriteToolbar.formBarClose.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.reviewWriteToolbar.formBarPrimary.setOnClickListener { submitReview() }

        setupMarkdownButtons()
    }

    private fun updateScoreLabel() {
        binding.reviewScoreLabel.text = getString(R.string.review_score_value, score)
    }

    private fun updateBodyHelper() {
        val missing = MIN_BODY_LENGTH - (binding.reviewBodyInput.text?.trim()?.length ?: 0)
        binding.reviewBodyLayout.helperText =
            if (missing > 0) getString(R.string.review_body_remaining, missing) else null
    }

    private fun submitReview() {
        val summary = binding.reviewSummaryInput.text?.toString()?.trim() ?: ""
        val body = binding.reviewBodyInput.text?.toString()?.trim() ?: ""
        val isPrivate = binding.reviewPrivateCheckbox.isChecked

        if (summary.length < 20) {
            toast(getString(R.string.review_summary_too_short))
            return
        }
        if (body.length < MIN_BODY_LENGTH) {
            toast(getString(R.string.review_body_too_short))
            return
        }

        customAlertDialog().apply {
            setTitle(R.string.warning)
            setMessage(R.string.post_to_anilist_warning)
            setPosButton(R.string.ok) {
                launchIO {
                    val result = Anilist.mutation.postReview(
                        summary = summary,
                        body = body,
                        mediaId = mediaId,
                        score = score,
                        isPrivate = isPrivate
                    )
                    toast(result)
                    if (!result.contains("error", ignoreCase = true)) {
                        finish()
                    }
                }
            }
            setNeutralButton(R.string.open_rules) {
                openLinkInBrowser("https://anilist.co/forum/thread/14")
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private fun setupMarkdownButtons() {
        val bodyInput = binding.reviewBodyInput

        fun applyFormat(prefix: String, suffix: String = prefix) {
            val start = bodyInput.selectionStart
            val end = bodyInput.selectionEnd
            val text = bodyInput.text ?: return
            if (start != end) {
                val selected = text.substring(start, end)
                text.replace(start, end, "$prefix$selected$suffix")
                bodyInput.setSelection(start + prefix.length, start + prefix.length + selected.length)
            } else {
                text.insert(start, "$prefix$suffix")
                bodyInput.setSelection(start + prefix.length)
            }
        }

        val bar = binding.reviewMarkdownToolbar
        // The shared bar carries every format the post composer has; a review wires these.
        listOf(
            bar.formatTitle, bar.formatCode, bar.formatListUnordered, bar.formatListOrdered,
            bar.formatImage, bar.formatYoutube, bar.formatVideo,
        ).forEach { it.visibility = View.GONE }
        bar.formatBold.setOnClickListener { applyFormat("**") }
        bar.formatItalic.setOnClickListener { applyFormat("_") }
        bar.formatSpoiler.setOnClickListener { applyFormat("~!", "!~") }
        bar.formatStrikethrough.setOnClickListener { applyFormat("~~") }
        bar.formatQuote.setOnClickListener {
            val start = bodyInput.selectionStart
            bodyInput.text?.insert(start, "> ")
            bodyInput.setSelection(start + 2)
        }
        bar.formatLink.setOnClickListener {
            val start = bodyInput.selectionStart
            val end = bodyInput.selectionEnd
            val text = bodyInput.text ?: return@setOnClickListener
            if (start != end) {
                val selected = text.substring(start, end)
                text.replace(start, end, "[$selected]()")
                bodyInput.setSelection(start + selected.length + 3)
            } else {
                text.insert(start, "[]()")
                bodyInput.setSelection(start + 1)
            }
        }
        bar.formatCenter.setOnClickListener { applyFormat("~~~") }
    }

    companion object {
        /** AniList's shortest accepted review body. */
        private const val MIN_BODY_LENGTH = 2200
    }
}
