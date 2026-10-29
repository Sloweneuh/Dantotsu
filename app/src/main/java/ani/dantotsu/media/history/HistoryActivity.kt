package ani.dantotsu.media.history

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityHistoryBinding
import ani.dantotsu.databinding.ItemHistoryBinding
import ani.dantotsu.databinding.ItemHistoryHeaderBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/**
 * The episodes and chapters last opened on this device, one row per media, grouped by day. A row
 * opens its media page; the cross on it forgets that one entry.
 */
class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private val adapter = Adapter()

    private sealed interface Row {
        data class Header(val label: String) : Row
        data class Item(val entry: WatchHistory.Entry) : Row
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.historyLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.historyBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = adapter
        binding.historyClear.setOnClickListener {
            customAlertDialog().apply {
                setTitle(R.string.clear_history)
                setMessage(R.string.history_clear_confirm)
                setPosButton(R.string.clear_all) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { WatchHistory.clear() }
                        reload()
                    }
                }
                setNegButton(R.string.cancel)
                show()
            }
        }
    }

    // On every return, so an episode watched from here shows up at the top when coming back.
    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { WatchHistory.entries() }
            binding.historyEmpty.isVisible = entries.isEmpty()
            binding.historyClear.isVisible = entries.isNotEmpty()
            adapter.submitList(rows(entries))
        }
    }

    private fun rows(entries: List<WatchHistory.Entry>): List<Row> {
        val out = ArrayList<Row>(entries.size + 8)
        var lastDay: String? = null
        for (entry in entries) {
            val day = dayLabel(entry.at)
            if (day != lastDay) {
                out += Row.Header(day)
                lastDay = day
            }
            out += Row.Item(entry)
        }
        return out
    }

    private fun dayLabel(at: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val now = Calendar.getInstance()
        val sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val days = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && days == 0 -> getString(R.string.today)
            sameYear && days == 1 -> getString(R.string.yesterday)
            else -> DateUtils.formatDateTime(
                this, at,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                    (if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
            )
        }
    }

    private fun remove(entry: WatchHistory.Entry) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { WatchHistory.remove(entry) }
            reload()
        }
    }

    private inner class Adapter : ListAdapter<Row, RecyclerView.ViewHolder>(Diff) {

        override fun getItemViewType(position: Int) = if (getItem(position) is Row.Header) HEADER else ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == HEADER) {
                HeaderHolder(ItemHistoryHeaderBinding.inflate(inflater, parent, false))
            } else {
                ItemHolder(ItemHistoryBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.Header -> (holder as HeaderHolder).binding.historyHeader.text = row.label
                is Row.Item -> (holder as ItemHolder).bind(row.entry)
            }
        }
    }

    private class HeaderHolder(val binding: ItemHistoryHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class ItemHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: WatchHistory.Entry) = with(binding) {
            historyCover.loadImage(entry.cover)
            historyTitle.text = entry.title
            historyItemLine.text = entry.itemLine(root.context)
            val type = getString(
                when (entry.type) {
                    MediaType.ANIME -> R.string.anime
                    MediaType.MANGA -> R.string.manga
                    MediaType.NOVEL -> R.string.novel
                }
            )
            historyTime.text = "$type · ${DateFormat.getTimeFormat(root.context).format(Date(entry.at))}"
            historyItem.setOnClickListener { startActivity(entry.openIntent(root.context)) }
            historyRemove.setOnClickListener { remove(entry) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = when {
            oldItem is Row.Header && newItem is Row.Header -> oldItem.label == newItem.label
            oldItem is Row.Item && newItem is Row.Item ->
                oldItem.entry.mediaId == newItem.entry.mediaId && oldItem.entry.type == newItem.entry.type
            else -> false
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
    }

    private companion object {
        const val HEADER = 0
        const val ITEM = 1
    }
}
