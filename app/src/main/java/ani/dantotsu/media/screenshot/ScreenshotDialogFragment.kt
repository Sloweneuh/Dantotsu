package ani.dantotsu.media.screenshot

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.anime.ExoplayerView
import ani.dantotsu.databinding.BottomSheetScreenshotBinding
import ani.dantotsu.saveImageToDownloads
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.shareImage
import ani.dantotsu.snackString
import ani.dantotsu.util.StoragePermissions.Companion.downloadsPermission
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Review/compose sheet shown after a reader screenshot is captured. The [screenshotCard] view is a
 * live WYSIWYG preview of the share card; the toggles simply change what it renders, and Save/Share
 * draw that same view to a bitmap (or hand back the untouched capture when nothing is added).
 *
 * The capture bitmap is passed through [pending] rather than the argument bundle to avoid a
 * TransactionTooLarge; the small text metadata rides in the arguments as usual.
 */
class ScreenshotDialogFragment : CaptureSheetFragment() {

    private var _binding: BottomSheetScreenshotBinding? = null
    private val binding get() = _binding!!

    private var screenshot: Bitmap? = null

    /** The untouched capture, kept so a crop can be undone. */
    private var originalScreenshot: Bitmap? = null

    /** Invoked when the sheet is dismissed (e.g. the anime player resumes playback here). */
    var onDismissed: (() -> Unit)? = null

    private val title get() = arguments?.getString(ARG_TITLE).orEmpty()
    private val titleOptions get() = arguments?.getStringArrayList(ARG_TITLE_OPTIONS).orEmpty()
    private val coverUrl get() = arguments?.getString(ARG_COVER)
    private val numberLabel get() = arguments?.getString(ARG_NUMBER).orEmpty()
    private val progressLabel get() = arguments?.getString(ARG_PROGRESS).orEmpty()
    private val sourceLabel get() = arguments?.getString(ARG_SOURCE)
    private val isAnime get() = arguments?.getBoolean(ARG_IS_ANIME) ?: false

    /**
     * Sources usually label the number themselves ("Vol. 4 Ch. 20", "Episode 5"), so it's shown
     * verbatim. When a source hands over a bare number there's nothing to say what it counts, so
     * add the prefix ourselves rather than let a lone "2" sit in the card.
     */
    private fun displayNumberLabel(): String {
        val label = numberLabel.trim()
        if (!label.matches(BARE_NUMBER)) return label
        return getString(
            if (isAnime) R.string.episode_num_short else R.string.chapter_num_short,
            label
        )
    }

    /** Currently displayed title, switchable via the title dropdown when there's more than one option. */
    private var selectedTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenshot = pending
        originalScreenshot = pending
        pending = null
        selectedTitle = title
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetScreenshotBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shot = screenshot
        if (shot == null) {
            snackString(getString(R.string.screenshot_failed))
            dismissAllowingStateLoss()
            return
        }

        // Static metadata
        binding.screenshotStage.screenshotTitle.text = selectedTitle
        binding.screenshotStage.screenshotSubtitle.text =
            listOf(displayNumberLabel(), progressLabel).filter { it.isNotBlank() }
                .joinToString("  •  ")
        binding.screenshotStage.screenshotSubtitle.isVisible = binding.screenshotStage.screenshotSubtitle.text.isNotBlank()
        binding.screenshotStage.screenshotDate.text =
            SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date())
        binding.screenshotStage.screenshotSource.text = sourceLabel.orEmpty()
        binding.screenshotStage.screenshotUsername.text = Anilist.username.orEmpty()

        // Seed the toggles from the saved defaults (Settings › Common › Screenshot defaults).
        // Must happen before the listeners are attached so it doesn't trigger an early re-render.
        binding.screenshotOptions.chipMediaInfo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowMediaInfo)
        binding.screenshotOptions.chipDate.isChecked = PrefManager.getVal(PrefName.ScreenshotShowDate)
        binding.screenshotOptions.chipSource.isChecked = PrefManager.getVal(PrefName.ScreenshotShowSource)
        binding.screenshotOptions.chipUserInfo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowUserInfo)
        binding.screenshotOptions.chipAppIcon.isChecked = PrefManager.getVal(PrefName.ScreenshotShowAppLogo)
        binding.screenshotOptions.chipFrame.isChecked = PrefManager.getVal(PrefName.ScreenshotShowFrame)
        binding.screenshotOptions.chipRounded.isChecked = PrefManager.getVal(PrefName.ScreenshotShowRoundedCorners)

        // User info is only meaningful when signed in to AniList.
        val loggedIn = !Anilist.username.isNullOrEmpty()
        binding.screenshotOptions.chipUserInfo.isEnabled = loggedIn
        if (!loggedIn) binding.screenshotOptions.chipUserInfo.isChecked = false

        loadRemoteImages()
        setupTitleSelector()

        // Re-render the card whenever anything changes.
        val toggles = listOf(
            binding.screenshotOptions.chipMediaInfo, binding.screenshotOptions.chipDate, binding.screenshotOptions.chipSource,
            binding.screenshotOptions.chipUserInfo, binding.screenshotOptions.chipAppIcon, binding.screenshotOptions.chipFrame,
            binding.screenshotOptions.chipRounded
        )
        toggles.forEach { it.setOnCheckedChangeListener { _, _ -> applyLayout() } }
        binding.screenshotOptions.screenshotCaptionInput.doOnTextChanged { _, _, _, _ -> applyLayout() }

        binding.screenshotStage.screenshotCropStart.setOnClickListener { setCropMode(true) }
        binding.screenshotBar.screenshotCropCancel.setOnClickListener { setCropMode(false) }
        binding.screenshotBar.screenshotCropApply.setOnClickListener { applyCrop() }
        binding.screenshotBar.screenshotCropReset.setOnClickListener {
            // Undo any crop already applied, then re-open the selection on the full capture.
            screenshot = originalScreenshot
            renderPreviewImage()
            binding.screenshotStage.screenshotCropOverlay.reset()
        }

        binding.screenshotBar.screenshotSave.setOnClickListener {
            val out = buildOutputBitmap()
            if (out == null) {
                fail(); return@setOnClickListener
            }
            if (downloadsPermission(requireActivity() as AppCompatActivity))
                saveImageToDownloads(fileName(), out, requireActivity())
        }
        binding.screenshotBar.screenshotShare.setOnClickListener {
            val out = buildOutputBitmap()
            if (out == null) {
                fail(); return@setOnClickListener
            }
            // Raising the chooser isn't the user leaving the player, but some platforms report it
            // as such and the video would drop into picture-in-picture behind it.
            (activity as? ExoplayerView)?.suppressPipForChooser()
            shareImage(fileName(), out, requireContext())
        }

        applyLayout()
    }

    /**
     * Loads the cover and (optionally) the AniList avatar into the card. The Glide request is built
     * on the main thread; only the blocking decode ([java.util.concurrent.Future.get]) runs on IO,
     * so both images are present before the user is likely to hit Save/Share.
     */
    private fun loadRemoteImages() {
        viewLifecycleOwner.lifecycleScope.launch {
            coverUrl?.let { url ->
                // Center-crop to a 2:3 poster and bake in rounded corners so they survive being
                // drawn to a bitmap (outline clipping doesn't render on a software canvas).
                val request = Glide.with(this@ScreenshotDialogFragment).asBitmap().load(url)
                    .transform(CenterCrop(), RoundedCorners(dp(8)))
                    .override(dp(72), dp(108))
                val cover = withContext(Dispatchers.IO) { runCatching { request.submit().get() }.getOrNull() }
                _binding?.screenshotStage?.screenshotCover?.setImageBitmap(cover)
            }
            Anilist.avatar?.let { url ->
                val request = Glide.with(this@ScreenshotDialogFragment).asBitmap().load(url)
                    .transform(CircleCrop())
                val avatar = withContext(Dispatchers.IO) { runCatching { request.submit().get() }.getOrNull() }
                _binding?.screenshotStage?.screenshotAvatar?.setImageBitmap(avatar)
            }
        }
    }

    /** Wires the "which title?" dropdown; hidden entirely when there's nothing to switch between. */
    private fun setupTitleSelector() {
        val options = titleOptions
        if (options.size <= 1) {
            binding.screenshotOptions.screenshotTitleSelectLayout.isVisible = false
            return
        }
        binding.screenshotOptions.screenshotTitleSelectLayout.isVisible = true
        binding.screenshotOptions.screenshotTitleSelect.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_titles_dropdown, options)
        )
        binding.screenshotOptions.screenshotTitleSelect.setText(selectedTitle, false)
        binding.screenshotOptions.screenshotTitleSelect.setOnItemClickListener { _, _, position, _ ->
            selectedTitle = options[position]
            binding.screenshotStage.screenshotTitle.text = selectedTitle
        }
    }

    /**
     * Enters/leaves crop mode: the overlay on the preview becomes interactive and the bottom bar
     * swaps from Save/Share to the crop actions. The card decorations stay hidden meanwhile so the
     * screenshot is all the user has to aim at.
     */
    private fun setCropMode(cropping: Boolean) {
        binding.screenshotStage.screenshotCropOverlay.isVisible = cropping
        binding.screenshotBar.screenshotActions.isVisible = !cropping
        binding.screenshotBar.screenshotCropActions.isVisible = cropping
        binding.screenshotStage.screenshotCropStart.isVisible = !cropping
        binding.screenshotStage.screenshotCropHint.isVisible = cropping
        binding.screenshotStage.screenshotDecorContainer.isVisible = !cropping && hasDecor()
        if (cropping) binding.screenshotStage.screenshotCropOverlay.reset() else applyLayout()
    }

    /** Crops [screenshot] down to the current selection and returns to the compose view. */
    private fun applyCrop() {
        val shot = screenshot
        val overlay = binding.screenshotStage.screenshotCropOverlay
        if (shot == null) {
            fail(); return
        }
        if (!overlay.isFullFrame()) {
            val rect = overlay.cropRect(shot.width, shot.height)
            val cropped = runCatching {
                Bitmap.createBitmap(shot, rect.left, rect.top, rect.width(), rect.height())
            }.getOrNull()
            if (cropped == null) {
                fail(); return
            }
            screenshot = cropped
        }
        setCropMode(false)
    }

    /**
     * Puts the current (possibly cropped) capture in the preview. The screenshot itself gets
     * rounded corners independently of the card frame/background.
     */
    private fun renderPreviewImage() {
        val shot = screenshot ?: return
        if (binding.screenshotOptions.chipRounded.isChecked) {
            binding.screenshotStage.screenshotImage.setImageDrawable(
                RoundedBitmapDrawableFactory.create(resources, shot).apply {
                    cornerRadius = dp(12).toFloat()
                }
            )
        } else {
            binding.screenshotStage.screenshotImage.setImageBitmap(shot)
        }
    }

    /** Whether anything is rendered below the screenshot itself. */
    private fun hasDecor(): Boolean {
        val mediaInfo = binding.screenshotOptions.chipMediaInfo.isChecked
        val userInfo = binding.screenshotOptions.chipUserInfo.isChecked && !Anilist.username.isNullOrEmpty()
        return captionText().isNotEmpty() || mediaInfo || userInfo || binding.screenshotOptions.chipAppIcon.isChecked
    }

    /** Applies the current toggle state to the live preview card. */
    private fun applyLayout() {
        val frame = binding.screenshotOptions.chipFrame.isChecked
        val mediaInfo = binding.screenshotOptions.chipMediaInfo.isChecked
        val userInfo = binding.screenshotOptions.chipUserInfo.isChecked && !Anilist.username.isNullOrEmpty()
        val appIcon = binding.screenshotOptions.chipAppIcon.isChecked
        val caption = captionText()

        binding.screenshotStage.screenshotCaption.text = caption
        binding.screenshotStage.screenshotCaptionRow.isVisible = caption.isNotEmpty()

        binding.screenshotStage.screenshotMediaInfo.isVisible = mediaInfo
        binding.screenshotOptions.chipDate.isEnabled = mediaInfo
        binding.screenshotOptions.chipSource.isEnabled = mediaInfo
        val dateVisible = mediaInfo && binding.screenshotOptions.chipDate.isChecked
        val sourceVisible = mediaInfo && binding.screenshotOptions.chipSource.isChecked && !sourceLabel.isNullOrBlank()
        binding.screenshotStage.screenshotDate.isVisible = dateVisible
        binding.screenshotStage.screenshotSource.isVisible = sourceVisible

        // Grow the cover with the amount of text beside it (title/subtitle, plus date and source).
        val extraRows = (if (dateVisible) 1 else 0) + (if (sourceVisible) 1 else 0)
        binding.screenshotStage.screenshotCover.updateLayoutParams {
            width = dp(48 + extraRows * 8)
            height = dp(72 + extraRows * 12)
        }

        binding.screenshotStage.screenshotUserInfo.isVisible = userInfo
        // Logo placement, in order of preference so it never sits alone on a row while another
        // section could share with it: user-info row (footer) → media-info row → caption row →
        // otherwise the footer on its own.
        val onMedia = appIcon && mediaInfo && !userInfo
        val onCaption = appIcon && caption.isNotEmpty() && !mediaInfo && !userInfo
        val onFooter = appIcon && !onMedia && !onCaption
        binding.screenshotStage.screenshotLogoInline.isVisible = onMedia
        binding.screenshotStage.screenshotLogoCaption.isVisible = onCaption
        binding.screenshotStage.screenshotLogoFooter.isVisible = onFooter
        binding.screenshotStage.screenshotFooter.isVisible = userInfo || onFooter

        val decor = hasDecor()
        binding.screenshotStage.screenshotDecorContainer.isVisible = decor

        renderPreviewImage()

        val pad = dp(12)
        when {
            frame -> {
                binding.screenshotStage.screenshotCard.setBackgroundResource(R.drawable.bg_screenshot_card)
                binding.screenshotStage.screenshotCard.setPadding(pad, pad, pad, pad)
                binding.screenshotStage.screenshotDecorContainer.setPadding(0, 0, 0, 0)
            }
            // Frameless but with info below: keep a surface strip so the text stays readable.
            decor -> {
                binding.screenshotStage.screenshotCard.setBackgroundResource(R.drawable.bg_screenshot_card)
                binding.screenshotStage.screenshotCard.setPadding(0, 0, 0, 0)
                binding.screenshotStage.screenshotDecorContainer.setPadding(pad, 0, pad, pad)
            }
            // Bare screenshot.
            else -> {
                binding.screenshotStage.screenshotCard.background = null
                binding.screenshotStage.screenshotCard.setPadding(0, 0, 0, 0)
                binding.screenshotStage.screenshotDecorContainer.setPadding(0, 0, 0, 0)
            }
        }
    }

    /**
     * The raw capture when nothing is added (keeps full resolution), else the rendered card.
     * Rendered at [CARD_EXPORT_SCALE]x the on-screen size: text is redrawn at that resolution
     * (not just upscaled after the fact), which keeps captions/labels sharp once chat apps like
     * Discord recompress the shared image.
     */
    private fun buildOutputBitmap(): Bitmap? {
        val shot = screenshot ?: return null
        val bare = !binding.screenshotOptions.chipFrame.isChecked && !binding.screenshotOptions.chipRounded.isChecked &&
            !binding.screenshotStage.screenshotDecorContainer.isVisible
        if (bare) return shot
        val card = binding.screenshotStage.screenshotCard
        if (card.width <= 0 || card.height <= 0) return null
        return runCatching {
            val width = (card.width * CARD_EXPORT_SCALE).toInt()
            val height = (card.height * CARD_EXPORT_SCALE).toInt()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.scale(CARD_EXPORT_SCALE, CARD_EXPORT_SCALE)
                card.draw(canvas)
            }
        }.getOrNull()
    }

    private fun captionText() = binding.screenshotOptions.screenshotCaptionInput.text?.toString()?.trim().orEmpty()

    private fun fail() {
        snackString(getString(R.string.screenshot_failed))
    }

    private fun fileName(): String {
        val raw = listOf(selectedTitle, displayNumberLabel(), progressLabel)
            .filter { it.isNotBlank() }.joinToString(" - ")
            .ifBlank { getString(R.string.screenshot) }
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "").take(120)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissed?.invoke()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val CARD_EXPORT_SCALE = 2f
        private const val ARG_TITLE = "title"
        private const val ARG_TITLE_OPTIONS = "titleOptions"
        private const val ARG_COVER = "cover"
        private const val ARG_NUMBER = "number"
        private const val ARG_PROGRESS = "progress"
        private const val ARG_SOURCE = "source"
        private const val ARG_IS_ANIME = "isAnime"

        /** A number and nothing else — no "Ch."/"Episode"/title to say what it's counting. */
        private val BARE_NUMBER = Regex("""\d+([.,]\d+)?""")

        /** Transient hand-off of the (large) capture bitmap; read and cleared in [onCreate]. */
        private var pending: Bitmap? = null

        /**
         * @param numberLabel  e.g. "Chapter 1050" or "Episode 5"; a bare number gets an
         *   "Ep."/"Ch." prefix added for display, per [isAnime]
         * @param progressLabel e.g. "8/24" (manga page) or "12:34" (anime timestamp)
         * @param sourceLabel  extension/source name, or null to hide the row
         * @param titleOptions alternate titles/synonyms offered in the title dropdown (e.g. via
         *   [ani.dantotsu.media.Media.mainTitleOptions]); the selector is hidden when there's 1 or fewer
         */
        fun newInstance(
            screenshot: Bitmap,
            title: String,
            titleOptions: List<String> = emptyList(),
            coverUrl: String?,
            numberLabel: String,
            progressLabel: String,
            sourceLabel: String?,
            isAnime: Boolean,
        ): ScreenshotDialogFragment {
            pending = screenshot
            return ScreenshotDialogFragment().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_TITLE_OPTIONS to ArrayList(titleOptions),
                    ARG_COVER to coverUrl,
                    ARG_NUMBER to numberLabel,
                    ARG_PROGRESS to progressLabel,
                    ARG_SOURCE to sourceLabel,
                    ARG_IS_ANIME to isAnime,
                )
            }
        }
    }
}
