package ani.dantotsu.parsers

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityParserTestBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import com.google.android.material.chip.Chip
import com.xwray.groupie.GroupieAdapter
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ParserTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParserTestBinding
    private val adapter = GroupieAdapter()
    private val extensionsToTest: MutableList<ExtensionTestItem> = mutableListOf()

    private val animeExtensionManager: AnimeExtensionManager = Injekt.get()
    private val mangaExtensionManager: MangaExtensionManager = Injekt.get()
    private val novelExtensionManager: NovelExtensionManager = Injekt.get()

    private var currentExtensionType = "anime"
    private var currentTestType = "basic"
    private var searchQuery = "Chainsaw Man"
    private val selectedExtensions = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityParserTestBinding.inflate(layoutInflater)
        binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.extensionResultsRecyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        setContentView(binding.root)

        setupViews()
        setupExtensionChips()
    }

    private fun setupViews() {
        binding.extensionResultsRecyclerView.adapter = adapter
        binding.extensionResultsRecyclerView.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.VERTICAL,
            false
        )

        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Extension Type Chips
        binding.animeChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentExtensionType = "anime"
                selectedExtensions.clear()
                updateSelectedCount()
                setupExtensionChips()
            }
        }

        binding.mangaChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentExtensionType = "manga"
                selectedExtensions.clear()
                updateSelectedCount()
                setupExtensionChips()
            }
        }

        binding.novelChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentExtensionType = "novel"
                selectedExtensions.clear()
                updateSelectedCount()
                setupExtensionChips()
            }
        }

        // Test Type Chips
        binding.pingChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) currentTestType = "ping"
        }

        binding.basicChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) currentTestType = "basic"
        }

        binding.fullChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) currentTestType = "full"
        }

        // Search Query
        binding.searchInputText.setText(searchQuery)
        binding.searchInputText.addTextChangedListener {
            searchQuery = it.toString()
        }

        // Select All Button
        binding.selectAllButton.setOnClickListener {
            val allExtensions = getExtensionNames()
            if (selectedExtensions.size == allExtensions.size) {
                // Deselect all
                selectedExtensions.clear()
            } else {
                // Select all
                selectedExtensions.clear()
                selectedExtensions.addAll(allExtensions)
            }
            setupExtensionChips()
            updateSelectedCount()
        }

        // Start Test Button
        binding.startButton.setOnClickListener {
            startTests()
        }

        updateSelectedCount()
    }

    private fun setupExtensionChips() {
        binding.extensionSelectionChipGroup.removeAllViews()
        val extensionsWithIcons = getExtensionsWithIcons()

        extensionsWithIcons.forEach { (name, icon) ->
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = selectedExtensions.contains(name)

                // Set extension icon - create a copy to avoid modifying the original
                icon?.let {
                    chipIcon = it.constantState?.newDrawable()?.mutate()
                    isChipIconVisible = true
                }

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedExtensions.add(name)
                    } else {
                        selectedExtensions.remove(name)
                    }
                    updateSelectedCount()
                }
            }
            binding.extensionSelectionChipGroup.addView(chip)
        }
    }

    private fun getExtensionNames(): List<String> {
        return when (currentExtensionType) {
            "anime" -> animeExtensionManager.installedExtensionsFlow.value.map { it.name }
            "manga" -> mangaExtensionManager.installedExtensionsFlow.value.map { it.name }
            "novel" -> novelExtensionManager.installedExtensionsFlow.value.map { it.name }
            else -> emptyList()
        }
    }

    private fun getExtensionsWithIcons(): List<Pair<String, android.graphics.drawable.Drawable?>> {
        return when (currentExtensionType) {
            "anime" -> animeExtensionManager.installedExtensionsFlow.value.map {
                it.name to it.icon
            }
            "manga" -> mangaExtensionManager.installedExtensionsFlow.value.map {
                it.name to it.icon
            }
            "novel" -> novelExtensionManager.installedExtensionsFlow.value.map {
                it.name to it.icon
            }
            else -> emptyList()
        }
    }

    private fun updateSelectedCount() {
        val count = selectedExtensions.size
        binding.selectedCountText.text = if (count == 0) {
            getString(R.string.no_extensions_selected)
        } else {
            "$count extension(s) selected"
        }

        // Update Select All button text
        val allExtensions = getExtensionNames()
        binding.selectAllButton.text = if (selectedExtensions.size == allExtensions.size) {
            "Deselect All"
        } else {
            "Select All"
        }
    }

    private fun startTests() {
        if (selectedExtensions.isEmpty()) {
            toast(R.string.no_extensions_selected)
            return
        }

        // Clear previous tests
        extensionsToTest.forEach { it.cancelJob() }
        extensionsToTest.clear()
        adapter.clear()

        // Create test items
        when (currentExtensionType) {
            "anime" -> {
                selectedExtensions.forEach { name ->
                    val extension = AnimeSources.list.find { source -> source.name == name }?.get?.value
                    extension?.let {
                        extensionsToTest.add(
                            ExtensionTestItem("anime", currentTestType, it, searchQuery)
                        )
                    }
                }
            }
            "manga" -> {
                selectedExtensions.forEach { name ->
                    val extension = MangaSources.list.find { source -> source.name == name }?.get?.value
                    extension?.let {
                        extensionsToTest.add(
                            ExtensionTestItem("manga", currentTestType, it, searchQuery)
                        )
                    }
                }
            }
            "novel" -> {
                selectedExtensions.forEach { name ->
                    val extension = NovelSources.list.find { source -> source.name == name }?.get?.value
                    extension?.let {
                        extensionsToTest.add(
                            ExtensionTestItem("novel", currentTestType, it, searchQuery)
                        )
                    }
                }
            }
        }

        // Show results header
        binding.resultsHeaderText.isVisible = true

        // Start tests
        extensionsToTest.forEach {
            adapter.add(it)
            it.startTest()
        }

        // Scroll to results
        binding.extensionResultsRecyclerView.post {
            binding.extensionResultsRecyclerView.smoothScrollToPosition(0)
        }
    }
}