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
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
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
class ScreenshotDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetScreenshotBinding? = null
    private val binding get() = _binding!!

    private var screenshot: Bitmap? = null

    /** Invoked when the sheet is dismissed (e.g. the anime player resumes playback here). */
    var onDismissed: (() -> Unit)? = null

    private val title get() = arguments?.getString(ARG_TITLE).orEmpty()
    private val titleOptions get() = arguments?.getStringArrayList(ARG_TITLE_OPTIONS).orEmpty()
    private val coverUrl get() = arguments?.getString(ARG_COVER)
    private val numberLabel get() = arguments?.getString(ARG_NUMBER).orEmpty()
    private val progressLabel get() = arguments?.getString(ARG_PROGRESS).orEmpty()
    private val sourceLabel get() = arguments?.getString(ARG_SOURCE)

    /** Currently displayed title, switchable via the title dropdown when there's more than one option. */
    private var selectedTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenshot = pending
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
        binding.screenshotTitle.text = selectedTitle
        binding.screenshotSubtitle.text =
            listOf(numberLabel, progressLabel).filter { it.isNotBlank() }.joinToString("  •  ")
        binding.screenshotSubtitle.isVisible = binding.screenshotSubtitle.text.isNotBlank()
        binding.screenshotDate.text =
            SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date())
        binding.screenshotSource.text = sourceLabel.orEmpty()
        binding.screenshotUsername.text = Anilist.username.orEmpty()

        // Seed the toggles from the saved defaults (Settings › Common › Screenshot defaults).
        // Must happen before the listeners are attached so it doesn't trigger an early re-render.
        binding.switchMediaInfo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowMediaInfo)
        binding.switchDate.isChecked = PrefManager.getVal(PrefName.ScreenshotShowDate)
        binding.switchSource.isChecked = PrefManager.getVal(PrefName.ScreenshotShowSource)
        binding.switchUserInfo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowUserInfo)
        binding.switchAppIcon.isChecked = PrefManager.getVal(PrefName.ScreenshotShowAppLogo)
        binding.switchFrame.isChecked = PrefManager.getVal(PrefName.ScreenshotShowFrame)
        binding.switchRounded.isChecked = PrefManager.getVal(PrefName.ScreenshotShowRoundedCorners)

        // User info is only meaningful when signed in to AniList.
        val loggedIn = !Anilist.username.isNullOrEmpty()
        binding.switchUserInfo.isEnabled = loggedIn
        if (!loggedIn) binding.switchUserInfo.isChecked = false

        loadRemoteImages()
        setupTitleSelector()

        // Re-render the card whenever anything changes.
        val toggles = listOf(
            binding.switchMediaInfo, binding.switchDate, binding.switchSource,
            binding.switchUserInfo, binding.switchAppIcon, binding.switchFrame,
            binding.switchRounded
        )
        toggles.forEach { it.setOnCheckedChangeListener { _, _ -> applyLayout() } }
        binding.screenshotCaptionInput.doOnTextChanged { _, _, _, _ -> applyLayout() }

        binding.screenshotSave.setOnClickListener {
            val out = buildOutputBitmap()
            if (out == null) {
                fail(); return@setOnClickListener
            }
            if (downloadsPermission(requireActivity() as AppCompatActivity))
                saveImageToDownloads(fileName(), out, requireActivity())
        }
        binding.screenshotShare.setOnClickListener {
            val out = buildOutputBitmap()
            if (out == null) {
                fail(); return@setOnClickListener
            }
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
                _binding?.screenshotCover?.setImageBitmap(cover)
            }
            Anilist.avatar?.let { url ->
                val request = Glide.with(this@ScreenshotDialogFragment).asBitmap().load(url)
                    .transform(CircleCrop())
                val avatar = withContext(Dispatchers.IO) { runCatching { request.submit().get() }.getOrNull() }
                _binding?.screenshotAvatar?.setImageBitmap(avatar)
            }
        }
    }

    /** Wires the "which title?" dropdown; hidden entirely when there's nothing to switch between. */
    private fun setupTitleSelector() {
        val options = titleOptions
        if (options.size <= 1) {
            binding.screenshotTitleSelectLayout.isVisible = false
            return
        }
        binding.screenshotTitleSelectLayout.isVisible = true
        binding.screenshotTitleSelect.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_titles_dropdown, options)
        )
        binding.screenshotTitleSelect.setText(selectedTitle, false)
        binding.screenshotTitleSelect.setOnItemClickListener { _, _, position, _ ->
            selectedTitle = options[position]
            binding.screenshotTitle.text = selectedTitle
        }
    }

    /** Applies the current toggle state to the live preview card. */
    private fun applyLayout() {
        val shot = screenshot ?: return
        val frame = binding.switchFrame.isChecked
        val rounded = binding.switchRounded.isChecked
        val mediaInfo = binding.switchMediaInfo.isChecked
        val userInfo = binding.switchUserInfo.isChecked && !Anilist.username.isNullOrEmpty()
        val appIcon = binding.switchAppIcon.isChecked
        val caption = captionText()

        binding.screenshotCaption.text = caption
        binding.screenshotCaptionRow.isVisible = caption.isNotEmpty()

        binding.screenshotMediaInfo.isVisible = mediaInfo
        binding.switchDate.isEnabled = mediaInfo
        binding.switchSource.isEnabled = mediaInfo
        val dateVisible = mediaInfo && binding.switchDate.isChecked
        val sourceVisible = mediaInfo && binding.switchSource.isChecked && !sourceLabel.isNullOrBlank()
        binding.screenshotDate.isVisible = dateVisible
        binding.screenshotSource.isVisible = sourceVisible

        // Grow the cover with the amount of text beside it (title/subtitle, plus date and source).
        val extraRows = (if (dateVisible) 1 else 0) + (if (sourceVisible) 1 else 0)
        binding.screenshotCover.updateLayoutParams {
            width = dp(48 + extraRows * 8)
            height = dp(72 + extraRows * 12)
        }

        binding.screenshotUserInfo.isVisible = userInfo
        // Logo placement, in order of preference so it never sits alone on a row while another
        // section could share with it: user-info row (footer) → media-info row → caption row →
        // otherwise the footer on its own.
        val onMedia = appIcon && mediaInfo && !userInfo
        val onCaption = appIcon && caption.isNotEmpty() && !mediaInfo && !userInfo
        val onFooter = appIcon && !onMedia && !onCaption
        binding.screenshotLogoInline.isVisible = onMedia
        binding.screenshotLogoCaption.isVisible = onCaption
        binding.screenshotLogoFooter.isVisible = onFooter
        binding.screenshotFooter.isVisible = userInfo || onFooter

        val hasDecor = caption.isNotEmpty() || mediaInfo || userInfo || appIcon
        binding.screenshotDecorContainer.isVisible = hasDecor

        // The screenshot itself gets rounded corners independently of the card frame/background.
        if (rounded) {
            binding.screenshotImage.setImageDrawable(
                RoundedBitmapDrawableFactory.create(resources, shot).apply {
                    cornerRadius = dp(12).toFloat()
                }
            )
        } else {
            binding.screenshotImage.setImageBitmap(shot)
        }

        val pad = dp(12)
        when {
            frame -> {
                binding.screenshotCard.setBackgroundResource(R.drawable.bg_screenshot_card)
                binding.screenshotCard.setPadding(pad, pad, pad, pad)
                binding.screenshotDecorContainer.setPadding(0, 0, 0, 0)
            }
            // Frameless but with info below: keep a surface strip so the text stays readable.
            hasDecor -> {
                binding.screenshotCard.setBackgroundResource(R.drawable.bg_screenshot_card)
                binding.screenshotCard.setPadding(0, 0, 0, 0)
                binding.screenshotDecorContainer.setPadding(pad, 0, pad, pad)
            }
            // Bare screenshot.
            else -> {
                binding.screenshotCard.background = null
                binding.screenshotCard.setPadding(0, 0, 0, 0)
                binding.screenshotDecorContainer.setPadding(0, 0, 0, 0)
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
        val bare = !binding.switchFrame.isChecked && !binding.switchRounded.isChecked &&
            !binding.screenshotDecorContainer.isVisible
        if (bare) return shot
        val card = binding.screenshotCard
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

    private fun captionText() = binding.screenshotCaptionInput.text?.toString()?.trim().orEmpty()

    private fun fail() {
        snackString(getString(R.string.screenshot_failed))
    }

    private fun fileName(): String {
        val raw = listOf(selectedTitle, numberLabel, progressLabel)
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

        /** Transient hand-off of the (large) capture bitmap; read and cleared in [onCreate]. */
        private var pending: Bitmap? = null

        /**
         * @param numberLabel  e.g. "Chapter 1050" or "Episode 5"
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
                )
            }
        }
    }
}
