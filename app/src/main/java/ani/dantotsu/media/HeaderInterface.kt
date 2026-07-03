package ani.dantotsu.media

import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.databinding.ItemSearchHeaderBinding
import ani.dantotsu.stripSpansOnPaste

abstract class HeaderInterface : RecyclerView.Adapter<HeaderInterface.SearchHeaderViewHolder>() {
    private val itemViewType = 6969
    var search: Runnable? = null
    var requestFocus: Runnable? = null
    protected var textWatcher: TextWatcher? = null
    protected lateinit var searchHistoryAdapter: SearchHistoryAdapter
    protected lateinit var binding: ItemSearchHeaderBinding

    private val _ready = MutableLiveData(false)

    // Fires once this header's view holder has been bound (and its search/requestFocus
    // Runnables populated). The header is always item 0, so unlike a footer item, it is
    // guaranteed to bind on first layout regardless of RecyclerView content height.
    val ready: LiveData<Boolean> get() = _ready

    protected fun markReady() {
        // Use postValue: this runs from onBindViewHolder, while the RecyclerView is still
        // mid-layout. Dispatching synchronously here (setValue) lets observers trigger
        // notifyDataSetChanged() reentrantly and crash with "Cannot call this method while
        // RecyclerView is computing a layout or scrolling". postValue defers dispatch until
        // after the current layout pass finishes.
        if (_ready.value != true) _ready.postValue(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchHeaderViewHolder {
        val binding =
            ItemSearchHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.searchBarText.stripSpansOnPaste()
        return SearchHeaderViewHolder(binding)
    }

    fun setHistoryVisibility(visible: Boolean) {
        if (visible) {
            binding.searchResultLayout.startAnimation(fadeOutAnimation())
            binding.searchHistoryList.startAnimation(fadeInAnimation())
            binding.searchResultLayout.visibility = View.GONE
            binding.searchHistoryList.visibility = View.VISIBLE
            binding.searchByImage.visibility = View.VISIBLE
        } else {
            if (binding.searchResultLayout.visibility != View.VISIBLE) {
                binding.searchResultLayout.startAnimation(fadeInAnimation())
                binding.searchHistoryList.startAnimation(fadeOutAnimation())
            }

            binding.searchResultLayout.visibility = View.VISIBLE
            binding.clearHistory.visibility = View.GONE
            binding.searchHistoryList.visibility = View.GONE
            binding.searchByImage.visibility = View.GONE
        }
    }

    private fun fadeInAnimation(): Animation {
        return AlphaAnimation(0f, 1f).apply {
            duration = 150
        }
    }

    protected fun fadeOutAnimation(): Animation {
        return AlphaAnimation(1f, 0f).apply {
            duration = 150
        }
    }

    protected fun updateClearHistoryVisibility() {
        binding.clearHistory.visibility =
            if (searchHistoryAdapter.itemCount > 0) View.VISIBLE else View.GONE
    }

    fun addHistory() {
        if (::searchHistoryAdapter.isInitialized && binding.searchBarText.text.toString()
                .isNotBlank()
        ) searchHistoryAdapter.add(binding.searchBarText.text.toString())
    }

    // Return the current textual content of the header's search bar, or null if blank.
    fun getSearchText(): String? {
        return binding.searchBarText.text.toString().takeIf { it.isNotBlank() }
    }

    inner class SearchHeaderViewHolder(val binding: ItemSearchHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = 1

    override fun getItemViewType(position: Int): Int {
        return itemViewType
    }
}