package ani.dantotsu.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.databinding.ActivitySettingsAppearanceBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.reloadActivity
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.AppFont
import ani.dantotsu.themes.DeviceFonts
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import eltos.simpledialogfragment.SimpleDialog
import eltos.simpledialogfragment.color.SimpleColorDialog
import java.io.File

/**
 * Everything that answers "how does the app look?".
 *
 * This was two screens at two different depths — Theme at the top level, and UI Settings two taps
 * down inside Common — which meant a user turning off banner animations had no reason to look under
 * Theme, where it wasn't, and every reason not to look under Common, where it was.
 *
 * The theme picker keeps the top of the screen because it is visual and wants the room. Everything
 * that used to be UI Settings follows as collapsible groups, replacing that screen's single "App"
 * group, which had itself become a small junk drawer of tabs, bars and home-screen options.
 */
class SettingsAppearanceActivity : AppCompatActivity(), SimpleDialog.OnDialogResultListener {
    private lateinit var binding: ActivitySettingsAppearanceBinding
    private lateinit var sectionAdapter: SettingsSectionAdapter
    private var reload = PrefManager.getCustomVal("reload", true)

    /** Section keys, also the search anchors — see [SettingsSection.key]. */
    object Section {
        const val COLORS = "appearance_colors"
        const val TEXT = "appearance_text"
        const val HOME = "appearance_home"
        const val ANIMATIONS = "appearance_animations"
        const val BLUR = "appearance_blur"
        const val SYSTEM_BARS = "appearance_system_bars"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsAppearanceLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        // Theme changes restart into Settings rather than just finishing, so the whole app picks up
        // the new theme. Inherited from the Theme screen this replaces.
        onBackPressedDispatcher.addCallback(context) {
            if (reload) {
                val packageName = context.packageName
                val mainIntent = Intent.makeRestartActivityTask(
                    packageManager.getLaunchIntentForPackage(packageName)!!.component
                )
                val component = ComponentName(packageName, SettingsActivity::class.qualifiedName!!)
                try {
                    startActivity(Intent().setComponent(component))
                } catch (e: Exception) {
                    startActivity(mainIntent)
                }
                finishAndRemoveTask()
                reload = false
            } else {
                finish()
            }
        }
        binding.appearanceSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        bindThemeModePicker()

        sectionAdapter = SettingsSectionAdapter(
            listOf(
                SettingsSection(
                    key = Section.COLORS,
                    title = getString(R.string.colors),
                    icon = R.drawable.ic_round_color_24,
                    summary = { getString(R.string.colors_desc) },
                    rows = { themeRows() },
                ),
                SettingsSection(
                    key = Section.TEXT,
                    title = getString(R.string.text_group),
                    icon = R.drawable.ic_round_format_text_24,
                    summary = { getString(R.string.text_group_desc) },
                    rows = { textRows() },
                ),
                SettingsSection(
                    key = Section.HOME,
                    title = getString(R.string.home_screen),
                    icon = R.drawable.ic_round_grid_view_24,
                    summary = { getString(R.string.home_screen_desc) },
                    rows = { homeRows() },
                ),
                SettingsSection(
                    key = Section.ANIMATIONS,
                    title = getString(R.string.animations),
                    icon = R.drawable.ic_round_auto_awesome_24,
                    summary = { getString(R.string.animations_desc) },
                    rows = { animationRows() },
                ),
                SettingsSection(
                    key = Section.BLUR,
                    title = getString(R.string.blur),
                    icon = R.drawable.blur_on,
                    summary = { getString(R.string.blur_desc) },
                    rows = { blurRows() },
                ),
                SettingsSection(
                    key = Section.SYSTEM_BARS,
                    title = getString(R.string.system_bars),
                    icon = R.drawable.ic_round_fullscreen_24,
                    summary = { getString(R.string.system_bars_desc) },
                    rows = { systemBarRows() },
                ),
            ),
            // Most of this screen restarts the activity when written, so which cards were open has
            // to outlive the restart (see markRelaunch) or the user is thrown back to the top on
            // every change. A plain entry from the settings list still starts collapsed.
            stateKey = SettingsSectionAdapter.STATE_APPEARANCE,
            keepExpanded = SettingsRouter.hasAnchor(this),
        )

        binding.settingsRecyclerView.apply {
            adapter = sectionAdapter
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        }

        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView)
        SettingsRouter.handleSectionAnchor(this, sectionAdapter, binding.settingsRecyclerView)
    }

    // -----------------------------------------------------------------------------------------
    // Theme — the picker header plus the rows that belong with it
    // -----------------------------------------------------------------------------------------

    private fun bindThemeModePicker() = binding.apply {
        var previous: View = when (PrefManager.getVal<Int>(PrefName.DarkMode)) {
            0 -> settingsUiAuto
            1 -> settingsUiLight
            2 -> settingsUiDark
            else -> settingsUiAuto
        }
        previous.alpha = 1f
        fun uiTheme(mode: Int, current: View) {
            previous.alpha = 0.33f
            previous = current
            current.alpha = 1f
            PrefManager.setVal(PrefName.DarkMode, mode)
            reload()
        }

        settingsUiAuto.setOnClickListener { uiTheme(0, it) }
        settingsUiLight.setOnClickListener {
            PrefManager.setVal(PrefName.UseOLED, false)
            uiTheme(1, it)
        }
        settingsUiDark.setOnClickListener { uiTheme(2, it) }

        val themeString: String = PrefManager.getVal(PrefName.Theme)
        (themeSwitcher as AutoCompleteTextView).apply {
            setText(themeString.substring(0, 1) + themeString.substring(1).lowercase())
            setAdapter(
                ArrayAdapter(
                    this@SettingsAppearanceActivity,
                    R.layout.item_dropdown,
                    ThemeManager.Companion.Theme.entries.map {
                        it.theme.substring(0, 1) + it.theme.substring(1).lowercase()
                    })
            )
            setOnItemClickListener { _, _, i, _ ->
                PrefManager.setVal(PrefName.Theme, ThemeManager.Companion.Theme.entries[i].theme)
                clearFocus()
                reload()
            }
        }
    }

    /**
     * Which tab the app opens on.
     *
     * Came from Common, where it sat two sections away from the Show Anime/Manga Tab switches that
     * decide which of its three choices even exist. It is now the first row of the same group as
     * those switches — a picker whose options are governed by the two toggles directly beneath it.
     */
    private fun startUpTabRow(): Settings {
        val showAnimeTab = PrefManager.getVal<Boolean>(PrefName.ShowAnimeTab)
        val showMangaTab = PrefManager.getVal<Boolean>(PrefName.ShowMangaTab)

        // Correct a saved choice whose tab has since been turned off.
        val current = PrefManager.getVal<Int>(PrefName.DefaultStartUpTab)
        if ((current == 0 && !showAnimeTab) || (current == 2 && !showMangaTab)) {
            PrefManager.setVal(PrefName.DefaultStartUpTab, 1)
        }

        return Settings(
            type = 5,
            name = getString(R.string.startUpTab),
            desc = getString(R.string.startUpTab_desc),
            icon = R.drawable.ic_round_home_24,
            compact = true,
            anchorKey = "start_up_tab",
            // With both anime and manga off, Home is the only option left and there is nothing to
            // pick — hide the row rather than show a picker with one dead choice in it.
            isVisible = showAnimeTab || showMangaTab,
            choice = ChoiceConfig(
                options = listOf(
                    ChoiceOption(
                        R.drawable.ic_round_movie_filter_24, getString(R.string.anime),
                        visible = showAnimeTab
                    ),
                    ChoiceOption(
                        R.drawable.ic_round_home_24, getString(R.string.home),
                        flipHorizontally = true
                    ),
                    ChoiceOption(
                        R.drawable.ic_round_import_contacts_24, getString(R.string.manga),
                        visible = showMangaTab
                    ),
                ),
                selected = PrefManager.getVal(PrefName.DefaultStartUpTab),
                onSelect = { mode ->
                    PrefManager.setVal(PrefName.DefaultStartUpTab, mode)
                    initActivity(this)
                },
            ),
        )
    }

    /** The finer colour choices. The light/dark toggle and the palette dropdown stay in the header
     *  above, where they can be seen without opening anything; these are the options you go looking
     *  for once, so they sit in a card like every other group on this screen. */
    private fun themeRows(): List<Settings> = listOf(
        Settings(
            type = 2,
            name = getString(R.string.oled_theme_variant),
            desc = getString(R.string.oled_theme_variant_desc),
            icon = R.drawable.ic_round_brightness_4_24,
            compact = true,
            anchorKey = "oled_theme",
            isChecked = PrefManager.getVal(PrefName.UseOLED),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.UseOLED, isChecked)
                reload()
            }
        ),
        Settings(
            type = 2,
            name = getString(R.string.use_material_you),
            desc = getString(R.string.use_material_you_desc),
            icon = R.drawable.ic_round_auto_awesome_24,
            compact = true,
            anchorKey = "material_you",
            isChecked = PrefManager.getVal(PrefName.UseMaterialYou),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.UseMaterialYou, isChecked)
                if (isChecked) PrefManager.setVal(PrefName.UseCustomTheme, false)
                reload()
            },
            isVisible = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
        ),
        Settings(
            type = 2,
            name = getString(R.string.use_unique_theme_for_each_item),
            desc = getString(R.string.use_unique_theme_for_each_item_desc),
            icon = R.drawable.ic_palette,
            compact = true,
            anchorKey = "source_theme",
            isChecked = PrefManager.getVal(PrefName.UseSourceTheme),
            switch = { isChecked, _ -> PrefManager.setVal(PrefName.UseSourceTheme, isChecked) },
            isVisible = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
        ),
        Settings(
            type = 2,
            name = getString(R.string.use_custom_theme),
            desc = getString(R.string.use_custom_theme_desc),
            icon = R.drawable.ic_round_color_24,
            compact = true,
            anchorKey = "custom_theme",
            isChecked = PrefManager.getVal(PrefName.UseCustomTheme),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.UseCustomTheme, isChecked)
                if (isChecked) PrefManager.setVal(PrefName.UseMaterialYou, false)
                reload()
            },
            isVisible = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
        ),
        Settings(
            type = 1,
            name = getString(R.string.color_picker),
            desc = getString(R.string.color_picker_desc),
            icon = R.drawable.ic_round_color_picker_24,
            compact = true,
            anchorKey = "color_picker",
            onClick = {
                val originalColor: Int = PrefManager.getVal(PrefName.CustomThemeInt)

                class CustomColorDialog : SimpleColorDialog() {
                    override fun onPositiveButtonClick() {
                        reload()
                        super.onPositiveButtonClick()
                    }
                }

                CustomColorDialog().title(R.string.custom_theme)
                    .colorPreset(originalColor)
                    .colors(this, SimpleColorDialog.MATERIAL_COLOR_PALLET)
                    .allowCustom(true).showOutline(0x46000000).gridNumColumn(5)
                    .choiceMode(SimpleColorDialog.SINGLE_CHOICE).neg()
                    .show(this, "colorPicker")
            },
            isVisible = Build.VERSION.SDK_INT > Build.VERSION_CODES.R
        ),
    )

    /** A font is not a colour, so it gets its own group rather than sitting among the palette
     *  options. Room here for anything else about type if it is wanted later. */
    private fun textRows(): List<Settings> = listOf(
        Settings(
            type = 1,
            name = getString(R.string.app_font),
            desc = currentFontLabel(),
            icon = R.drawable.ic_round_format_text_24,
            compact = true,
            anchorKey = "app_font",
            onClick = { showFontDialog() },
        ),
    )

    // -----------------------------------------------------------------------------------------
    // Home screen
    // -----------------------------------------------------------------------------------------

    private fun homeRows(): List<Settings> = listOf(
        startUpTabRow(),
        Settings(
            type = 2,
            name = getString(R.string.hide_notification_dot),
            desc = getString(R.string.hide_notification_dot_desc),
            icon = R.drawable.ic_round_app_badging_24,
            compact = true,
            anchorKey = "hide_notification_dot",
            // Stored as "show", shown as "hide" — the label is the negation of the preference.
            isChecked = !PrefManager.getVal<Boolean>(PrefName.ShowNotificationRedDot),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.ShowNotificationRedDot, !isChecked)
            }
        ),
        Settings(
            type = 1,
            name = getString(R.string.home_layout_show),
            desc = getString(R.string.home_layout_show_desc),
            icon = R.drawable.ic_round_grid_view_24,
            compact = true,
            anchorKey = "home_layout",
            onClick = { showHomeLayoutDialog() },
        ),
        Settings(
            type = 1,
            name = getString(R.string.home_stats_select),
            desc = getString(R.string.home_stats_select_desc),
            icon = R.drawable.ic_stats_24,
            compact = true,
            anchorKey = "home_stats",
            onClick = { showHomeStatsDialog() },
        ),
        Settings(
            type = 2,
            name = getString(R.string.small_view),
            desc = getString(R.string.small_view_desc),
            icon = R.drawable.ic_round_grid_view_24,
            compact = true,
            anchorKey = "small_view",
            isChecked = PrefManager.getVal(PrefName.SmallView),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.SmallView, isChecked)
                restartKeepingSections()
            }
        ),
        Settings(
            type = 2,
            name = getString(R.string.show_anime_tab),
            desc = getString(R.string.show_anime_tab_desc),
            icon = R.drawable.ic_round_movie_filter_24,
            compact = true,
            anchorKey = "show_anime_tab",
            isChecked = PrefManager.getVal(PrefName.ShowAnimeTab),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.ShowAnimeTab, isChecked)
                // Hiding the tab the app starts on would open it to nothing.
                if (!isChecked && PrefManager.getVal<Int>(PrefName.DefaultStartUpTab) == 0) {
                    PrefManager.setVal(PrefName.DefaultStartUpTab, 1)
                }
                restartKeepingSections()
            }
        ),
        Settings(
            type = 2,
            name = getString(R.string.show_manga_tab),
            desc = getString(R.string.show_manga_tab_desc),
            icon = R.drawable.ic_round_import_contacts_24,
            compact = true,
            anchorKey = "show_manga_tab",
            isChecked = PrefManager.getVal(PrefName.ShowMangaTab),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.ShowMangaTab, isChecked)
                if (!isChecked && PrefManager.getVal<Int>(PrefName.DefaultStartUpTab) == 2) {
                    PrefManager.setVal(PrefName.DefaultStartUpTab, 1)
                }
                restartKeepingSections()
            }
        ),
    )

    // -----------------------------------------------------------------------------------------
    // Animations
    // -----------------------------------------------------------------------------------------

    /** Slider position → animation-speed multiplier. The track runs slow-to-fast left-to-right,
     *  which is the reverse of the multiplier, and 0 means off at both ends. */
    private val speedByPosition = mapOf(
        2f to 0.5f, 1.75f to 0.625f, 1.5f to 0.75f, 1.25f to 0.875f,
        1f to 1f, 0.75f to 1.25f, 0.5f to 1.5f, 0.25f to 1.75f, 0f to 0f
    )

    private fun animationRows(): List<Settings> {
        val positionBySpeed = speedByPosition.entries.associate { it.value to it.key }
        return listOf(
            Settings(
                type = 2,
                name = getString(R.string.banner_animations),
                desc = getString(R.string.banner_animations_desc),
                icon = R.drawable.ic_round_animation_24,
                compact = true,
                anchorKey = "banner_animations",
                isChecked = PrefManager.getVal(PrefName.BannerAnimations),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.BannerAnimations, isChecked)
                    restartKeepingSections()
                }
            ),
            Settings(
                type = 2,
                name = getString(R.string.layout_animations),
                desc = getString(R.string.layout_animations_desc),
                icon = R.drawable.ic_round_animation_24,
                compact = true,
                anchorKey = "layout_animations",
                isChecked = PrefManager.getVal(PrefName.LayoutAnimations),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.LayoutAnimations, isChecked)
                    restartKeepingSections()
                }
            ),
            Settings(
                type = 2,
                name = getString(R.string.trending_scroller),
                desc = getString(R.string.trending_scroller_desc),
                icon = R.drawable.ic_round_swipe_vertical_24,
                compact = true,
                anchorKey = "trending_scroller",
                isChecked = PrefManager.getVal(PrefName.TrendingScroller),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.TrendingScroller, isChecked)
                }
            ),
            Settings(
                type = 4,
                name = getString(R.string.animation_speed),
                desc = getString(R.string.animation_speed_desc),
                icon = R.drawable.ic_round_animation_24,
                compact = true,
                anchorKey = "animation_speed",
                slider = SliderConfig(
                    from = 0f, to = 2f, stepSize = 0.25f,
                    value = positionBySpeed[PrefManager.getVal(PrefName.AnimationSpeed)] ?: 1f,
                    format = { pos ->
                        val speed = speedByPosition[pos] ?: 1f
                        if (speed == 0f) getString(R.string.none) else "${speed}x"
                    },
                    onChange = { pos ->
                        PrefManager.setVal(PrefName.AnimationSpeed, speedByPosition[pos] ?: 1f)
                        restartKeepingSections()
                    },
                ),
            ),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Blur
    // -----------------------------------------------------------------------------------------

    private fun blurRows(): List<Settings> = listOf(
        Settings(
            type = 2,
            name = getString(R.string.blur_banners),
            desc = getString(R.string.blur_banners_desc),
            icon = R.drawable.blur_on,
            compact = true,
            anchorKey = "blur_banners",
            isChecked = PrefManager.getVal(PrefName.BlurBanners),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.BlurBanners, isChecked)
                restartKeepingSections()
            }
        ),
        Settings(
            type = 4,
            name = getString(R.string.radius),
            desc = getString(R.string.blur_radius_desc),
            icon = R.drawable.blur_on,
            compact = true,
            anchorKey = "blur_radius",
            slider = SliderConfig(
                from = 1f, to = 10f, stepSize = 1f,
                value = PrefManager.getVal(PrefName.BlurRadius),
                format = { it.toInt().toString() },
                onChange = {
                    PrefManager.setVal(PrefName.BlurRadius, it)
                    restartKeepingSections()
                },
            ),
        ),
        Settings(
            type = 4,
            name = getString(R.string.sampling),
            desc = getString(R.string.blur_sampling_desc),
            icon = R.drawable.blur_on,
            compact = true,
            anchorKey = "blur_sampling",
            slider = SliderConfig(
                from = 1f, to = 10f, stepSize = 1f,
                value = PrefManager.getVal(PrefName.BlurSampling),
                format = { it.toInt().toString() },
                onChange = {
                    PrefManager.setVal(PrefName.BlurSampling, it)
                    restartKeepingSections()
                },
            ),
        ),
    )

    // -----------------------------------------------------------------------------------------
    // System bars
    // -----------------------------------------------------------------------------------------

    private fun systemBarRows(): List<Settings> = listOf(
        Settings(
            type = 2,
            name = getString(R.string.immersive_mode),
            desc = getString(R.string.immersive_mode_info),
            icon = R.drawable.ic_round_fullscreen_24,
            compact = true,
            anchorKey = "immersive_mode",
            isChecked = PrefManager.getVal(PrefName.ImmersiveMode),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.ImmersiveMode, isChecked)
                restartKeepingSections()
            }
        ),
        Settings(
            type = 2,
            name = getString(R.string.ui_show_system_bars),
            desc = getString(R.string.ui_show_system_bars_desc),
            icon = R.drawable.ic_round_fullscreen_24,
            compact = true,
            anchorKey = "ui_show_system_bars",
            isChecked = PrefManager.getVal(PrefName.ShowSystemBarsUI),
            switch = { isChecked, _ ->
                PrefManager.setVal(PrefName.ShowSystemBarsUI, isChecked)
            }
        ),
    )

    // -----------------------------------------------------------------------------------------
    // Dialogs carried over from the UI settings screen
    // -----------------------------------------------------------------------------------------

    private fun showHomeLayoutDialog() {
        val views = resources.getStringArray(R.array.home_layouts)
        val savedLayout = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout)

        // Older preferences hold fewer entries than there are sections; pad rather than crash.
        val visibilityList = if (savedLayout.size < views.size) {
            savedLayout.toMutableList().apply { while (size < views.size) add(true) }
        } else savedLayout.toMutableList()

        val savedOrder = PrefManager.getVal<List<Int>>(PrefName.HomeLayoutOrder)
        val order = if (savedOrder.isNullOrEmpty() || savedOrder.size != views.size) {
            views.indices.toList()
        } else savedOrder

        val items = order.map { originalIndex ->
            HomeLayoutItem(
                originalIndex,
                views[originalIndex],
                visibilityList.getOrNull(originalIndex) == true
            )
        }.toMutableList()

        val dialogView = layoutInflater.inflate(R.layout.dialog_home_layout_reorder, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.homeLayoutRecycler)
        val adapter = HomeLayoutAdapter(items)
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(this)

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)

        customAlertDialog().apply {
            setTitle(getString(R.string.home_layout_show_and_order))
            setCustomView(dialogView)
            setPosButton(R.string.ok) {
                val finalItems = adapter.getItems()
                PrefManager.setVal(PrefName.HomeLayoutOrder, finalItems.map { it.id })
                PrefManager.setVal(
                    PrefName.HomeLayout,
                    MutableList(views.size) { i -> finalItems.find { it.id == i }?.visible ?: true }
                )
                restartKeepingSections()
            }
            setNegButton(R.string.cancel, null)
            show()
        }
    }

    private fun showHomeStatsDialog() {
        val statOptions = arrayOf(
            getString(R.string.none),
            getString(R.string.episodes_watched),
            getString(R.string.chapters_read),
            getString(R.string.anime_count),
            getString(R.string.days_watched),
            getString(R.string.manga_count),
            getString(R.string.volumes_read),
            getString(R.string.anime_mean_score),
            getString(R.string.manga_mean_score),
        )
        val dialogView = layoutInflater.inflate(R.layout.dialog_home_stats, null)
        val dropdown1 = dialogView.findViewById<AutoCompleteTextView>(R.id.homeStat1Dropdown)
        val dropdown2 = dialogView.findViewById<AutoCompleteTextView>(R.id.homeStat2Dropdown)
        val adapter = ArrayAdapter(this, R.layout.item_dropdown, statOptions)
        dropdown1.setAdapter(adapter)
        dropdown2.setAdapter(adapter)
        dropdown1.setText(statOptions[PrefManager.getVal<Int>(PrefName.HomeStat1)], false)
        dropdown2.setText(statOptions[PrefManager.getVal<Int>(PrefName.HomeStat2)], false)
        customAlertDialog().apply {
            setTitle(getString(R.string.home_stats_select))
            setCustomView(dialogView)
            setPosButton(R.string.ok) {
                val sel1 = statOptions.indexOf(dropdown1.text.toString())
                val sel2 = statOptions.indexOf(dropdown2.text.toString())
                if (sel1 >= 0) PrefManager.setVal(PrefName.HomeStat1, sel1)
                if (sel2 >= 0) PrefManager.setVal(PrefName.HomeStat2, sel2)
                Refresh.activity[1]?.postValue(true)
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    override fun onResult(dialogTag: String, which: Int, extras: Bundle): Boolean {
        if (which == SimpleDialog.OnDialogResultListener.BUTTON_POSITIVE) {
            if (dialogTag == "colorPicker") {
                val color = extras.getInt(SimpleColorDialog.COLOR)
                PrefManager.setVal(PrefName.CustomThemeInt, color)
                Logger.log("Custom Theme: $color")
            }
        }
        return true
    }

    /**
     * [restartApp], keeping the groups the user has open.
     *
     * Eleven rows on this screen restart the app when written — every animation and blur control,
     * the tab switches, the home layout dialog. The restart starts a fresh Intent, so without the
     * marker the next launch reads as a plain entry and collapses everything, throwing the user out
     * of the group they were adjusting.
     */
    private fun restartKeepingSections() {
        if (::sectionAdapter.isInitialized) sectionAdapter.markRelaunch()
        restartApp()
    }

    fun reload() {
        // Same reasoning as restartKeepingSections: reloadActivity() also starts a fresh Intent.
        if (::sectionAdapter.isInitialized) sectionAdapter.markRelaunch()
        PrefManager.setCustomVal("reload", true)
        Handler(Looper.getMainLooper()).postDelayed({
            reloadActivity()
            finishAndRemoveTask()
        }, 100)
    }

    // -----------------------------------------------------------------------------------------
    // App font
    // -----------------------------------------------------------------------------------------

    /** Picks a font file the device does not expose through [DeviceFonts]. */
    private val pickFontFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFont(uri) }

    /** What the App font row reads as: the chosen font's own name. */
    private fun currentFontLabel(): String {
        val key = AppFont.current()
        return when {
            key == AppFont.SYSTEM -> getString(R.string.font_follow_system)
            key == AppFont.DEFAULT -> getString(R.string.font_default)
            key.startsWith("res:") -> key.removePrefix("res:")
                .replace('_', ' ').replaceFirstChar { it.uppercase() }

            key.startsWith("file:") ->
                File(key.removePrefix("file:")).nameWithoutExtension.replace('_', ' ')

            else -> getString(R.string.font_default)
        }
    }

    private fun showFontDialog() {
        val device = DeviceFonts.list()
        // Order matters: the two that always work first, then what is bundled, then what the device
        // happens to have, then the escape hatch for everything those miss.
        val keys = buildList {
            add(AppFont.SYSTEM)
            add(AppFont.DEFAULT)
            AppFont.bundled.forEach { add("res:${it.first}") }
            device.forEach { add("file:${it.path}") }
            add(PICK_FILE)
        }
        val labels = buildList {
            add(getString(R.string.font_follow_system))
            add(getString(R.string.font_default))
            AppFont.bundled.forEach {
                add(it.first.replace('_', ' ').replaceFirstChar { c -> c.uppercase() })
            }
            device.forEach { add(it.label) }
            add(getString(R.string.font_choose_file))
        }.toTypedArray()

        customAlertDialog().apply {
            setTitle(R.string.app_font)
            singleChoiceItems(labels, keys.indexOf(AppFont.current()).coerceAtLeast(0)) { i ->
                if (keys[i] == PICK_FILE) {
                    pickFontFile.launch(arrayOf("font/*", "application/x-font-ttf", "application/octet-stream"))
                } else {
                    applyFont(keys[i])
                }
            }
            show()
        }
    }

    /**
     * Copies a picked font into app storage and selects it.
     *
     * Copied rather than referenced: a content URI is a grant that does not survive a reboot, and a
     * font the app cannot re-open on next launch would silently fall back with nothing to explain it.
     */
    private fun importFont(uri: Uri) {
        try {
            val dir = File(filesDir, "fonts").apply { mkdirs() }
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: "imported.ttf"
            val dest = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return toast(getString(R.string.font_load_failed))
            applyFont("file:${dest.absolutePath}")
        } catch (e: Exception) {
            Logger.log("AppFont: import failed - ${e.message}")
            toast(getString(R.string.font_load_failed))
        }
    }

    /** Checks the font can actually draw text before committing to it. */
    private fun applyFont(key: String) {
        val previous = AppFont.current()
        AppFont.set(key)
        AppFont.invalidate()
        val face = runCatching { AppFont.probe(this) }.getOrNull()
        if (key != AppFont.SYSTEM && key != AppFont.DEFAULT && face == null) {
            AppFont.set(previous)
            AppFont.invalidate()
            return toast(getString(R.string.font_load_failed))
        }
        if (face != null && !AppFont.hasLatinCoverage(face)) {
            AppFont.set(previous)
            AppFont.invalidate()
            return toast(getString(R.string.font_no_latin))
        }
        reload()
    }

    private companion object {
        const val PICK_FILE = "__pick_file__"
    }
}
