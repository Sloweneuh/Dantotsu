package ani.dantotsu.media

import android.app.Activity
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.widget.FrameLayout
import ani.dantotsu.databinding.ItemTitleTrailerBinding

/**
 * The YouTube trailer card used on the media info screens.
 *
 * It loads a thumbnail first and only swaps in the real embed once tapped, so opening an info
 * screen never starts a video or pulls the player's payload. Extracted from
 * [AniListInfoFragment] so the Comick tab shows the identical card rather than a second
 * implementation that would drift from it.
 */
object TrailerWebView {

    /**
     * Build a trailer card for [youtubeId].
     *
     * @param activity needed for the fullscreen callback, which parents the video to the window
     * @return the card's root view, ready to add to a container
     */
    fun create(
        activity: Activity,
        inflater: LayoutInflater,
        parent: ViewGroup,
        youtubeId: String
    ): View {
        val bind = ItemTitleTrailerBinding.inflate(inflater, parent, false)
        bind.mediaInfoTrailer.apply {
            visibility = View.VISIBLE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            settings.userAgentString = null
            isSoundEffectsEnabled = true
            webChromeClient = FullscreenChrome(activity)

            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun loadVideo() {
                    activity.runOnUiThread {
                        loadDataWithBaseURL(
                            BASE_URL, embedHtml(youtubeId), "text/html", "utf-8", null
                        )
                    }
                }
            }, "Android")

            loadDataWithBaseURL(
                BASE_URL, placeholderHtml(youtubeId), "text/html", "utf-8", null
            )
        }
        return bind.root
    }

    private const val BASE_URL = "https://www.youtube-nocookie.com"

    /** Hands the video to the window decor view so fullscreen actually fills the screen. */
    @Suppress("DEPRECATION")
    private class FullscreenChrome(private val activity: Activity) : WebChromeClient() {
        private var customView: View? = null
        private var callback: CustomViewCallback? = null
        private var originalSystemUiVisibility = 0

        override fun onHideCustomView() {
            (activity.window.decorView as FrameLayout).removeView(customView)
            customView = null
            activity.window.decorView.systemUiVisibility = originalSystemUiVisibility
            callback?.onCustomViewHidden()
            callback = null
        }

        override fun onShowCustomView(paramView: View, paramCallback: CustomViewCallback) {
            if (customView != null) {
                onHideCustomView()
                return
            }
            customView = paramView
            originalSystemUiVisibility = activity.window.decorView.systemUiVisibility
            callback = paramCallback
            (activity.window.decorView as FrameLayout).addView(
                customView, FrameLayout.LayoutParams(-1, -1)
            )
            activity.window.decorView.systemUiVisibility =
                3846 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun embedHtml(id: String) = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                * {
                    margin: 0;
                    padding: 0;
                    box-sizing: border-box;
                    -webkit-tap-highlight-color: transparent;
                }
                html, body {
                    width: 100%;
                    height: 100%;
                    background: #000;
                    overflow: hidden;
                }
                iframe {
                    width: 100%;
                    height: 100%;
                    border: none;
                    display: block;
                }
            </style>
        </head>
        <body>
            <iframe
                src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&rel=0&modestbranding=1&controls=1&fs=0"
                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                frameborder="0">
            </iframe>
        </body>
        </html>
    """.trimIndent()

    private fun placeholderHtml(id: String) = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                * {
                    margin: 0;
                    padding: 0;
                    box-sizing: border-box;
                    -webkit-tap-highlight-color: transparent;
                    -webkit-touch-callout: none;
                    -webkit-user-select: none;
                    user-select: none;
                }
                body, html {
                    width: 100%;
                    height: 100%;
                    background: #000;
                    overflow: hidden;
                }
                .thumbnail-container {
                    position: relative;
                    width: 100%;
                    height: 100%;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    background: #000;
                }
                .thumbnail {
                    width: 100%;
                    height: 100%;
                    object-fit: contain;
                }
                .play-button {
                    position: absolute;
                    width: 68px;
                    height: 48px;
                    background: rgba(255, 0, 0, 0.8);
                    border-radius: 12px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    transition: transform 0.2s;
                }
                .thumbnail-container:active .play-button {
                    transform: scale(0.95);
                }
                .play-icon {
                    width: 0;
                    height: 0;
                    border-left: 20px solid white;
                    border-top: 12px solid transparent;
                    border-bottom: 12px solid transparent;
                    margin-left: 4px;
                }
            </style>
        </head>
        <body>
            <div class="thumbnail-container" onclick="Android.loadVideo()">
                <img class="thumbnail" src="https://img.youtube.com/vi/$id/maxresdefault.jpg"
                     onerror="this.src='https://img.youtube.com/vi/$id/hqdefault.jpg'" alt="Trailer">
                <div class="play-button">
                    <div class="play-icon"></div>
                </div>
            </div>
        </body>
        </html>
    """.trimIndent()
}
