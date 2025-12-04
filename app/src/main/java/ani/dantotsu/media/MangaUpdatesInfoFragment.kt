package ani.dantotsu.media

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.FragmentMediaInfoBinding
import ani.dantotsu.databinding.ItemChipBinding
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.px
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import java.net.URLEncoder

class MangaUpdatesInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private var loaded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
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
        val offline: Boolean =
            PrefManager.getVal(PrefName.OfflineMode) || !isOnline(requireContext())

        binding.mediaInfoContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin += 128f.px + navBarHeight
        }

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        if (offline) {
            loaded = true
            showError("MangaUpdates requires an internet connection")
            return
        }

        // Observe both media and MU link
        var currentMedia: Media? = null

        model.getMedia().observe(viewLifecycleOwner) { media ->
            currentMedia = media
            if (media != null) {
                checkAndDisplay(model, media)
            }
        }

        model.mangaUpdatesLink.observe(viewLifecycleOwner) { muLink ->
            if (currentMedia != null) {
                checkAndDisplay(model, currentMedia!!)
            }
        }

        model.comickLoaded.observe(viewLifecycleOwner) { comickLoaded ->
            if (comickLoaded && currentMedia != null) {
                checkAndDisplay(model, currentMedia!!)
            }
        }
    }

    private fun checkAndDisplay(model: MediaDetailsViewModel, media: Media) {
        if (loaded) return // Only display once

        val muLink = model.mangaUpdatesLink.value
        val comickHasLoaded = model.comickLoaded.value == true

        // Wait for Comick to finish loading before making a decision
        if (!comickHasLoaded) {
            // Still waiting for Comick to load
            return
        }

        // Comick has finished loading, now we can decide what to show
        loaded = true
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE

        if (muLink != null) {
            // We have a MU link, show the styled button page
            showMangaUpdatesButton(muLink)
        } else {
            // No MU link found, show search buttons
            showNoDataWithSearch(media)
        }
    }

    private fun showError(message: String) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup
        frameLayout?.let {
            val errorView = layoutInflater.inflate(
                android.R.layout.simple_list_item_1,
                it,
                false
            )
            (errorView as? android.widget.TextView)?.apply {
                text = message
                val padding = 32f.px
                setPadding(padding, padding, padding, padding)
                textSize = 16f
            }
            it.addView(errorView)
        }
    }

    private fun showMangaUpdatesButton(muLink: String) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.GONE

        // Inflate the styled layout similar to MAL not logged in
        val frameLayout = binding.mediaInfoContainer.parent as? ViewGroup
        frameLayout?.let {
            val muView = layoutInflater.inflate(
                ani.dantotsu.R.layout.fragment_mangaupdates_page,
                it,
                false
            )

            // Set title text
            muView.findViewById<android.widget.TextView>(ani.dantotsu.R.id.mangaUpdatesTitle)?.text =
                buildString { append("Open on "); append("MangaUpdates") }

            // Set button click
            muView.findViewById<com.google.android.material.button.MaterialButton>(ani.dantotsu.R.id.mangaUpdatesButton)?.apply {
                text = buildString { append("Open "); append("MangaUpdates") }
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(muLink)))
                }
            }

            it.addView(muView)
        }
    }

    private fun showNoDataWithSearch(media: Media) {
        binding.mediaInfoProgressBar.visibility = View.GONE
        binding.mediaInfoContainer.visibility = View.VISIBLE

        val parent = binding.mediaInfoContainer
        parent.removeAllViews()

        // Get available titles
        val titles = ArrayList<String>()
        titles.add(media.userPreferredName)
        if (media.nameRomaji != media.userPreferredName) {
            titles.add(media.nameRomaji)
        }
        // Filter English titles
        val englishSynonyms = media.synonyms.filter { title ->
            if (title.isBlank()) return@filter false
            val hasCJK = title.any { char ->
                char.code in 0x3040..0x309F || char.code in 0x30A0..0x30FF ||
                char.code in 0x4E00..0x9FFF || char.code in 0xAC00..0xD7AF ||
                char.code in 0x1100..0x11FF
            }
            !hasCJK
        }
        englishSynonyms.forEach { if (!titles.contains(it)) titles.add(it) }

        // Use chip group style like the modal
        val bind = ani.dantotsu.databinding.ItemTitleChipgroupMultilineBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        bind.itemTitle.text = buildString { append("Search on "); append("MangaUpdates") }

        // Add chips for each title
        titles.forEach { title ->
            val chip = ani.dantotsu.databinding.ItemChipBinding.inflate(LayoutInflater.from(context), bind.itemChipGroup, false).root
            chip.text = title
            chip.setOnClickListener {
                val encoded = java.net.URLEncoder.encode(title, "utf-8").replace("+", "%20")
                val url = "https://www.mangaupdates.com/series?search=$encoded"
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }
            chip.setOnLongClickListener {
                copyToClipboard(title)
                android.widget.Toast.makeText(requireContext(), "Copied: $title", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            bind.itemChipGroup.addView(chip)
        }

        parent.addView(bind.root)
    }
}

