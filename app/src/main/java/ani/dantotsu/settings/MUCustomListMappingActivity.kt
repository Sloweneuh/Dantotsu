package ani.dantotsu.settings

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.connections.mangaupdates.MUUserList
import ani.dantotsu.connections.mangaupdates.MangaUpdates
import ani.dantotsu.databinding.ActivityMuCustomListMappingBinding
import kotlinx.serialization.encodeToString
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MUCustomListMappingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMuCustomListMappingBinding

    /** Options shown in each row's spinner. Index 0 = excluded (default). */
    private val targetOptions = listOf("Exclude", "Reading", "Planning", "Completed", "Dropped", "Paused", "Separate")

    /** Current spinner selection per custom list ID. */
    private val selectionMap = mutableMapOf<Int, Int>()
    private var customLists = listOf<MUUserList>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivityMuCustomListMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsMuCustomMappingLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.muCustomMappingBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.muCustomMappingSave.setOnClickListener { saveMapping() }

        lifecycleScope.launch {
            binding.muCustomMappingProgress.visibility = View.VISIBLE
            val allLists = withContext(Dispatchers.IO) { MangaUpdates.getUserListsMeta() }
            customLists = allLists.filter { it.custom }
            binding.muCustomMappingProgress.visibility = View.GONE

            if (customLists.isEmpty()) {
                binding.muCustomMappingEmpty.visibility = View.VISIBLE
            } else {
                populateRows()
                binding.muCustomMappingSave.visibility = View.VISIBLE
            }
        }
    }

    private fun populateRows() {
        val currentMapping: Map<String, String> = try {
            val json = PrefManager.getVal<String>(PrefName.MuCustomListMapping)
            if (json.isNotBlank()) Mapper.json.decodeFromString(json) else emptyMap()
        } catch (_: Exception) { emptyMap() }

        val container = binding.muCustomMappingContainer
        container.removeAllViews()

        val typeface = ResourcesCompat.getFont(this, R.font.poppins_bold)
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp160 = (160 * resources.displayMetrics.density).toInt()

        for (list in customLists) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp8, 0, dp8)
            }

            val displayTitle = buildString {
                if (!list.icon.isNullOrBlank()) append("${list.icon} ")
                append(list.title ?: "List ${list.listId}")
            }

            val titleView = TextView(this).apply {
                text = displayTitle
                this.typeface = typeface
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val spinner = Spinner(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp160, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = dp8 }
                val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, targetOptions)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                this.adapter = adapter
            }

            // Restore saved selection
            val storedTarget = currentMapping[list.listId.toString()]
            val spinnerIndex = when {
                storedTarget == null -> 0
                targetOptions.indexOf(storedTarget).let { it > 0 } -> targetOptions.indexOf(storedTarget)
                else -> targetOptions.indexOf("Separate") // stored value is a custom title = "Separate" was chosen
            }
            spinner.setSelection(spinnerIndex.coerceAtLeast(0))
            selectionMap[list.listId] = spinnerIndex.coerceAtLeast(0)

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectionMap[list.listId] = position
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            row.addView(titleView)
            row.addView(spinner)
            container.addView(row)
        }
    }

    private fun saveMapping() {
        val newMapping = mutableMapOf<String, String>()
        val newTitles = mutableMapOf<String, String>()
        for (list in customLists) {
            val idx = selectionMap[list.listId] ?: 0
            if (idx == 0) continue // Exclude
            val target = targetOptions[idx]
            // "Separate" → use the list's own title as the bucket key
            val bucket = if (target == "Separate") list.title ?: "Custom ${list.listId}" else target
            newMapping[list.listId.toString()] = bucket
            newTitles[list.listId.toString()] = list.title ?: "Custom ${list.listId}"
        }
        val json = if (newMapping.isEmpty()) "" else Mapper.json.encodeToString(newMapping)
        val titlesJson = if (newTitles.isEmpty()) "" else Mapper.json.encodeToString(newTitles)
        PrefManager.setVal(PrefName.MuCustomListMapping, json)
        PrefManager.setVal(PrefName.MuCustomListTitles, titlesJson)
        finish()
    }
}
