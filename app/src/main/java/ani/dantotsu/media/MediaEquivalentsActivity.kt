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
import ani.dantotsu.connections.comick.ComickCover
import ani.dantotsu.connections.mangaupdates.MUMedia
import ani.dantotsu.connections.mangaupdates.MUDetailsCache
import ani.dantotsu.databinding.ActivityMediaEquivalentsBinding
import ani.dantotsu.databinding.ActivityMediaListViewBinding
import ani.dantotsu.loadImage
import ani.dantotsu.initActivity
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.getThemeColor
import ani.dantotsu.hideSystemBarsExtendView
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bumptech.glide.Glide

class MediaEquivalentsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaEquivalentsBinding

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

        val adapter = EquivalentAdapter(items)
        recycler = binding.equivalentsRecyclerView
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

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

                    val slug = if (!savedSlug.isNullOrBlank()) savedSlug else withContext(Dispatchers.IO) { ComickApi.searchAndMatchComicByMuId(listOf(title), it.mu.id) }

                    if (!slug.isNullOrBlank()) {
                        val details = withContext(Dispatchers.IO) { ComickApi.getComicDetails(slug) }
                        val comic = details?.comic
                        if (comic != null) {
                            // Prefer AniList if Comick links include an AniList id
                            val al = comic.links?.al?.takeIf { it.isNotBlank() }
                            var usedAni: ani.dantotsu.media.Media? = null
                            if (!al.isNullOrBlank()) {
                                val alId = al.toIntOrNull()
                                if (alId != null) {
                                    try {
                                        usedAni = withContext(Dispatchers.IO) { Anilist.query.getMedia(alId, false) }
                                    } catch (_: Exception) {}
                                }
                            }

                            if (usedAni != null) {
                                it.matchedTitle = usedAni.userPreferredName.ifBlank { usedAni.mainName() }
                                it.matchedAniCoverUrl = usedAni.cover
                                it.matchedCoverB2key = null
                                it.matchedAniId = usedAni.id
                                it.matchedAniType = if (usedAni.anime != null) "anime" else "manga"
                            } else {
                                // Do NOT fall back to Comick entries — indicate no AniList match
                                it.matchedTitle = getString(R.string.no_anilist_id_found)
                                it.matchedAniCoverUrl = null
                                it.matchedCoverB2key = null
                                it.matchedAniId = null
                                it.matchedAniType = null
                            }
                        } else {
                            // No Comick details found — mark as no Comick entry
                            it.matchedTitle = getString(R.string.no_comick_entry_found)
                            it.matchedAniCoverUrl = null
                            it.matchedCoverB2key = null
                            it.matchedAniId = null
                            it.matchedAniType = null
                        }
                    } else {
                        // No Comick slug found — mark as no Comick entry
                        it.matchedTitle = getString(R.string.no_comick_entry_found)
                        it.matchedAniCoverUrl = null
                        it.matchedCoverB2key = null
                        it.matchedAniId = null
                        it.matchedAniType = null
                    }
                } catch (_: Exception) {}
                adapter.notifyItemChanged(idx)
            }
        }
    }

    private inner class EquivalentAdapter(private val list: List<EquivalentItem>) : RecyclerView.Adapter<EquivalentAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val muCover: ImageView = view.findViewById(R.id.muCover)
            val muCoverCard: CardView = view.findViewById(R.id.muCoverCard)
            val muTitle: TextView = view.findViewById(R.id.muTitle)
            val matchCover: ImageView = view.findViewById(R.id.matchCover)
            val matchCoverCard: CardView = view.findViewById(R.id.matchCoverCard)
            val matchTitle: TextView = view.findViewById(R.id.matchTitle)
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
                    holder.matchCoverCard.isClickable = false
                    holder.matchCoverCard.setOnClickListener(null)
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
                        // No AniList match: show gray card + book icon
                        Glide.with(holder.matchCover.context).clear(holder.matchCover)
                        holder.matchCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        holder.matchCover.setImageResource(R.drawable.ic_round_no_icon_24)
                        holder.matchCover.imageTintList = android.content.res.ColorStateList.valueOf(
                            getThemeColor(com.google.android.material.R.attr.colorOutline)
                        )
                        holder.matchCoverCard.setCardBackgroundColor(getThemeColor(com.google.android.material.R.attr.colorSurface))
                    }
                }
            }
        }

        override fun getItemCount(): Int = list.size

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
