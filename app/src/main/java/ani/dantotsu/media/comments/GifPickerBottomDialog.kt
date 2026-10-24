package ani.dantotsu.media.comments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.BuildConfig
import ani.dantotsu.client
import ani.dantotsu.databinding.BottomSheetGifPickerBinding
import ani.dantotsu.databinding.ItemGifBinding
import ani.dantotsu.loadImage
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * GIF search for the comment box, from Klipy (its Tenor-compatible v2 API): trending until
 * something is typed, then results for it. Picking one hands its URL to [onGifSelected].
 *
 * The key is the app's own, from the build config ([isAvailable] is false in a build without
 * one, and the comment box then shows no GIF button).
 */
class GifPickerBottomDialog : BottomSheetDialogFragment() {
    private var _binding: BottomSheetGifPickerBinding? = null
    private val binding get() = _binding!!

    var onGifSelected: ((String) -> Unit)? = null
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetGifPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.gifRecycler.layoutManager = GridLayoutManager(context, COLUMNS)
        binding.gifSearchInput.doAfterTextChanged { text ->
            searchJob?.cancel()
            val query = text?.toString()?.trim().orEmpty()
            // Waits for typing to pause rather than searching every keystroke.
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(SEARCH_DELAY_MS)
                load(query)
            }
        }
        load("")
    }

    private fun load(query: String) {
        binding.gifProgressBar.isVisible = true
        binding.gifEmpty.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            val gifs = withContext(Dispatchers.IO) { fetch(query) }
            val b = _binding ?: return@launch
            b.gifProgressBar.isVisible = false
            b.gifEmpty.isVisible = gifs.isEmpty()
            b.gifRecycler.adapter = GifAdapter(gifs) { url ->
                onGifSelected?.invoke(url)
                dismiss()
            }
        }
    }

    private suspend fun fetch(query: String): List<String> = try {
        val common = "key=${BuildConfig.KLIPY_API_KEY}&media_filter=tinygif&limit=$LIMIT&contentfilter=high"
        val url = if (query.isEmpty()) "$BASE_URL/v2/featured?$common"
        else "$BASE_URL/v2/search?$common&q=${URLEncoder.encode(query, "UTF-8")}"
        parse(client.get(url).text)
    } catch (e: Exception) {
        Logger.log(e)
        emptyList()
    }

    /** The small-GIF URL of each result, falling back to the result's own. */
    private fun parse(json: String): List<String> = runCatching {
        val results = (Json.parseToJsonElement(json) as? JsonObject)?.get("results") as? JsonArray
        results.orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val tiny = (item["media_formats"] as? JsonObject)?.get("tinygif") as? JsonObject
            ((tiny?.get("url") ?: item["url"]) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    override fun onDestroyView() {
        searchJob?.cancel()
        _binding?.gifRecycler?.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private class GifAdapter(
        private val gifs: List<String>,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<GifAdapter.Holder>() {
        class Holder(val binding: ItemGifBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemGifBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val url = gifs[position]
            holder.binding.gifImage.loadImage(url)
            holder.binding.root.setOnClickListener { onClick(url) }
        }

        override fun getItemCount() = gifs.size
    }

    companion object {
        private const val BASE_URL = "https://api.klipy.com"
        private const val COLUMNS = 2
        private const val LIMIT = 30
        private const val SEARCH_DELAY_MS = 500L

        /** Whether this build has a Klipy key to search with. */
        val isAvailable: Boolean get() = BuildConfig.KLIPY_API_KEY.isNotBlank()
    }
}
