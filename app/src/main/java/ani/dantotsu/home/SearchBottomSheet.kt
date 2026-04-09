package ani.dantotsu.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType
import ani.dantotsu.connections.anilist.AnilistSearch.SearchType.Companion.toAnilistString
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.databinding.BottomSheetSearchBinding
import ani.dantotsu.media.SearchActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

class SearchBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSearchBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_QUERY = "query"

        fun newInstance(query: String?): SearchBottomSheet {
            val f = SearchBottomSheet()
            val args = Bundle()
            args.putString(ARG_QUERY, query)
            f.arguments = args
            return f
        }
        fun newInstance(): SearchBottomSheet = newInstance(null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentQuery = arguments?.getString(ARG_QUERY)

        binding.animeSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.ANIME, currentQuery)
            dismiss()
        }
        binding.mangaSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.MANGA, currentQuery)
            dismiss()
        }
        binding.characterSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.CHARACTER, currentQuery)
            dismiss()
        }
        binding.staffSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.STAFF, currentQuery)
            dismiss()
        }
        binding.studioSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.STUDIO, currentQuery)
            dismiss()
        }
        binding.userSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.USER, currentQuery)
            dismiss()
        }
        binding.muSearch.visibility = if (MangaUpdates.token != null) View.VISIBLE else View.GONE
        binding.muSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.MANGAUPDATES, currentQuery)
            dismiss()
        }
        binding.comickSearch.visibility = if (PrefManager.getVal<Boolean>(PrefName.ComickEnabled)) View.VISIBLE else View.GONE
        binding.comickSearch.setOnClickListener {
            startActivity(requireContext(), SearchType.COMICK, currentQuery)
            dismiss()
        }
    }

    private fun startActivity(context: Context, type: SearchType, query: String?) {
        val intent = Intent(context, SearchActivity::class.java).putExtra("type", type.toAnilistString())
        if (!query.isNullOrBlank()) intent.putExtra("query", query)
        // If opened from an existing SearchActivity, only copy the current textual search
        val src = activity as? SearchActivity
        if (src != null) {
            val srcSearchText = src.getHeaderSearchText()
            if (!srcSearchText.isNullOrBlank()) intent.putExtra("query", srcSearchText)
        }

        // If a query is present, request the new activity to perform the search immediately
        if (intent.getStringExtra("query") != null) intent.putExtra("search", true)

        ContextCompat.startActivity(context, intent, null)
        // If the bottom sheet was opened from an existing SearchActivity, finish it
        // so the new SearchActivity doesn't stack on top of the old one.
        if (src != null) {
            src.finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // companion object moved above
}