package ani.dantotsu.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.graphics.Color
import android.view.Window
import androidx.cardview.widget.CardView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.comick.ComickApi
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mangaupdates.AniListQuickSearchDialogFragment
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.databinding.ActivityMediaEquivalentsBinding
import ani.dantotsu.loadImage
import ani.dantotsu.initActivity
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bumptech.glide.Glide
import java.util.Locale

class MediaEquivalentsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaEquivalentsBinding
    private lateinit var adapter: EquivalentAdapter

    companion object {
        var passedMuMedia: ArrayList<MUMedia>? = null
    }

    data class EquivalentItem(
        val mu: MUMedia,
        var matchedTitle: String? = null,
        var matchedCoverB2key: String? = null,
        var matchedAniCoverUrl: String? = null,
        var matchedAniId: Int? = null,
        var matchedAniType: String? = null // "anime" or "manga"
    )

    private lateinit var recycler: RecyclerView
    private val items = mutableListOf<EquivalentItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        // Inflate binding early so we can adjust window features before adding content
        binding = ActivityMediaEquivalentsBinding.inflate(layoutInflater)

        // Standard CoordinatorLayout/AppBar behavior used; no manual top padding applied.

        // back button
        binding.root.findViewById<View?>(R.id.back)?.setOnClickListener { finish() }

        // If immersive mode is enabled, ensure appbar isn't under the status bar
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            this.window.statusBarColor =
                ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true

        } else {
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }

        // Now set the activity content view
        setContentView(binding.root)

        val muList = passedMuMedia ?: arrayListOf()
        passedMuMedia = null
        for (m in muList) items.add(EquivalentItem(m))

        adapter = EquivalentAdapter(items)
        recycler = binding.equivalentsRecyclerView
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        binding.quickSearchNoResults.visibility = View.GONE

        // Prefetch MU details (covers) for items that lack a coverUrl
        val idsToFetch = items.mapNotNull { item -> item.mu.id.takeIf { item.mu.coverUrl.isNullOrBlank() } }
        if (idsToFetch.isNotEmpty()) {
            MUDetailsCache.prefetch(lifecycleScope, idsToFetch) { id ->
                // find all positions with this MU id and refresh them
                val positions = items.mapIndexedNotNull { idx, it -> if (it.mu.id == id) idx else null }
                positions.forEach { pos -> runOnUiThread { adapter.notifyItemChanged(pos) } }
            }
        }

        // Launch searches for each entry
        items.forEachIndexed { idx, it ->
            lifecycleScope.launch {
                try {
                    val title = it.mu.title ?: return@launch
                    // Check for a user-saved Comick slug for this MU series.
                    // Different parts of the app may store the key as the masked int id or the raw MU id,
                    // so try a few variants to be resilient.
                    val maskedId = ((it.mu.id) and 0x7FFFFFFF).toInt()
                    val candidateKeys = listOf(
                        "comick_slug_$maskedId",
                        "comick_slug_${it.mu.id}",
                        "comick_slug_${it.mu.id.toInt()}"
                    )
                    var savedSlug: String? = null
                    for (k in candidateKeys) {
                        try {
                            savedSlug = PrefManager.getNullableCustomVal(k, null, String::class.java)
                        } catch (e: Exception) {
                            Logger.log("MediaEquivalents: Pref lookup error for key=$k -> ${e.message}")
                        }
                        Logger.log("MediaEquivalents: tried pref key=$k -> ${if (savedSlug.isNullOrBlank()) "<null>" else savedSlug}")
                        if (!savedSlug.isNullOrBlank()) {
                            break
                        }
                    }

                    // Resolve the AniList equivalent of this MangaUpdates series. Prefer MangaBaka's
                    // cross-source mapping (reliable id linking); fall back to Comick when MangaBaka
                    // has no matching series or no AniList link.
                    var usedAni: ani.dantotsu.media.Media? = null

                    val mbAniId = withContext(Dispatchers.IO) {
                        ani.dantotsu.connections.mangabaka.MangaBakaApi.getAnilistIdFromMangaUpdates(it.mu.id)
                    }
                    if (mbAniId != null) {
                        usedAni = try {
                            withContext(Dispatchers.IO) { Anilist.query.getMedia(mbAniId, false) }
                        } catch (_: Exception) { null }
                    }

                    if (usedAni == null) {
                        val slug = if (!savedSlug.isNullOrBlank()) savedSlug else withContext(Dispatchers.IO) { ComickApi.searchAndMatchComicByMuId(listOf(title), it.mu.id) }
                        if (!slug.isNullOrBlank()) {
                            val comic = withContext(Dispatchers.IO) { ComickApi.getComicDetails(slug) }?.comic
                            val alId = comic?.links?.al?.takeIf { s -> s.isNotBlank() }?.toIntOrNull()
                            if (alId != null) {
                                usedAni = try {
                                    withContext(Dispatchers.IO) { Anilist.query.getMedia(alId, false) }
                                } catch (_: Exception) { null }
                            }
                        }
                    }

                    if (usedAni != null) {
                        it.matchedTitle = usedAni.userPreferredName.ifBlank { usedAni.mainName() }
                        it.matchedAniCoverUrl = usedAni.cover
                        it.matchedCoverB2key = null
                        it.matchedAniId = usedAni.id
                        it.matchedAniType = if (usedAni.anime != null) "anime" else "manga"
                    } else {
                        it.matchedTitle = getString(R.string.no_anilist_id_found)
                        it.matchedAniCoverUrl = null
                        it.matchedCoverB2key = null
                        it.matchedAniId = null
                        it.matchedAniType = null
                    }
                } catch (_: Exception) {}
                // Float entries with a found equivalent to the top as they resolve (even late ones).
                val from = items.indexOf(it)
                if (from < 0) return@launch
                if (it.matchedAniId != null && from > 0) {
                    items.removeAt(from)
                    items.add(0, it)
                    adapter.notifyItemMoved(from, 0)
                    adapter.notifyItemChanged(0)
                    // Keep the moved item on screen only if the user is already at the top;
                    // don't yank them back up if they've scrolled down.
                    val lm = recycler.layoutManager as? LinearLayoutManager
                    if (lm != null && lm.findFirstCompletelyVisibleItemPosition() <= 0) {
                        recycler.scrollToPosition(0)
                    }
                } else {
                    adapter.notifyItemChanged(from)
                }
            }
        }
    }

    private fun buildQuickCandidates(mu: MUMedia): List<String> {
        val list = mutableListOf<String>()
        fun add(value: String?) {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
            list.add(v)
        }

        add(mu.title)
        MangaUpdates.synonymsCache[mu.id].orEmpty().forEach { add(it) }
        return list.distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun openQuickSearchPickerForUnmatched() {
        val noAnilistText = getString(R.string.no_anilist_id_found)

        val unmatchedIndexes = items.mapIndexedNotNull { idx, item ->
            val unresolved = item.matchedAniId == null && item.matchedTitle == noAnilistText
            if (unresolved) idx else null
        }

        if (unmatchedIndexes.isEmpty()) {
            toast(getString(R.string.no_results_found))
            return
        }

        val picker = CustomBottomDialog.newInstance().apply {
            setTitleText(getString(R.string.quick_search))
        }

        unmatchedIndexes.forEach { idx ->
            val item = items[idx]
            val row = android.widget.Button(this).apply {
                text = item.mu.title ?: "MU #${item.mu.id}"
                textSize = 16f
                isAllCaps = false
                setPadding(32, 20, 32, 20)
                setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface))
                setOnClickListener {
                    picker.dismiss()
                    val candidates = buildQuickCandidates(item.mu)
                    AniListQuickSearchDialogFragment
                        .newInstance(ArrayList(candidates))
                        .show(supportFragmentManager, "equiv_quick_sheet_$idx")
                }
            }
            picker.addView(row)
        }

        picker.setNegativeButton(getString(R.string.cancel)) {
            picker.dismiss()
        }
        picker.show(supportFragmentManager, "equiv_quick_picker")
    }

    private inner class EquivalentAdapter(private val list: List<EquivalentItem>) : RecyclerView.Adapter<EquivalentAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val muCover: ImageView = view.findViewById(R.id.muCover)
            val muCoverCard: CardView = view.findViewById(R.id.muCoverCard)
            val muTitle: TextView = view.findViewById(R.id.muTitle)
            val matchCover: ImageView = view.findViewById(R.id.matchCover)
            val matchCoverCard: CardView = view.findViewById(R.id.matchCoverCard)
            val matchTitle: TextView = view.findViewById(R.id.matchTitle)
            val convertButton: View = view.findViewById(R.id.convertButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_equivalent_pair, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.muTitle.text = item.mu.title ?: ""
            // MU side: open MUMediaDetailsActivity when clicked
            val ctx = holder.itemView.context
            holder.muCoverCard.isClickable = true
            holder.muCoverCard.setOnClickListener {
                try {
                    val intent = android.content.Intent(ctx, ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity::class.java)
                    intent.putExtra("muMedia", item.mu as java.io.Serializable)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
            holder.muTitle.isClickable = true
            holder.muTitle.setOnClickListener {
                try {
                    val intent = android.content.Intent(ctx, ani.dantotsu.connections.mangaupdates.MUMediaDetailsActivity::class.java)
                    intent.putExtra("muMedia", item.mu as java.io.Serializable)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            }
            val muCoverUrl = item.mu.coverUrl ?: MUDetailsCache.get(item.mu.id)?.coverUrl
            if (muCoverUrl.isNullOrBlank()) {
                Logger.log("MediaEquivalents: MU cover missing for id=${item.mu.id}; item.coverUrl=${item.mu.coverUrl}")
            }
            if (!muCoverUrl.isNullOrBlank()) {
                holder.muCoverCard.setCardBackgroundColor(Color.TRANSPARENT)
                // load via helper
                holder.muCover.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(holder.muCover.context).clear(holder.muCover)
                holder.muCover.loadImage(muCoverUrl)
            } else {
                // ensure no stale image remains and show empty card background
                Glide.with(holder.muCover.context).clear(holder.muCover)
                holder.muCover.setImageDrawable(null)
                holder.muCoverCard.setCardBackgroundColor(holder.muCover.context.getThemeColor(com.google.android.material.R.attr.colorSurface))
            }

            if (item.matchedTitle == null) {
                // placeholder (searching): gray card with centered search icon
                holder.matchTitle.text = getString(R.string.searching)
                Glide.with(holder.matchCover.context).clear(holder.matchCover)
                holder.matchCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                holder.matchCover.setImageResource(R.drawable.ic_round_image_search_24)
                holder.matchCover.imageTintList = android.content.res.ColorStateList.valueOf(
                    getThemeColor(com.google.android.material.R.attr.colorOutline)
                )
                holder.matchCoverCard.setCardBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorSurface))
                // Tapping the placeholder starts a quick search for a match.
                holder.matchCoverCard.isClickable = true
                holder.matchCoverCard.setOnClickListener { startQuickSearch(item, holder.bindingAdapterPosition) }
                holder.matchTitle.isClickable = false
                holder.matchTitle.setOnClickListener(null)
            } else {
                holder.matchTitle.text = item.matchedTitle
                // Make title and cover clickable when we have an AniList id
                val aniId = item.matchedAniId
                if (aniId != null) {
                    // Open MediaDetailsActivity for matched AniList media
                    holder.matchCoverCard.isClickable = true
                    holder.matchCoverCard.setOnClickListener {
                        try {
                            val i = android.content.Intent(ctx, ani.dantotsu.media.MediaDetailsActivity::class.java)
                            i.putExtra("mediaId", aniId)
                            ctx.startActivity(i)
                        } catch (_: Exception) {}
                    }
                    holder.matchTitle.isClickable = true
                    holder.matchTitle.setOnClickListener {
                        try {
                            val i = android.content.Intent(ctx, ani.dantotsu.media.MediaDetailsActivity::class.java)
                            i.putExtra("mediaId", aniId)
                            ctx.startActivity(i)
                        } catch (_: Exception) {}
                    }
                } else {
                    // No AniList match: tapping the placeholder cover starts a quick search.
                    holder.matchCoverCard.isClickable = true
                    holder.matchCoverCard.setOnClickListener { startQuickSearch(item, holder.bindingAdapterPosition) }
                    holder.matchTitle.isClickable = false
                    holder.matchTitle.setOnClickListener(null)
                }
                val aniCover = item.matchedAniCoverUrl
                val b2 = item.matchedCoverB2key
                when {
                    !aniCover.isNullOrBlank() -> {
                        // real AniList cover: clear card background and show image cropped
                        holder.matchCoverCard.setCardBackgroundColor(Color.TRANSPARENT)
                        holder.matchCover.imageTintList = null
                        holder.matchCover.scaleType = ImageView.ScaleType.CENTER_CROP
                        Glide.with(holder.matchCover.context).clear(holder.matchCover)
                        holder.matchCover.loadImage(aniCover)
                    }
                    !b2.isNullOrBlank() -> {
                        // Comick thumb (shouldn't be used per recent change, but keep fallback defensive)
                        holder.matchCoverCard.setCardBackgroundColor(Color.TRANSPARENT)
                        holder.matchCover.imageTintList = null
                        holder.matchCover.scaleType = ImageView.ScaleType.CENTER_CROP
                        Glide.with(holder.matchCover.context).clear(holder.matchCover)
                        val thumb = buildThumbUrl(b2)
                        holder.matchCover.loadImage(thumb)
                    }
                    else -> {
                        // No AniList match: gray card with the AniList search icon (tap to quick-search)
                        Glide.with(holder.matchCover.context).clear(holder.matchCover)
                        holder.matchCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        holder.matchCover.setImageResource(R.drawable.ic_anilist_search_24)
                        holder.matchCover.imageTintList = android.content.res.ColorStateList.valueOf(
                            getThemeColor(com.google.android.material.R.attr.colorOutline)
                        )
                        holder.matchCoverCard.setCardBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorSurface))
                    }
                }
            }

            // One-tap convert: only when the MU series is in the user's list and has an AniList match.
            val aniId = item.matchedAniId
            val convertible = aniId != null && item.mu.listId in 0..4
            // INVISIBLE (not GONE) so the middle column always reserves the same width and the
            // cover columns line up whether or not a row has a convert button.
            holder.convertButton.visibility = if (convertible) View.VISIBLE else View.INVISIBLE
            holder.convertButton.setOnClickListener {
                if (aniId == null) return@setOnClickListener
                holder.convertButton.isEnabled = false
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        ani.dantotsu.connections.mangaupdates.convertMuToAnilist(
                            muSeriesId = item.mu.id,
                            muListId = item.mu.listId,
                            anilistId = aniId,
                            chapter = item.mu.userChapter,
                            volume = item.mu.userVolume,
                            addedAt = item.mu.addedAt,
                        )
                    }
                    if (ok) {
                        toast(getString(R.string.converted_to_anilist))
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            items.removeAt(pos)
                            notifyItemRemoved(pos)
                        }
                    } else {
                        toast(getString(R.string.list_update_failed))
                        holder.convertButton.isEnabled = true
                    }
                }
            }
        }

        override fun getItemCount(): Int = list.size

        /** Opens the AniList quick-search sheet for a MangaUpdates entry with no auto-detected match. */
        private fun startQuickSearch(item: EquivalentItem, position: Int) {
            val candidates = buildQuickCandidates(item.mu)
            AniListQuickSearchDialogFragment
                .newInstance(ArrayList(candidates))
                .show(supportFragmentManager, "equiv_row_quick_sheet_$position")
        }

        private fun buildThumbUrl(b2key: String): String {
            val dotIdx = b2key.lastIndexOf('.')
            return if (dotIdx >= 0) {
                val base = b2key.substring(0, dotIdx)
                val ext = b2key.substring(dotIdx)
                "https://meo.comick.pictures/${base}-s${ext}"
            } else {
                "https://meo.comick.pictures/$b2key"
            }
        }
    }
}
