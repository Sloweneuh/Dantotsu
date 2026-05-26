package ani.dantotsu.media

import android.os.Bundle
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityReviewWriteBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.AndroidBug5497Workaround
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

        binding.reviewWriteToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.reviewMarkdownToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += navBarHeight
        }

        mediaId = intent.getIntExtra("mediaId", -1)
        if (mediaId == -1) {
            toast(getString(R.string.error))
            finish()
            return
        }

        updateScoreLabel()
        binding.reviewScoreSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                score = progress + 1
                updateScoreLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        binding.reviewWriteBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.reviewSubmitButton.setOnClickListener { submitReview() }

        setupMarkdownButtons()
    }

    private fun updateScoreLabel() {
        binding.reviewScoreLabel.text = getString(R.string.review_score_label_value, score)
    }

    private fun submitReview() {
        val summary = binding.reviewSummaryInput.text?.toString()?.trim() ?: ""
        val body = binding.reviewBodyInput.text?.toString()?.trim() ?: ""
        val isPrivate = binding.reviewPrivateCheckbox.isChecked

        if (summary.length < 20) {
            toast(getString(R.string.review_summary_too_short))
            return
        }
        if (body.length < 2200) {
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

        binding.formatBold.setOnClickListener { applyFormat("**") }
        binding.formatItalic.setOnClickListener { applyFormat("_") }
        binding.formatSpoiler.setOnClickListener { applyFormat("~!") }
        binding.formatStrikethrough.setOnClickListener { applyFormat("~~") }
        binding.formatQuote.setOnClickListener {
            val start = bodyInput.selectionStart
            bodyInput.text?.insert(start, "> ")
            bodyInput.setSelection(start + 2)
        }
        binding.formatLink.setOnClickListener {
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
        binding.formatCenter.setOnClickListener { applyFormat("~~~") }
    }
}
