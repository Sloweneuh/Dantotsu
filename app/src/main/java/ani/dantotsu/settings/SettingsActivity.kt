package ani.dantotsu.settings

import ani.dantotsu.databinding.ItemSettingsBinding
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Build.BRAND
import android.os.Build.DEVICE
import android.os.Build.SUPPORTED_ABIS
import android.os.Build.VERSION.CODENAME
import android.os.Build.VERSION.RELEASE
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BuildConfig
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivitySettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.AppUpdater
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class SettingsActivity : AppCompatActivity() {
    lateinit var binding: ActivitySettingsBinding
    private var cursedCounter = 0

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val context = this
        // Opened from another settings page's search button: just the search, nothing to browse.
        val searchOnly = intent.getBooleanExtra(EXTRA_SEARCH_ONLY, false)
        binding.apply {
            settingsVersion.apply {
                text = getString(R.string.version_current, BuildConfig.VERSION_NAME)

                setOnLongClickListener {
                    copyToClipboard(getDeviceInfo(), false)
                    toast(getString(R.string.copied_device_info))
                    return@setOnLongClickListener true
                }
            }
            settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            onBackPressedDispatcher.addCallback(context) {
                if (!searchOnly && PrefManager.getCustomVal("reload", false)) {
                    startMainActivity(context)
                    PrefManager.setCustomVal("reload", false)
                } else {
                    finish()
                }
            }

            settingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }

            // Accounts is entirely about logging into/out of online services (AniList, MAL,
            // MangaUpdates, etc.), so it's meaningless (and non-functional) offline.
            val offline = !isOnline(context) || PrefManager.getVal<Boolean>(PrefName.OfflineMode)
            val sectionsAdapter = SettingsAdapter(
                arrayListOf(
                    *listOfNotNull(
                        if (!offline) Settings(
                            type = 1,
                            name = getString(R.string.accounts_and_sync),
                            desc = getString(R.string.accounts_and_sync_desc),
                            icon = R.drawable.ic_round_manage_accounts_24,
                            onClick = {
                                openPage(it, SettingsAccountActivity::class.java)
                            },
                            isActivity = true
                        ) else null
                    ).toTypedArray(),
                    Settings(
                        type = 1,
                        name = getString(R.string.appearance),
                        desc = getString(R.string.appearance_desc),
                        icon = R.drawable.ic_palette,
                        onClick = {
                            openPage(it, SettingsAppearanceActivity::class.java)
                        },
                        isActivity = true,
                        startsGroup = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.general),
                        desc = getString(R.string.general_desc),
                        icon = R.drawable.ic_round_settings_24,
                        onClick = {
                            openPage(it, SettingsGeneralActivity::class.java)
                        },
                        isActivity = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.anime_and_player),
                        desc = getString(R.string.anime_and_player_desc),
                        icon = R.drawable.ic_round_movie_filter_24,
                        onClick = {
                            openPage(it, PlayerSettingsActivity::class.java)
                        },
                        isActivity = true,
                        startsGroup = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.manga_and_reader),
                        desc = getString(R.string.manga_and_reader_desc),
                        icon = R.drawable.ic_round_import_contacts_24,
                        onClick = {
                            openPage(it, ReaderSettingsActivity::class.java)
                        },
                        isActivity = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.sources_and_downloads),
                        desc = getString(R.string.sources_and_downloads_desc),
                        icon = R.drawable.ic_extension,
                        onClick = {
                            openPage(it, SettingsSourcesActivity::class.java)
                        },
                        isActivity = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.notifications),
                        desc = getString(R.string.notifications_desc),
                        icon = R.drawable.ic_round_notifications_none_24,
                        onClick = {
                            openPage(it, SettingsNotificationActivity::class.java)
                        },
                        isActivity = true,
                        startsGroup = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.about),
                        desc = getString(R.string.about_desc),
                        icon = R.drawable.ic_round_info_24,
                        onClick = {
                            openPage(it, SettingsAboutActivity::class.java)
                        },
                        isActivity = true
                    ),
                    *listOfNotNull(
                        // Debug console for manually triggering/testing background notification
                        // tasks; the activity itself also finishes immediately outside debug
                        // builds, so this is just keeping it out of the list release users see.
                        if (BuildConfig.DEBUG) Settings(
                            type = 1,
                            name = "Firebase Test Console",
                            desc = "Debug: test notifications and background tasks",
                            icon = R.drawable.ic_round_bug_report_24,
                            onClick = {
                                startActivity(
                                    Intent(
                                        context,
                                        ani.dantotsu.notifications.firebase.FirebaseTestActivity::class.java
                                    )
                                )
                            },
                            isActivity = true,
                            startsGroup = true
                        ) else null,
                        // Debug playground comparing current components and motion with the
                        // proposed modernisation (Material 1.14 Expressive, transitions, springs).
                        if (DesignLabActivity.enabled) Settings(
                            type = 1,
                            name = "Design Lab",
                            desc = "Debug: compare current UI with proposed changes",
                            icon = R.drawable.ic_round_bug_report_24,
                            onClick = {
                                openPage(it, DesignLabActivity::class.java)
                            },
                            isActivity = true,
                            startsGroup = !BuildConfig.DEBUG
                        ) else null
                    ).toTypedArray()
                ),
                tiled = true,
            )

            val idleAdapter = if (searchOnly) SettingsAdapter(arrayListOf()) else sectionsAdapter
            settingsRecyclerView.adapter = idleAdapter
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }
            if (searchOnly) {
                settingsTitleRow.visibility = View.GONE
                loginGithub.visibility = View.GONE
                settingsVersion.visibility = View.GONE
                settingsSearch.requestFocus()
                WindowCompat.getInsetsController(window, settingsSearch)
                    .show(WindowInsetsCompat.Type.ime())
            }

            settingsSearch.addTextChangedListener { text ->
                val query = text?.toString().orEmpty()
                val searching = query.isNotBlank()
                if (!searching) {
                    settingsRecyclerView.adapter = idleAdapter
                    settingsSearchEmpty.visibility = View.GONE
                    settingsSearchEmptyFrame.visibility = View.GONE
                    loginGithub.isVisible = !searchOnly
                    settingsVersion.isVisible = !searchOnly
                    return@addTextChangedListener
                }
                loginGithub.visibility = View.GONE
                settingsVersion.visibility = View.GONE
                // Grouped by the page each result lives on, the pages in order of their best match.
                val results = SettingsSearch.query(context, query)
                    .groupBy { SettingsSearch.pageRes(it) }
                    .flatMap { (page, entries) ->
                        listOf(Settings(type = 3, name = getString(page), desc = "", icon = 0)) +
                            entries.map { entry ->
                                Settings(
                                    type = 1,
                                    name = getString(entry.titleRes),
                                    // The full path down from the page, for a setting nested
                                    // in one of its groups; just the page for one on its surface.
                                    desc = listOfNotNull(
                                        SettingsSearch.parentPageRes(page), page, entry.sectionRes
                                    ).distinct().joinToString(" › ") { getString(it) },
                                    icon = entry.icon,
                                    onClick = { SettingsRouter.open(context, entry) },
                                )
                            }
                    }
                settingsSearchEmpty.isVisible = results.isEmpty()
                settingsSearchEmptyFrame.isVisible = results.isEmpty()
                settingsRecyclerView.adapter = SettingsAdapter(ArrayList(results), tiled = true)
            }

            if (!BuildConfig.FLAVOR.contains("fdroid")) {
                settingsLogo.setOnLongClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppUpdater.check(this@SettingsActivity, true)
                    }
                    true
                }
            }

            loginGithub.setOnClickListener {
                openLinkInBrowser(getString(R.string.github))
            }


            (settingsLogo.drawable as Animatable).start()
            val array = resources.getStringArray(R.array.tips)

            settingsLogo.setSafeOnClickListener {
                cursedCounter++
                (settingsLogo.drawable as Animatable).start()
                if (cursedCounter % 16 == 0) {
                    val oldVal: Boolean = PrefManager.getVal(PrefName.OC)
                    if (!oldVal) {
                        toast(R.string.omega_cursed)
                    } else {
                        toast(R.string.omega_freed)
                    }
                    PrefManager.setVal(PrefName.OC, !oldVal)
                } else {
                    snackString(array[(Math.random() * array.size).toInt()], context)
                }

            }
        }
    }

    companion object {
        /** Opens this screen as a bare settings search; see [SettingsSearchButton]. */
        const val EXTRA_SEARCH_ONLY = "ani.dantotsu.settings.SEARCH_ONLY"

        fun getDeviceInfo(): String {
            return """
                dantotsu Version: ${BuildConfig.VERSION_NAME}
                Device: $BRAND $DEVICE
                Architecture: ${getArch()}
                OS Version: $CODENAME $RELEASE ($SDK_INT)
            """.trimIndent()
        }

        private fun getArch(): String {
            SUPPORTED_ABIS.forEach {
                when (it) {
                    "arm64-v8a" -> return "aarch64"
                    "armeabi-v7a" -> return "arm"
                    "x86_64" -> return "x86_64"
                    "x86" -> return "i686"
                }
            }
            return System.getProperty("os.arch") ?: System.getProperty("os.product.cpu.abi")
            ?: "Unknown Architecture"
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager(this).applyTheme()
    }

    /** Opens a settings page with [row]'s icon flying into its header; see [SettingsPageTransitions.open]. */
    private fun openPage(row: ItemSettingsBinding, page: Class<*>) =
        SettingsPageTransitions.open(this, row, page)
}
