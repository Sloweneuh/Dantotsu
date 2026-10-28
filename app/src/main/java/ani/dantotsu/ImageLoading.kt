package ani.dantotsu

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.ShimmerDrawable
import ani.dantotsu.util.shineCrossFade
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import jp.wasabeef.glide.transformations.BlurTransformation
import java.io.File

// Glide image loading into ImageViews: covers, banners and local files.

/**
 * A per-item shared-element transition name for a media cover. Every row in a RecyclerView
 * reusing the same static "mediaCover" name broke Activity shared-element transitions as soon as
 * more than one row was attached at once: Android re-resolves the shared element by name within
 * the departing window at transition-start time, not by the exact View reference passed to
 * ActivityOptions, so it always latched onto whichever same-named view it found first (in
 * practice, always the first list row) regardless of which item was actually tapped.
 */
fun mediaCoverTransitionName(id: Any?): String = "mediaCover_$id"

/**
 * Wraps a Glide RequestListener around [onReady] so it fires once regardless of success/failure —
 * used to gate a postponed shared-element transition on the cover actually having pixels, instead
 * of releasing the transition while the ImageView is still blank and animating nothing visible.
 */
private fun onReadyListener(onReady: (() -> Unit)?): RequestListener<Drawable>? = onReady?.let { cb ->
    object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: com.bumptech.glide.load.engine.GlideException?,
            model: Any?,
            target: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            cb()
            return false
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>?,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            cb()
            return false
        }
    }
}

fun ImageView.loadImage(url: String?, size: Int = 0) {
    if (!url.isNullOrEmpty()) {
        val localFile = File(url)
        if (localFile.exists()) {
            loadLocalImage(localFile, size)
        } else {
            loadImage(FileUrl(url), size)
        }
    } else {
        Glide.with(this).clear(this)
        setImageDrawable(null)
    }
}

fun ImageView.loadImage(file: FileUrl?, size: Int = 0) {
    file?.url = PrefManager.getVal<String>(PrefName.ImageUrl).ifEmpty { file?.url ?: "" }
    if (file?.url?.isNotEmpty() == true) {
        tryWith {
            if (file.url.startsWith("content://")) {
                Glide.with(this.context).load(Uri.parse(file.url))
                .placeholder(ShimmerDrawable.loading(context)).transition(shineCrossFade)
                    .override(size).into(this)
            } else {
                val glideUrl = GlideUrl(file.url) { file.headers }
                Glide.with(this.context).load(glideUrl)
                .placeholder(ShimmerDrawable.loading(context)).transition(shineCrossFade).override(size)
                    .into(this)
            }
        }
    } else {
        Glide.with(this).clear(this)
        setImageDrawable(null)
    }
}

/**
 * Like [ImageView.loadImage], but invokes [onReady] once the load resolves (success or failure)
 * instead of leaving it fire-and-forget. Used to gate a postponed shared-element transition on
 * the cover actually having pixels — Glide never resolves synchronously, even on a cache hit, so
 * releasing the transition purely on layout timing can animate an empty view.
 */
fun ImageView.loadCoverImage(url: String?, onReady: () -> Unit) {
    if (url.isNullOrEmpty()) {
        Glide.with(this).clear(this)
        setImageDrawable(null)
        onReady()
        return
    }
    val localFile = File(url)
    if (localFile.exists()) {
        loadLocalImage(localFile, onReady = onReady)
        return
    }
    val file = FileUrl(url).also { it.url = PrefManager.getVal<String>(PrefName.ImageUrl).ifEmpty { it.url } }
    if (file.url.isEmpty()) {
        Glide.with(this).clear(this)
        setImageDrawable(null)
        onReady()
        return
    }
    tryWith {
        val listener = onReadyListener(onReady)
        if (file.url.startsWith("content://")) {
            Glide.with(this.context).load(Uri.parse(file.url))
                .placeholder(ShimmerDrawable.loading(context)).transition(shineCrossFade)
                .listener(listener).into(this)
        } else {
            val glideUrl = GlideUrl(file.url) { file.headers }
            Glide.with(this.context).load(glideUrl)
                .placeholder(ShimmerDrawable.loading(context)).transition(shineCrossFade)
                .listener(listener).into(this)
        }
    }
}

/** [loadCoverImage] overload for sources (e.g. extension CDNs) that need [file]'s auth headers. */
fun ImageView.loadCoverImage(file: FileUrl?, onReady: () -> Unit) {
    if (file == null) {
        Glide.with(this).clear(this)
        setImageDrawable(null)
        onReady()
        return
    }
    file.url = PrefManager.getVal<String>(PrefName.ImageUrl).ifEmpty { file.url }
    if (file.url.isEmpty()) {
        Glide.with(this).clear(this)
        setImageDrawable(null)
        onReady()
        return
    }
    tryWith {
        val listener = onReadyListener(onReady)
        if (file.url.startsWith("content://")) {
            Glide.with(this.context).load(Uri.parse(file.url))
                .placeholder(ShimmerDrawable.loading(context)).transition(shineCrossFade)
                .listener(listener).into(this)
        } else {
            val glideUrl = GlideUrl(file.url) { file.headers }
            Glide.with(this.context).load(glideUrl)
                .placeholder(ShimmerDrawable.loading(context)).transition(shineCrossFade)
                .listener(listener).into(this)
        }
    }
}

fun ImageView.loadImage(file: FileUrl?, width: Int = 0, height: Int = 0) {
    file?.url = PrefManager.getVal<String>(PrefName.ImageUrl).ifEmpty { file?.url ?: "" }
    if (file?.url?.isNotEmpty() == true) {
        tryWith {
            if (file.url.startsWith("content://")) {
                Glide.with(this.context).load(Uri.parse(file.url))
                .placeholder(ShimmerDrawable.loading(context)).transition(shineCrossFade)
                    .override(width, height).into(this)
            } else {
                val glideUrl = GlideUrl(file.url) { file.headers }
                Glide.with(this.context).load(glideUrl)
                .placeholder(ShimmerDrawable.loading(context)).transition(shineCrossFade)
                    .override(width, height)
                    .into(this)
            }
        }
    } else {
        Glide.with(this).clear(this)
        setImageDrawable(null)
    }
}


fun ImageView.loadLocalImage(file: File?, size: Int = 0, onReady: (() -> Unit)? = null) {
    if (file?.exists() == true) {
        tryWith {
            val listener = onReadyListener(onReady)
            Glide.with(this.context).load(file).transition(withCrossFade())
                .let { if (listener != null) it.listener(listener) else it }
                .override(size)
                .into(this)
        }
    } else {
        onReady?.invoke()
    }
}

fun blurImage(imageView: ImageView, banner: String?, headers: Map<String, String> = emptyMap()) {
    if (banner != null) {
        val radius = PrefManager.getVal<Float>(PrefName.BlurRadius).toInt()
        val sampling = PrefManager.getVal<Float>(PrefName.BlurSampling).toInt()
        val context = imageView.context
        // Safely resolve Activity from context (context can be a ContextThemeWrapper in dialogs)
        val activity = when (context) {
            is Activity -> context
            is android.content.ContextWrapper -> {
                var base = context
                var act: Activity? = null
                while (base is android.content.ContextWrapper) {
                    if (base is Activity) { act = base; break }
                    val b = base.baseContext
                    if (b is android.content.ContextWrapper) base = b else { base = object : android.content.ContextWrapper(b) {} }
                }
                act
            }
            else -> null
        }
        // Proceed if activity is not destroyed or if we couldn't resolve an Activity (use context directly)
        if (activity == null || !activity.isDestroyed) {
            val url = PrefManager.getVal<String>(PrefName.ImageUrl).ifEmpty { banner }
            val model: Any = when {
                banner.startsWith("http") -> if (headers.isNotEmpty()) GlideUrl(url) { headers } else GlideUrl(url)
                banner.startsWith("content://") -> Uri.parse(url)
                else -> File(url)
            }
            if (PrefManager.getVal(PrefName.BlurBanners)) {
                Glide.with(context as Context)
                    .load(model)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE).override(400)
                    .apply(RequestOptions.bitmapTransform(BlurTransformation(radius, sampling)))
                    .into(imageView)

            } else {
                Glide.with(context as Context)
                    .load(model)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE).override(400)
                    .into(imageView)
            }
        }
    } else {
        imageView.setImageResource(R.drawable.linear_gradient_bg)
    }
}
