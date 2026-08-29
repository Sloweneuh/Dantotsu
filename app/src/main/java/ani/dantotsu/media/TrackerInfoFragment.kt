package ani.dantotsu.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Shared scaffolding for the external info tabs (Comick, MangaBaka, Kitsu, Simkl). Each of these
 * resolves the current AniList entry to a tracker record, then either renders it into the shared
 * [FragmentMediaInfoBinding] or shows a "no match" / "offline" placeholder. That flow was copied
 * four times; this owns it.
 *
 * @param T the tracker's fully-loaded model type.
 */
abstract class TrackerInfoFragment<T> : Fragment() {

    protected var _binding: FragmentMediaInfoBinding? = null
    protected val binding get() = _binding!!

    /** True once a load attempt has finished (success, no-match or error) — guards re-entry. */
    protected var loaded = false

    // ---- subclass contract -------------------------------------------------------------------

    /** Shown in place of the tab body when there is no connection. */
    @get:StringRes
    protected abstract val requiresInternetMessageRes: Int

    /** The "no match" placeholder page's contents. */
    protected abstract fun noData(media: Media): NoDataConfig

    /** Resolve [media] to the tracker record and fetch it. `null` ⇒ show the no-data page. */
    protected abstract suspend fun resolveAndFetch(media: Media, model: MediaDetailsViewModel): T?

    /** Populate [binding] (and append dynamic sections) from the loaded record. */
    protected abstract fun render(full: T, media: Media, model: MediaDetailsViewModel)

    data class NoDataConfig(
        @DrawableRes val logoRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int,
        @StringRes val buttonTextRes: Int,
        val onButton: () -> Unit,
    )

    // ---- lifecycle -------------------------------------------------------------------------------

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMediaInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val model: MediaDetailsViewModel by activityViewModels()
        val offline = PrefManager.getVal<Boolean>(PrefName.OfflineMode) || !isOnline(requireContext())

        binding.mediaInfoContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += 128f.px + navBarHeight
        }
        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        if (offline) {
            loaded = true
            showError(getString(requiresInternetMessageRes))
            return
        }

        model.getMedia().observe(viewLifecycleOwner) { media ->
            val m = media ?: return@observe
            if (!loaded) load(m, model)
        }
    }

    private fun load(media: Media, model: MediaDetailsViewModel) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.mediaInfoProgressBar.visibility = View.VISIBLE
                binding.mediaInfoContainer.visibility = View.GONE

                val full = resolveAndFetch(media, model)
                if (_binding == null) return@launch

                if (full == null) {
                    loaded = true
                    showNoData(noData(media))
                    return@launch
                }

                loaded = true
                binding.mediaInfoProgressBar.visibility = View.GONE
                binding.mediaInfoContainer.visibility = View.VISIBLE
                render(full, media, model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loaded = true
                Logger.log("${this@TrackerInfoFragment.javaClass.simpleName} error: ${e.message}")
                Logger.log(e)
                if (_binding != null) showNoData(noData(media))
            }
        }
    }

    // ---- placeholders (lifted verbatim from MangaBakaInfoFragment) ------------------------------

    protected fun showError(message: String) {
        if (_binding == null) return
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE
        (binding.mediaInfoContainer.parent as? ViewGroup)?.let { host ->
            val errorView = layoutInflater.inflate(android.R.layout.simple_list_item_1, host, false)
            (errorView as? TextView)?.apply {
                text = message
                val padding = 32f.px
                setPadding(padding, padding, padding, padding)
                textSize = 16f
            }
            host.addView(errorView)
        }
    }

    protected fun showNoData(config: NoDataConfig) {
        if (_binding == null) return
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        (binding.mediaInfoContainer.parent as? ViewGroup)?.let { host ->
            val pageView = layoutInflater.inflate(R.layout.fragment_nodata_page, host, false)
            pageView.findViewById<android.widget.ImageView>(R.id.logo)?.setImageResource(config.logoRes)
            pageView.findViewById<TextView>(R.id.title)?.setText(config.titleRes)
            pageView.findViewById<TextView>(R.id.subtitle)?.setText(config.descRes)
            pageView.findViewById<MaterialButton>(R.id.quickSearchButton)?.apply {
                setText(config.buttonTextRes)
                icon = ContextCompat.getDrawable(context, config.logoRes)
                setOnClickListener { config.onButton() }
            }
            host.addView(pageView)
        }
    }
}
