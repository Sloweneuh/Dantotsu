package ani.dantotsu.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityUserInterfaceSettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.customAlertDialog

class UserInterfaceSettingsActivity : AppCompatActivity() {
    lateinit var binding: ActivityUserInterfaceSettingsBinding
    private val ui = "ui_settings"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityUserInterfaceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.uiSettingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.uiSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.uiSettingsHomeLayout.setOnClickListener {
            val views = resources.getStringArray(R.array.home_layouts)
            val savedLayout = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout)

            // Ensure the list has the correct size (handle old preferences with fewer items)
            val visibilityList = if (savedLayout.size < views.size) {
                // Pad with 'true' for new items
                savedLayout.toMutableList().apply {
                    while (size < views.size) add(true)
                }
            } else savedLayout.toMutableList()

            // Build initial order from pref or default sequential
            val savedOrder = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder)
            val order = if (savedOrder.isNullOrEmpty() || savedOrder.size != views.size) {
                (0 until views.size).toList()
            } else savedOrder

            val items = order.mapIndexed { i, originalIndex ->
                HomeLayoutItem(originalIndex, views[originalIndex], visibilityList.getOrNull(originalIndex) == true)
            }.toMutableList()

            val dialogView = layoutInflater.inflate(R.layout.dialog_home_layout_reorder, null)
            val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.homeLayoutRecycler)
            val adapter = HomeLayoutAdapter(items)
            recycler.adapter = adapter
            recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

            val callback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ): Boolean {
                    val from = vh.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    adapter.onItemMove(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
            }

            val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(callback)
            touchHelper.attachToRecyclerView(recycler)

            customAlertDialog().apply {
                setTitle(getString(R.string.home_layout_show_and_order))
                setCustomView(dialogView)
                setPosButton(R.string.ok) {
                    // Persist visibility in original index order
                    val finalItems = adapter.getItems()
                    val newOrder = finalItems.map { it.id }
                    val newVisibility = MutableList(views.size) { i ->
                        // find item with original index i
                        finalItems.find { it.id == i }?.visible ?: true
                    }
                    PrefManager.setVal(PrefName.HomeLayoutOrder, newOrder)
                    PrefManager.setVal(PrefName.HomeLayout, newVisibility)
                    restartApp()
                }
                setNegButton(R.string.cancel, null)
                show()
            }
        }

        binding.uiSettingsSmallView.isChecked = PrefManager.getVal(PrefName.SmallView)
        binding.uiSettingsSmallView.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.SmallView, isChecked)
            restartApp()
        }

        binding.uiSettingsImmersive.isChecked = PrefManager.getVal(PrefName.ImmersiveMode)
        binding.uiSettingsImmersive.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ImmersiveMode, isChecked)
            restartApp()
        }
        binding.uiSettingsHideRedDot.isChecked =
            !PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot)
        binding.uiSettingsHideRedDot.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.ShowNotificationRedDot, !isChecked)
        }
        binding.uiSettingsBannerAnimation.isChecked = PrefManager.getVal(PrefName.BannerAnimations)
        binding.uiSettingsBannerAnimation.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.BannerAnimations, isChecked)
            restartApp()
        }

        binding.uiSettingsLayoutAnimation.isChecked = PrefManager.getVal(PrefName.LayoutAnimations)
        binding.uiSettingsLayoutAnimation.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.LayoutAnimations, isChecked)
            restartApp()
        }

        binding.uiSettingsTrendingScroller.isChecked = PrefManager.getVal(PrefName.TrendingScroller)
        binding.uiSettingsTrendingScroller.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.TrendingScroller, isChecked)
        }

        val map = mapOf(
            2f to 0.5f,
            1.75f to 0.625f,
            1.5f to 0.75f,
            1.25f to 0.875f,
            1f to 1f,
            0.75f to 1.25f,
            0.5f to 1.5f,
            0.25f to 1.75f,
            0f to 0f
        )
        val mapReverse = map.map { it.value to it.key }.toMap()
        binding.uiSettingsAnimationSpeed.value =
            mapReverse[PrefManager.getVal(PrefName.AnimationSpeed)] ?: 1f
        binding.uiSettingsAnimationSpeed.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.AnimationSpeed, map[value] ?: 1f)
            restartApp()
        }
        binding.uiSettingsBlurBanners.isChecked = PrefManager.getVal(PrefName.BlurBanners)
        binding.uiSettingsBlurBanners.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.setVal(PrefName.BlurBanners, isChecked)
            restartApp()
        }
        binding.uiSettingsBlurRadius.value = (PrefManager.getVal(PrefName.BlurRadius) as Float)
        binding.uiSettingsBlurRadius.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.BlurRadius, value)
            restartApp()
        }
        binding.uiSettingsBlurSampling.value = (PrefManager.getVal(PrefName.BlurSampling) as Float)
        binding.uiSettingsBlurSampling.addOnChangeListener { _, value, _ ->
            PrefManager.setVal(PrefName.BlurSampling, value)
            restartApp()
        }
    }
}
