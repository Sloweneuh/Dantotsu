package ani.dantotsu.media

import android.annotation.SuppressLint
import androidx.core.text.HtmlCompat
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.blurImage
import ani.dantotsu.currActivity
import ani.dantotsu.databinding.ItemMediaCompactBinding
import ani.dantotsu.databinding.ItemMediaLargeBinding
import ani.dantotsu.databinding.ItemMediaPageBinding
import ani.dantotsu.databinding.ItemMediaPageSmallBinding
import ani.dantotsu.loadImage
import ani.dantotsu.setAnimation
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import java.io.Serializable


class MediaAdaptor(
    var type: Int,
    private val mediaList: MutableList<Media>?,
    private val activity: FragmentActivity,
    private val matchParent: Boolean = false,
    private val viewPager: ViewPager2? = null,
    private val fav: Boolean = false,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (type) {
            0 -> MediaViewHolder(
                ItemMediaCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            1 -> MediaLargeViewHolder(
                ItemMediaLargeBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            2 -> MediaPageViewHolder(
                ItemMediaPageBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            3 -> MediaPageSmallViewHolder(
                ItemMediaPageSmallBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        }

    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (type) {
            0 -> {
                val b = (holder as MediaViewHolder).binding
                setAnimation(activity, b.root)
                val media = mediaList?.getOrNull(position)
                if (media != null) {
                    b.itemCompactImage.loadImage(media.cover)
                    run {
                        val status = media.status ?: ""
                        val releasingStr = currActivity()!!.getString(R.string.status_releasing)
                        val hiatusStr = currActivity()!!.getString(R.string.status_hiatus)
                        val isReleasing = status == releasingStr
                        val isHiatus = status.equals(hiatusStr, ignoreCase = true)
                        if (isReleasing || isHiatus) {
                            b.itemCompactOngoing.isVisible = true
                            try {
                                val dot = b.itemCompactOngoing.getChildAt(0)
                                if (isHiatus) dot?.setBackgroundResource(R.drawable.item_hiatus) else dot?.setBackgroundResource(R.drawable.item_ongoing)
                            } catch (e: Exception) {
                            }
                        } else {
                            b.itemCompactOngoing.isVisible = false
                        }
                    }
                    b.itemCompactTitle.text = media.userPreferredName
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScoreBG.background = ContextCompat.getDrawable(
                        b.root.context,
                        (if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                    )
                    b.itemCompactUserProgress.text = (media.userProgress ?: "~").toString()
                    if (media.relation != null) {
                        b.itemCompactRelation.text = "${media.relation}  "
                        b.itemCompactType.visibility = View.VISIBLE
                    } else {
                        b.itemCompactType.visibility = View.GONE
                    }
                    if (media.anime != null) {
                        if (media.relation != null) b.itemCompactTypeImage.setImageDrawable(
                            AppCompatResources.getDrawable(
                                activity,
                                R.drawable.ic_round_movie_filter_24
                            )
                        )
                        b.itemCompactTotal.text =
                            " | ${if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " | " + (media.anime.totalEpisodes ?: "~").toString()) else (media.anime.totalEpisodes ?: "~").toString()}"
                    } else if (media.manga != null) {
                        if (media.relation != null) b.itemCompactTypeImage.setImageDrawable(
                            AppCompatResources.getDrawable(
                                activity,
                                R.drawable.ic_round_import_contacts_24
                            )
                        )
                        b.itemCompactTotal.text = " | ${media.manga.totalChapters ?: "~"}"
                    }
                    // compact layout has no info button; skip showing MAL intro here
                    b.itemCompactProgressContainer.visibility = if (fav) View.GONE else View.VISIBLE
                }
            }

            1 -> {
                val b = (holder as MediaLargeViewHolder).binding
                setAnimation(activity, b.root)
                val media = mediaList?.get(position)
                if (media != null) {
                    b.itemCompactImage.loadImage(media.cover)
                    blurImage(b.itemCompactBanner, media.banner ?: media.cover)
                    run {
                        val status = media.status ?: ""
                        val releasingStr = currActivity()!!.getString(R.string.status_releasing)
                        val hiatusStr = currActivity()!!.getString(R.string.status_hiatus)
                        val isReleasing = status == releasingStr
                        val isHiatus = status.equals(hiatusStr, ignoreCase = true)
                        if (isReleasing || isHiatus) {
                            b.itemCompactOngoing.isVisible = true
                            try {
                                val dot = b.itemCompactOngoing.getChildAt(0)
                                if (isHiatus) dot?.setBackgroundResource(R.drawable.item_hiatus) else dot?.setBackgroundResource(R.drawable.item_ongoing)
                            } catch (e: Exception) {
                            }
                        } else {
                            b.itemCompactOngoing.isVisible = false
                        }
                    }
                    b.itemCompactTitle.text = media.userPreferredName
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScoreBG.background = ContextCompat.getDrawable(
                        b.root.context,
                        (if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                    )
                    b.itemCompactStatus.text = media.status ?: ""
                    b.itemCompactStatus.visibility = if (!b.itemCompactStatus.text.isNullOrBlank()) View.VISIBLE else View.GONE
                    // Synopsis preview (stripped from HTML) and user progress
                    try {
                        val rawDesc = media.description ?: ""
                        val parsed = HtmlCompat.fromHtml(rawDesc, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                        val synopsis = if (parsed.isNullOrBlank() || parsed == "null") activity.getString(R.string.no_description_available) else parsed
                        b.itemCompactSynopsis.text = synopsis
                        b.itemCompactSynopsis.movementMethod = ScrollingMovementMethod()
                        b.itemCompactSynopsis.scrollTo(0, 0)
                        b.itemCompactSynopsis.setOnTouchListener { v, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            false
                        }
                    } catch (e: Exception) {
                        b.itemCompactSynopsis.text = activity.getString(R.string.no_description_available)
                        b.itemCompactSynopsis.scrollTo(0, 0)
                    }
                    b.itemUserProgressLarge.text = (media.userProgress ?: "~").toString()
                    if (media.anime != null) {
                        val itemTotal = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural) else currActivity()!!.getString(
                            R.string.episode_singular
                        )
                        b.itemTotal.text = itemTotal
                        // Show only total count here; separator is handled in layout
                        b.itemCompactTotal.text = (media.anime.totalEpisodes ?: "??").toString()
                    } else if (media.manga != null) {
                        val itemTotal = " " + if ((media.manga.totalChapters
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.chapter_plural) else currActivity()!!.getString(
                            R.string.chapter_singular
                        )
                        b.itemTotal.text = itemTotal
                        b.itemCompactTotal.text = (media.manga.totalChapters ?: "??").toString()
                    }
                    if (position == mediaList!!.size - 2 && viewPager != null) viewPager.post {
                        val start = mediaList.size
                        mediaList.addAll(mediaList)
                        val end = mediaList.size - start
                        notifyItemRangeInserted(start, end)
                    }
                    // Info button: show scraped MAL intro if available (large layout)
                    try {
                        if (!activity.isFinishing && media.malIntro != null && media.malIntro!!.isNotBlank()) {
                            b.itemInfoButton.visibility = View.VISIBLE
                            b.itemInfoButton.setOnClickListener {
                                try {
                                    val inflater = LayoutInflater.from(activity)
                                    val popupView = inflater.inflate(R.layout.speech_bubble_popup, null)
                                    val popupText = popupView.findViewById<android.widget.TextView>(R.id.popupText)
                                    popupText.text = media.malIntro
                                    // reduce background opacity for the bubble programmatically
                                    try {
                                        popupText.background?.mutate()?.alpha = (0.85f * 255).toInt()
                                    } catch (ignored: Exception) {
                                    }

                                    val popup = android.widget.PopupWindow(
                                        popupView,
                                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                        true
                                    )
                                    popup.isOutsideTouchable = true
                                    popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

                                    // measure popup and decide whether to show above or below the button
                                    popupView.measure(
                                        android.view.View.MeasureSpec.UNSPECIFIED,
                                        android.view.View.MeasureSpec.UNSPECIFIED
                                    )
                                    val pw = popupView.measuredWidth
                                    val ph = popupView.measuredHeight
                                    val loc = IntArray(2)
                                    // use getLocationInWindow to align with showAtLocation coordinates
                                    b.itemInfoButton.getLocationInWindow(loc)
                                    val bx = loc[0]
                                    val by = loc[1]
                                    val buttonWidth = b.itemInfoButton.width

                                    val screenHeight = activity.resources.displayMetrics.heightPixels

                                    // offset to center popup horizontally relative to button
                                    val xOff = (buttonWidth - pw) / 2

                                    // if not enough space above, show below; otherwise show above
                                    val showAbove = by - ph > 0
                                    val arrowTop = popupView.findViewById<android.widget.ImageView>(R.id.popupArrowTop)
                                    val arrowBottom = popupView.findViewById<android.widget.ImageView>(R.id.popupArrowBottom)
                                    if (showAbove) {
                                        // show bubble above button: use bottom arrow (arrow points down)
                                        arrowTop?.visibility = View.GONE
                                        arrowBottom?.visibility = View.VISIBLE
                                        arrowBottom?.rotation = 0f

                                        // compute absolute screen coords so arrow centers exactly over button
                                        val decor = activity.window.decorView
                                        val screenX = bx + buttonWidth / 2 - pw / 2
                                        val screenY = by - ph
                                        popup.showAtLocation(decor, android.view.Gravity.NO_GRAVITY, screenX, screenY)
                                    } else {
                                        // show bubble below button: show top arrow and rotate it to point up
                                        arrowBottom?.visibility = View.GONE
                                        arrowTop?.visibility = View.VISIBLE
                                        arrowTop?.rotation = 180f

                                        val decor = activity.window.decorView
                                        val screenX = bx + buttonWidth / 2 - pw / 2
                                        val screenY = by + b.itemInfoButton.height
                                        popup.showAtLocation(decor, android.view.Gravity.NO_GRAVITY, screenX, screenY)
                                    }
                                } catch (e: Exception) {
                                    // fallback to dialog on error
                                    try {
                                        androidx.appcompat.app.AlertDialog.Builder(activity)
                                            .setTitle(activity.getString(R.string.info))
                                            .setMessage(media.malIntro)
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show()
                                    } catch (ignored: Exception) {
                                    }
                                }
                            }
                        } else {
                            b.itemInfoButton.visibility = View.GONE
                        }
                    } catch (e: Exception) {
                        b.itemInfoButton.visibility = View.GONE
                    }
                }
            }

            2 -> {
                val b = (holder as MediaPageViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {

                    val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
                    b.itemCompactImage.loadImage(media.cover)
                    if (bannerAnimations)
                        b.itemCompactBanner.setTransitionGenerator(
                            RandomTransitionGenerator(
                                (10000 + 15000 * ((PrefManager.getVal(PrefName.AnimationSpeed)) as Float)).toLong(),
                                AccelerateDecelerateInterpolator()
                            )
                        )
                    blurImage(
                        if (bannerAnimations) b.itemCompactBanner else b.itemCompactBannerNoKen,
                        media.banner ?: media.cover
                    )
                    run {
                        val status = media.status ?: ""
                        val releasingStr = currActivity()!!.getString(R.string.status_releasing)
                        val hiatusStr = currActivity()!!.getString(R.string.status_hiatus)
                        val isReleasing = status == releasingStr
                        val isHiatus = status.equals(hiatusStr, ignoreCase = true)
                        if (isReleasing || isHiatus) {
                            b.itemCompactOngoing.isVisible = true
                            try {
                                val dot = b.itemCompactOngoing.getChildAt(0)
                                if (isHiatus) dot?.setBackgroundResource(R.drawable.item_hiatus) else dot?.setBackgroundResource(R.drawable.item_ongoing)
                            } catch (e: Exception) {
                            }
                        } else {
                            b.itemCompactOngoing.isVisible = false
                        }
                    }
                    b.itemCompactTitle.text = media.userPreferredName
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScoreBG.background = ContextCompat.getDrawable(
                        b.root.context,
                        (if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                    )
                    if (media.anime != null) {
                        b.itemTotal.text = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural)
                        else currActivity()!!.getString(R.string.episode_singular)
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    } else if (media.manga != null) {
                        b.itemTotal.text = " " + if ((media.manga.totalChapters
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.chapter_plural)
                        else currActivity()!!.getString(R.string.chapter_singular)
                        b.itemCompactTotal.text = "${media.manga.totalChapters ?: "??"}"
                    }
                    @SuppressLint("NotifyDataSetChanged")
                    if (position == mediaList!!.size - 2 && viewPager != null) viewPager.post {
                        val size = mediaList.size
                        mediaList.addAll(mediaList)
                        notifyItemRangeInserted(size - 1, mediaList.size)
                    }
                }
            }

            3 -> {
                val b = (holder as MediaPageSmallViewHolder).binding
                val media = mediaList?.get(position)
                if (media != null) {
                    val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
                    b.itemCompactImage.loadImage(media.cover)
                    if (bannerAnimations)
                        b.itemCompactBanner.setTransitionGenerator(
                            RandomTransitionGenerator(
                                (10000 + 15000 * ((PrefManager.getVal(PrefName.AnimationSpeed) as Float))).toLong(),
                                AccelerateDecelerateInterpolator()
                            )
                        )
                    blurImage(
                        if (bannerAnimations) b.itemCompactBanner else b.itemCompactBannerNoKen,
                        media.banner ?: media.cover
                    )
                    run {
                        val status = media.status ?: ""
                        val releasingStr = currActivity()!!.getString(R.string.status_releasing)
                        val hiatusStr = currActivity()!!.getString(R.string.status_hiatus)
                        val isReleasing = status == releasingStr
                        val isHiatus = status.equals(hiatusStr, ignoreCase = true)
                        if (isReleasing || isHiatus) {
                            b.itemCompactOngoing.isVisible = true
                            try {
                                val dot = b.itemCompactOngoing.getChildAt(0)
                                if (isHiatus) dot?.setBackgroundResource(R.drawable.item_hiatus) else dot?.setBackgroundResource(R.drawable.item_ongoing)
                            } catch (e: Exception) {
                            }
                        } else {
                            b.itemCompactOngoing.isVisible = false
                        }
                    }
                    b.itemCompactTitle.text = media.userPreferredName
                    b.itemCompactScore.text =
                        ((if (media.userScore == 0) (media.meanScore
                            ?: 0) else media.userScore) / 10.0).toString()
                    b.itemCompactScoreBG.background = ContextCompat.getDrawable(
                        b.root.context,
                        (if (media.userScore != 0) R.drawable.item_user_score else R.drawable.item_score)
                    )
                    media.genres.apply {
                        if (isNotEmpty()) {
                            var genres = ""
                            forEach { genres += "$it • " }
                            genres = genres.removeSuffix(" • ")
                            b.itemCompactGenres.text = genres
                        }
                    }
                    b.itemCompactStatus.text = media.status ?: ""
                    if (media.anime != null) {
                        b.itemTotal.text = " " + if ((media.anime.totalEpisodes
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.episode_plural)
                        else currActivity()!!.getString(R.string.episode_singular)
                        b.itemCompactTotal.text =
                            if (media.anime.nextAiringEpisode != null) (media.anime.nextAiringEpisode.toString() + " / " + (media.anime.totalEpisodes
                                ?: "??").toString()) else (media.anime.totalEpisodes
                                ?: "??").toString()
                    } else if (media.manga != null) {
                        b.itemTotal.text = " " + if ((media.manga.totalChapters
                                ?: 0) != 1
                        ) currActivity()!!.getString(R.string.chapter_plural)
                        else currActivity()!!.getString(R.string.chapter_singular)
                        b.itemCompactTotal.text = "${media.manga.totalChapters ?: "??"}"
                    }
                    @SuppressLint("NotifyDataSetChanged")
                    if (position == mediaList!!.size - 2 && viewPager != null) viewPager.post {
                        val size = mediaList.size
                        mediaList.addAll(mediaList)
                        notifyItemRangeInserted(size - 1, mediaList.size)
                    }
                }
            }
        }
    }

    override fun getItemCount() = mediaList!!.size

    override fun getItemViewType(position: Int): Int {
        return type
    }

    fun randomOptionClick() {
        if (!mediaList.isNullOrEmpty()) {
            try {
                val listCopy = ArrayList(mediaList)
                MediaRandomDialogFragment.newInstance(listCopy)
                    .show(activity.supportFragmentManager, "random")
            } catch (e: Exception) {
                // fallback to previous behavior
                val media = mediaList.random()
                val index = mediaList.indexOf(media)
                clicked(index, null)
            }
        }
    }

    inner class MediaViewHolder(val binding: ItemMediaCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            if (matchParent) itemView.updateLayoutParams { width = -1 }
            itemView.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
        }
    }

    inner class MediaLargeViewHolder(val binding: ItemMediaLargeBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnLongClickListener { longClicked(bindingAdapterPosition) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MediaPageViewHolder(val binding: ItemMediaPageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemCompactImage.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnTouchListener { _, _ -> true }
            binding.itemCompactImage.setOnLongClickListener { longClicked(bindingAdapterPosition) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class MediaPageSmallViewHolder(val binding: ItemMediaPageSmallBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.itemCompactImage.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            binding.itemCompactTitleContainer.setSafeOnClickListener {
                clicked(
                    bindingAdapterPosition,
                    binding.itemCompactImage,
                    resizeBitmap(getBitmapFromImageView(binding.itemCompactImage), 100)
                )
            }
            itemView.setOnTouchListener { _, _ -> true }
            binding.itemCompactImage.setOnLongClickListener { longClicked(bindingAdapterPosition) }
        }
    }

    fun clicked(position: Int, itemCompactImage: ImageView?, bitmap: Bitmap? = null) {
        if ((mediaList?.size ?: 0) > position && position != -1) {
            val media = mediaList?.get(position)
            if (bitmap != null) MediaSingleton.bitmap = bitmap
            ContextCompat.startActivity(
                activity,
                Intent(activity, MediaDetailsActivity::class.java).putExtra(
                    "media",
                    media as Serializable
                ),
                if (itemCompactImage != null) {
                    ActivityOptionsCompat.makeSceneTransitionAnimation(
                        activity,
                        itemCompactImage,
                        ViewCompat.getTransitionName(itemCompactImage)!!
                    ).toBundle()
                } else {
                    null
                }
            )
        }
    }


    fun longClicked(position: Int): Boolean {
        if ((mediaList?.size ?: 0) > position && position != -1) {
            val media = mediaList?.get(position) ?: return false
            if (activity.supportFragmentManager.findFragmentByTag("list") == null) {
                MediaListDialogSmallFragment.newInstance(media)
                    .show(activity.supportFragmentManager, "list")
                return true
            }
        }
        return false
    }

    fun getBitmapFromImageView(imageView: ImageView): Bitmap? {
        val drawable = imageView.drawable ?: return null

        // If the drawable is a BitmapDrawable, then just get the bitmap
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        // Create a bitmap with the same dimensions as the drawable
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )

        // Draw the drawable onto the bitmap
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return bitmap
    }

    fun resizeBitmap(source: Bitmap?, maxDimension: Int): Bitmap? {
        if (source == null) return null
        val width = source.width
        val height = source.height
        val newWidth: Int
        val newHeight: Int

        if (width > height) {
            newWidth = maxDimension
            newHeight = (height * (maxDimension.toFloat() / width)).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (width * (maxDimension.toFloat() / height)).toInt()
        }

        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }

}