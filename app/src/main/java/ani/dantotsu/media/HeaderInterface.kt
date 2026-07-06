package ani.dantotsu.media

import android.text.TextWatcher
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ani.dantotsu.databinding.ItemSearchHeaderBinding
import ani.dantotsu.stripSpansOnPaste

abstract class HeaderInterface {
    var search: Runnable? = null
    var requestFocus: Runnable? = null
    protected var textWatcher: TextWatcher? = null
    protected lateinit var searchHistoryAdapter: SearchHistoryAdapter
    protected lateinit var binding: ItemSearchHeaderBinding

    private val _ready = MutableLiveData(false)

    // Fires once the header has been bound (and its search/requestFocus Runnables
    // populated). The header now lives outside the results RecyclerView as a fixed
    // view, so it is bound exactly once during activity setup.
    val ready: LiveData<Boolean> get() = _ready

    protected fun markReady() {
        if (_ready.value != true) _ready.postValue(true)
    }

    fun attach(binding: ItemSearchHeaderBinding) {
        this.binding = binding
        binding.searchBarText.stripSpansOnPaste()
        bind()
    }

    protected abstract fun bind()

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
}
