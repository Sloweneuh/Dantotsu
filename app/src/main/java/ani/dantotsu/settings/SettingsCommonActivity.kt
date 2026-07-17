package ani.dantotsu.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.handoff.GlobalHandoffReceiver
import ani.dantotsu.connections.handoff.HandoffManager
import ani.dantotsu.databinding.ActivitySettingsCommonBinding
import ani.dantotsu.databinding.DialogScreenshotDefaultsBinding
import ani.dantotsu.databinding.DialogSetPasswordBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.calc.BiometricPromptUtils
import ani.dantotsu.restartApp
import ani.dantotsu.startMainActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.customAlertDialog
import java.util.UUID

class SettingsCommonActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsCommonBinding

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsCommonBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.settingsRecyclerView)

        binding.apply {
            settingsCommonLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            commonSettingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
            val exDns =
                listOf(
                    "None",
                    "Cloudflare",
                    "Google",
                    "AdGuard",
                    "Quad9",
                    "AliDNS",
                    "DNSPod",
                    "360",
                    "Quad101",
                    "Mullvad",
                    "Controld",
                    "Njalla",
                    "Shecan",
                    "Libre",
                )
            settingsExtensionDns.setText(exDns[PrefManager.getVal(PrefName.DohProvider)])
            settingsExtensionDns.setAdapter(
                ArrayAdapter(
                    context,
                    R.layout.item_dropdown,
                    exDns,
                ),
            )
            settingsExtensionDns.setOnItemClickListener { _, _, i, _ ->
                PrefManager.setVal(PrefName.DohProvider, i)
                settingsExtensionDns.clearFocus()
                restartApp()
            }

            settingsRecyclerView.adapter =
                SettingsAdapter(
                    arrayListOf(
                        Settings(
                            type = 1,
                            name = getString(R.string.language_setting),
                            desc = ani.dantotsu.util.LanguageHelper.getLanguageDisplayName(
                                ani.dantotsu.util.LanguageHelper.getCurrentLanguageCode()
                            ),
                            icon = R.drawable.ic_round_language_24,
                            onClick = {
                                val languages = ani.dantotsu.util.LanguageHelper.getSupportedLanguages()
                                val languageNames = languages.map { it.getDisplayName() }.toTypedArray()
                                val currentLanguage = ani.dantotsu.util.LanguageHelper.getCurrentLanguageCode()
                                val currentIndex = languages.indexOfFirst { it.code == currentLanguage }

                                customAlertDialog().apply {
                                    setTitle(getString(R.string.language_setting))
                                    singleChoiceItems(languageNames, currentIndex) { which ->
                                        val selectedLanguage = languages[which]
                                        ani.dantotsu.util.LanguageHelper.setLanguage(context, selectedLanguage.code)

                                        // Show restart confirmation dialog
                                        customAlertDialog().apply {
                                            setTitle(getString(R.string.restart_required))
                                            setMessage(getString(R.string.restart_app_to_apply_language))
                                            setPosButton(getString(R.string.restart)) {
                                                startMainActivity(this@SettingsCommonActivity)
                                            }
                                            setNegButton(getString(R.string.later))
                                            show()
                                        }
                                    }
                                    show()
                                }
                            },
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.ui_settings),
                            desc = getString(R.string.ui_settings_desc),
                            icon = R.drawable.ic_round_auto_awesome_24,
                            onClick = {
                                startActivity(
                                    Intent(
                                        context,
                                        UserInterfaceSettingsActivity::class.java,
                                    ),
                                )
                            },
                            isActivity = true,
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.app_lock),
                            desc = getString(R.string.app_lock_desc),
                            icon = R.drawable.ic_round_lock_open_24,
                            onClick = {
                                customAlertDialog().apply {
                                    val view = DialogSetPasswordBinding.inflate(layoutInflater)
                                    setTitle(R.string.app_lock)
                                    setCustomView(view.root)
                                    setPosButton(R.string.ok) {
                                        if (view.forgotPasswordCheckbox.isChecked) {
                                            PrefManager.setVal(PrefName.OverridePassword, true)
                                        }
                                        val password = view.passwordInput.text.toString()
                                        val confirmPassword = view.confirmPasswordInput.text.toString()
                                        if (password == confirmPassword && password.isNotEmpty()) {
                                            PrefManager.setVal(PrefName.AppPassword, password)
                                            if (view.biometricCheckbox.isChecked) {
                                                val canBiometricPrompt =
                                                    BiometricManager
                                                        .from(applicationContext)
                                                        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                                                        BiometricManager.BIOMETRIC_SUCCESS

                                                if (canBiometricPrompt) {
                                                    val biometricPrompt =
                                                        BiometricPromptUtils.createBiometricPrompt(this@SettingsCommonActivity) { _ ->
                                                            val token = UUID.randomUUID().toString()
                                                            PrefManager.setVal(
                                                                PrefName.BiometricToken,
                                                                token,
                                                            )
                                                            toast(R.string.success)
                                                        }
                                                    val promptInfo =
                                                        BiometricPromptUtils.createPromptInfo(this@SettingsCommonActivity)
                                                    biometricPrompt.authenticate(promptInfo)
                                                }
                                            } else {
                                                PrefManager.setVal(PrefName.BiometricToken, "")
                                                toast(R.string.success)
                                            }
                                        } else {
                                            toast(R.string.password_mismatch)
                                        }
                                    }
                                    setNegButton(R.string.cancel)
                                    setNeutralButton(R.string.remove) {
                                        PrefManager.setVal(PrefName.AppPassword, "")
                                        PrefManager.setVal(PrefName.BiometricToken, "")
                                        PrefManager.setVal(PrefName.OverridePassword, false)
                                        toast(R.string.success)
                                    }
                                    setOnShowListener {
                                        view.passwordInput.requestFocus()
                                        val canAuthenticate =
                                            BiometricManager.from(applicationContext).canAuthenticate(
                                                BiometricManager.Authenticators.BIOMETRIC_WEAK,
                                            ) == BiometricManager.BIOMETRIC_SUCCESS
                                        view.biometricCheckbox.isVisible = canAuthenticate
                                        view.biometricCheckbox.isChecked =
                                            PrefManager.getVal(PrefName.BiometricToken, "").isNotEmpty()
                                        view.forgotPasswordCheckbox.isChecked =
                                            PrefManager.getVal(PrefName.OverridePassword)
                                    }
                                    show()
                                }
                            },
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.backup_sync),
                            desc = getString(R.string.backup_sync_desc),
                            icon = R.drawable.backup_restore,
                            isActivity = true,
                            onClick = {
                                startActivity(
                                    Intent(context, SettingsBackupSyncActivity::class.java)
                                )
                            },
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.always_continue_content),
                            desc = getString(R.string.always_continue_content_desc),
                            icon = R.drawable.ic_round_delete_24,
                            isChecked = PrefManager.getVal(PrefName.ContinueMedia),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.ContinueMedia, isChecked)
                            },
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.handoff_discovery_setting),
                            desc = getString(R.string.handoff_discovery_setting_desc),
                            icon = R.drawable.ic_round_cast_24,
                            isChecked = PrefManager.getVal(PrefName.HandoffDiscoveryEnabled),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.HandoffDiscoveryEnabled, isChecked)
                                if (isChecked) GlobalHandoffReceiver.restart(applicationContext)
                                else GlobalHandoffReceiver.stop()
                            },
                            // No Nearby/LAN on WSA/emulator, so the toggle would be a no-op there.
                            isVisible = !HandoffManager.isVirtualDevice(context),
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.screenshot_defaults),
                            desc = getString(R.string.screenshot_defaults_desc),
                            icon = R.drawable.ic_round_screenshot_frame_24,
                            onClick = { showScreenshotDefaultsDialog() },
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.hide_private),
                            desc = getString(R.string.hide_private_desc),
                            icon = R.drawable.ic_round_remove_red_eye_24,
                            isChecked = PrefManager.getVal(PrefName.HidePrivate),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.HidePrivate, isChecked)
                                restartApp()
                            },
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.search_source_list),
                            desc = getString(R.string.search_source_list_desc),
                            icon = R.drawable.ic_round_search_sources_24,
                            isChecked = PrefManager.getVal(PrefName.SearchSources),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.SearchSources, isChecked)
                            },
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.recentlyListOnly),
                            desc = getString(R.string.recentlyListOnly_desc),
                            icon = R.drawable.ic_round_new_releases_24,
                            isChecked = PrefManager.getVal(PrefName.RecentlyListOnly),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.RecentlyListOnly, isChecked)
                            },
                        ),
                        Settings(
                            type = 2,
                            name = getString(R.string.adult_only_content),
                            desc = getString(R.string.adult_only_content_desc),
                            icon = R.drawable.ic_round_nsfw_24,
                            isChecked = PrefManager.getVal(PrefName.AdultOnly),
                            switch = { isChecked, _ ->
                                PrefManager.setVal(PrefName.AdultOnly, isChecked)
                                restartApp()
                            },
                            isVisible = Anilist.adult,
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.hidden_from_lists_manage),
                            desc = getString(R.string.hidden_from_lists_manage_desc),
                            icon = R.drawable.ic_round_remove_red_eye_24,
                            onClick = { showHiddenFromListsDialog() },
                        ),
                    ),
                )
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }
            val showAnimeTab = PrefManager.getVal<Boolean>(PrefName.ShowAnimeTab)
            val showMangaTab = PrefManager.getVal<Boolean>(PrefName.ShowMangaTab)
            // Auto-correct saved default tab if the corresponding tab is now disabled
            val currentDefault = PrefManager.getVal<Int>(PrefName.DefaultStartUpTab)
            if ((currentDefault == 0 && !showAnimeTab) || (currentDefault == 2 && !showMangaTab)) {
                PrefManager.setVal(PrefName.DefaultStartUpTab, 1)
            }
            // Show/hide tab picker buttons based on whether the tab is enabled
            (uiSettingsAnime.parent as? View)?.visibility =
                if (showAnimeTab) View.VISIBLE else View.GONE
            (uiSettingsManga.parent as? View)?.visibility =
                if (showMangaTab) View.VISIBLE else View.GONE

            var previousStart: View =
                when (PrefManager.getVal<Int>(PrefName.DefaultStartUpTab)) {
                    0 -> uiSettingsAnime
                    1 -> uiSettingsHome
                    2 -> uiSettingsManga
                    else -> uiSettingsHome
                }
            previousStart.alpha = 1f

            fun uiDefault(
                mode: Int,
                current: View,
            ) {
                previousStart.alpha = 0.33f
                previousStart = current
                current.alpha = 1f
                PrefManager.setVal(PrefName.DefaultStartUpTab, mode)
                initActivity(context)
            }

            uiSettingsAnime.setOnClickListener {
                uiDefault(0, it)
            }

            uiSettingsHome.setOnClickListener {
                uiDefault(1, it)
            }

            uiSettingsManga.setOnClickListener {
                uiDefault(2, it)
            }
        }
    }

    private fun showScreenshotDefaultsDialog() {
        val view = DialogScreenshotDefaultsBinding.inflate(layoutInflater)
        view.dsMediaInfo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowMediaInfo)
        view.dsDate.isChecked = PrefManager.getVal(PrefName.ScreenshotShowDate)
        view.dsSource.isChecked = PrefManager.getVal(PrefName.ScreenshotShowSource)
        view.dsUserInfo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowUserInfo)
        view.dsAppLogo.isChecked = PrefManager.getVal(PrefName.ScreenshotShowAppLogo)
        view.dsFrame.isChecked = PrefManager.getVal(PrefName.ScreenshotShowFrame)
        view.dsRounded.isChecked = PrefManager.getVal(PrefName.ScreenshotShowRoundedCorners)
        view.dsMediaInfo.setOnCheckedChangeListener { _, c -> PrefManager.setVal(PrefName.ScreenshotShowMediaInfo, c) }
        view.dsDate.setOnCheckedChangeListener { _, c -> PrefManager.setVal(PrefName.ScreenshotShowDate, c) }
        view.dsSource.setOnCheckedChangeListener { _, c -> PrefManager.setVal(PrefName.ScreenshotShowSource, c) }
        view.dsUserInfo.setOnCheckedChangeListener { _, c -> PrefManager.setVal(PrefName.ScreenshotShowUserInfo, c) }
        view.dsAppLogo.setOnCheckedChangeListener { _, c -> PrefManager.setVal(PrefName.ScreenshotShowAppLogo, c) }
        view.dsFrame.setOnCheckedChangeListener { _, c -> PrefManager.setVal(PrefName.ScreenshotShowFrame, c) }
        view.dsRounded.setOnCheckedChangeListener { _, c -> PrefManager.setVal(PrefName.ScreenshotShowRoundedCorners, c) }
        customAlertDialog().apply {
            setTitle(getString(R.string.screenshot_defaults))
            setCustomView(view.root)
            setPosButton(R.string.ok) {}
            show()
        }
    }

    private fun showHiddenFromListsDialog() {
        MediaExcludeBottomDialog.newInstance(
            PrefName.HiddenFromLists,
            getString(R.string.hidden_from_lists_manage)
        ).show(supportFragmentManager, "hiddenFromLists")
    }
}
