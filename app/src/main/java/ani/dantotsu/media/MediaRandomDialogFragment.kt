package ani.dantotsu.media

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import ani.dantotsu.R
import ani.dantotsu.loadImage
import ani.dantotsu.blurImage
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.content.Intent
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import android.widget.FrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.request.RequestOptions
import jp.wasabeef.glide.transformations.BlurTransformation

class MediaRandomDialogFragment : DialogFragment() {

    private var mediaList: ArrayList<Media> = arrayListOf()
    private var current: Media? = null
    private var currentCoverTarget: CustomTarget<Drawable>? = null
    private var currentBackgroundTarget: CustomTarget<Drawable>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getSerializable("list")?.let {
            @Suppress("UNCHECKED_CAST")
            mediaList = it as ArrayList<Media>
        }
        // Use default dialog theme from host activity (no explicit style set to avoid API requirements)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_media_random, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // allow dialog to be cancelled by tapping outside
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(true)

        val cardBackground = view.findViewById<ImageView>(R.id.cardBackground)
        val coverImage = view.findViewById<ImageView>(R.id.coverImage)
        val titleText = view.findViewById<TextView>(R.id.titleText)
        val synopsisText = view.findViewById<TextView>(R.id.synopsisText)
        val progressText = view.findViewById<TextView>(R.id.progressText)
        val totalText = view.findViewById<TextView>(R.id.totalText)
        val rerollButton = view.findViewById<ImageButton>(R.id.rerollButton)
        val card = view.findViewById<View>(R.id.cardContainer)

        // tapping the full-screen dialog background should dismiss the dialog;
        // the `card` child will consume clicks, so this only fires when outside the card.
        view.setOnClickListener {
            try { dismissAllowingStateLoss() } catch (_: Exception) {}
        }

        fun bindMedia(media: Media, drawable: Drawable? = null, backgroundDrawable: Drawable? = null) {
            current = media
            // show placeholder immediately so card isn't empty while cover loads
            if (drawable != null) {
                coverImage.setImageDrawable(drawable)
            } else {
                coverImage.setImageResource(R.drawable.linear_gradient_bg)
                coverImage.loadImage(media.cover)
            }
            // blurred low-opacity background (use banner if available). If a preloaded blurred drawable is provided, use it to avoid double-loading.
            if (backgroundDrawable != null) {
                cardBackground.setImageDrawable(backgroundDrawable)
            } else {
                blurImage(cardBackground, media.banner ?: media.cover)
            }
            titleText.text = media.userPreferredName
            val rawDesc = media.description ?: ""
            val parsed = HtmlCompat.fromHtml(rawDesc, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            synopsisText.text = if (parsed.isBlank() || parsed == "null") getString(R.string.no_description_available) else parsed
            synopsisText.movementMethod = ScrollingMovementMethod()
            progressText.text = (media.userProgress ?: "~").toString()
            totalText.text = when {
                media.anime != null -> (media.anime.totalEpisodes ?: "~").toString()
                media.manga != null -> (media.manga.totalChapters ?: "~").toString()
                else -> "~"
            }
        }

        fun pickRandom(animate: Boolean = true) {
            if (mediaList.isNotEmpty()) {
                val next = mediaList.random()
                // If there is a cover url try to preload it before flipping so the animation shows the loaded image
                val url = next.cover
                val backgroundUrl = next.banner ?: next.cover
                if (url.isNullOrEmpty() && backgroundUrl.isNullOrEmpty()) {
                    // no cover to preload — bind immediately (no animation)
                    if (animate) {
                        card.animate().rotationY(90f).setDuration(180).withEndAction {
                            bindMedia(next)
                            card.rotationY = -90f
                            card.animate().rotationY(0f).setDuration(180).setInterpolator(AccelerateDecelerateInterpolator()).start()
                        }.start()
                    } else {
                        bindMedia(next)
                    }
                } else {
                    try {
                        // Clear previous targets
                        currentCoverTarget?.let { Glide.with(coverImage.context).clear(it) }
                        currentBackgroundTarget?.let { Glide.with(cardBackground.context).clear(it) }

                        var coverReady = false
                        var backgroundReady = false
                        var coverDrawable: Drawable? = null
                        var backgroundDrawable: Drawable? = null

                        // cover target
                        val coverTarget = object : CustomTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                coverReady = true
                                coverDrawable = resource
                                if (backgroundUrl.isNullOrEmpty() || backgroundReady) {
                                    // both ready (or no background) — perform bind/flip according to animate flag
                                    if (animate) {
                                                    performFlip(card) {
                                                        bindMedia(next, coverDrawable, backgroundDrawable)
                                                    }
                                                } else {
                                                    bindMedia(next, coverDrawable, backgroundDrawable)
                                                }
                                }
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {}
                        }

                        // background target (apply blur)
                        val backgroundTarget = object : CustomTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                backgroundReady = true
                                backgroundDrawable = resource
                                if (coverReady || url.isNullOrEmpty()) {
                                    if (animate) {
                                        performFlip(card) {
                                            bindMedia(next, coverDrawable, backgroundDrawable)
                                        }
                                    } else {
                                        bindMedia(next, coverDrawable, backgroundDrawable)
                                    }
                                }
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {}
                        }

                        currentCoverTarget = coverTarget
                        currentBackgroundTarget = backgroundTarget

                        // start loading
                        if (!url.isNullOrEmpty()) Glide.with(coverImage.context).load(url).into(coverTarget)
                        if (!backgroundUrl.isNullOrEmpty()) {
                            // apply blur transformation similar to blurImage
                            val radius = 20
                            val sampling = 3
                            Glide.with(cardBackground.context)
                                .load(backgroundUrl)
                                .apply(RequestOptions.bitmapTransform(BlurTransformation(radius, sampling)))
                                .into(backgroundTarget)
                        }
                    } catch (e: Exception) {
                        // fallback to immediate flip on error
                        card.animate().rotationY(90f).setDuration(180).withEndAction {
                            bindMedia(next)
                            card.rotationY = -90f
                            card.animate().rotationY(0f).setDuration(180).setInterpolator(AccelerateDecelerateInterpolator()).start()
                        }.start()
                    }
                }
            }
        }

        // initial selection without animation — preload then bind
        if (mediaList.isNotEmpty()) pickRandom(false)

        rerollButton.setOnClickListener { pickRandom() }

        // Make the card clickable to open full media with a proper shared-element transition.
        // Dialog views are in a separate window, so we create an overlay ImageView in the
        // host Activity's window and use that as the shared element for the transition.
        card.setOnClickListener {
            val media = current ?: return@setOnClickListener
            try {
                // prepare thumbnail bitmap similar to adapter
                val drawable = coverImage.drawable
                val bitmap: Bitmap? = when (drawable) {
                    is BitmapDrawable -> drawable.bitmap
                    null -> null
                    else -> {
                        val bmp = Bitmap.createBitmap(
                            drawable.intrinsicWidth.takeIf { it > 0 } ?: 1,
                            drawable.intrinsicHeight.takeIf { it > 0 } ?: 1,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    }
                }
                if (bitmap != null) MediaSingleton.bitmap = bitmap

                // Create an overlay ImageView in the Activity decor view positioned over
                // the dialog cover so the shared-element transition uses a view from the
                // activity window (dialogs are separate windows and won't work reliably).
                val transitionName = ViewCompat.getTransitionName(coverImage) ?: "mediaCover"
                val decor = requireActivity().window.decorView as ViewGroup
                val overlay = ImageView(requireContext())
                overlay.setImageDrawable(coverImage.drawable)
                overlay.scaleType = coverImage.scaleType
                ViewCompat.setTransitionName(overlay, transitionName)

                // match size and position of the coverImage on screen
                val loc = IntArray(2)
                coverImage.getLocationOnScreen(loc)
                val width = coverImage.width
                val height = coverImage.height

                val lp = FrameLayout.LayoutParams(width, height)
                // Add overlay and position via x/y so margins aren't confused by decor offsets
                decor.addView(overlay, lp)
                overlay.x = loc[0].toFloat()
                overlay.y = loc[1].toFloat()
                overlay.elevation = 20f

                val intent = Intent(requireActivity(), MediaDetailsActivity::class.java).putExtra("media", media as java.io.Serializable)
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(requireActivity(), overlay, transitionName)
                ContextCompat.startActivity(requireContext(), intent, options.toBundle())

                // remove overlay and dismiss the dialog shortly after starting the activity
                overlay.postDelayed({
                    try { decor.removeView(overlay) } catch (_: Exception) {}
                    try { dismissAllowingStateLoss() } catch (_: Exception) {}
                }, 300)
            } catch (e: Exception) {
                // fallback: just open activity without transition
                try {
                    val intent = Intent(requireActivity(), MediaDetailsActivity::class.java).putExtra("media", media as java.io.Serializable)
                    ContextCompat.startActivity(requireContext(), intent, null)
                    dismiss()
                } catch (_: Exception) {}
            }
        }
    }

    // Helper to perform a Y-rotation flip with proper camera distance/pivot and
    // using a hardware layer to avoid clipping / rendering artifacts.
    private fun performFlip(target: View, onMidFlip: () -> Unit) {
        // ensure pivot is centered once layout is measured
        target.post {
            val density = resources.displayMetrics.density
            // increase camera distance for better perspective
            target.cameraDistance = 8000 * density
            target.pivotX = (target.width / 2).toFloat()
            target.pivotY = (target.height / 2).toFloat()

            // Use hardware layer for smoother 3D rotation
            target.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            target.animate().rotationY(90f).setDuration(200).withEndAction {
                try {
                    onMidFlip()
                } catch (_: Exception) {}
                target.rotationY = -90f
                target.animate().rotationY(0f).setDuration(200).setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                    // restore layer type
                    target.setLayerType(View.LAYER_TYPE_NONE, null)
                }.start()
            }.start()
        }
    }

        override fun onStart() {
            super.onStart()
            // make dialog window background transparent so only the card is visible
            dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // expand dialog window to full screen so 3D flips aren't clipped by
            // the dialog window bounds (card may rotate outside its original size).
            val lp = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog?.window?.setLayout(lp.width, lp.height)
        }

    companion object {
        fun newInstance(list: ArrayList<Media>): MediaRandomDialogFragment {
            val frag = MediaRandomDialogFragment()
            val args = Bundle()
            args.putSerializable("list", list)
            frag.arguments = args
            return frag
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // clear any pending Glide targets to avoid leaks
        currentCoverTarget?.let { Glide.with(requireContext()).clear(it) }
        currentBackgroundTarget?.let { Glide.with(requireContext()).clear(it) }
        currentCoverTarget = null
        currentBackgroundTarget = null
    }
}
