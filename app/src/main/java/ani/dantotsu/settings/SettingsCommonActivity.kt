package ani.dantotsu.settings

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivitySettingsCommonBinding
import ani.dantotsu.databinding.DialogSetPasswordBinding
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.others.calc.BiometricPromptUtils
import ani.dantotsu.restartApp
import ani.dantotsu.savePrefsToDownloads
import ani.dantotsu.startMainActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.Location
import ani.dantotsu.settings.saving.internal.PreferenceKeystore
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.LauncherWrapper
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class SettingsCommonActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsCommonBinding
    private lateinit var launcher: LauncherWrapper

    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsCommonBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val openDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try {
                        val jsonString =
                            contentResolver.openInputStream(uri)?.readBytes()
                                ?: throw Exception("Error reading file")
                        val name = DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
                        // .sani is encrypted, .ani is not
                        if (name.endsWith(".sani")) {
                            passwordAlertDialog(false) { password ->
                                if (password != null) {
                                    val salt = jsonString.copyOfRange(0, 16)
                                    val encrypted = jsonString.copyOfRange(16, jsonString.size)
                                    val decryptedJson =
                                        try {
                                            PreferenceKeystore.decryptWithPassword(
                                                password,
                                                encrypted,
                                                salt,
                                            )
                                        } catch (e: Exception) {
                                            toast(getString(R.string.incorrect_password))
                                            return@passwordAlertDialog
                                        }
                                    if (PreferencePackager.unpack(decryptedJson)) {
                                        checkPermissionsAfterRestore()
                                    }
                                } else {
                                    toast(getString(R.string.password_cannot_be_empty))
                                }
                            }
                        } else if (name.endsWith(".ani")) {
                            val decryptedJson = jsonString.toString(Charsets.UTF_8)
                            if (PreferencePackager.unpack(decryptedJson)) {
                                checkPermissionsAfterRestore()
                            }
                        } else {
                            toast(getString(R.string.unknown_file_type))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toast(getString(R.string.error_importing_settings))
                    }
                }
            }
        val contract = ActivityResultContracts.OpenDocumentTree()
        launcher = LauncherWrapper(this, contract)

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
                            name = getString(R.string.download_manager_select),
                            desc = getString(R.string.download_manager_select_desc),
                            icon = R.drawable.ic_download_24,
                            onClick = {
                                val managers = arrayOf("Default", "1DM", "ADM")
                                customAlertDialog().apply {
                                    setTitle(getString(R.string.download_manager))
                                    singleChoiceItems(
                                        managers,
                                        PrefManager.getVal(PrefName.DownloadManager),
                                    ) { count ->
                                        PrefManager.setVal(PrefName.DownloadManager, count)
                                    }
                                    show()
                                }
                            },
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
                            name = getString(R.string.backup_restore),
                            desc = getString(R.string.backup_restore_desc),
                            icon = R.drawable.backup_restore,
                            onClick = {
                                StoragePermissions.downloadsPermission(context)
                                val filteredLocations = Location.entries.filter { it.exportable }
                                val selectedArray = BooleanArray(filteredLocations.size) { false }
                                context.customAlertDialog().apply {
                                    setTitle(R.string.backup_restore)
                                    multiChoiceItems(
                                        filteredLocations.map { it.name }.toTypedArray(),
                                        selectedArray,
                                    ) { updatedSelection ->
                                        for (i in updatedSelection.indices) {
                                            selectedArray[i] = updatedSelection[i]
                                        }
                                    }
                                    setPosButton(R.string.button_restore) {
                                        openDocumentLauncher.launch(arrayOf("*/*"))
                                    }
                                    setNegButton(R.string.button_backup) {
                                        if (!selectedArray.contains(true)) {
                                            toast(R.string.no_location_selected)
                                            return@setNegButton
                                        }
                                        val selected =
                                            filteredLocations.filterIndexed { index, _ -> selectedArray[index] }
                                        if (selected.contains(Location.Protected)) {
                                            passwordAlertDialog(true) { password ->
                                                if (password != null) {
                                                    savePrefsToDownloads(
                                                        "DantotsuSettings",
                                                        PrefManager.exportAllPrefs(selected),
                                                        context,
                                                        password,
                                                    )
                                                } else {
                                                    toast(R.string.password_cannot_be_empty)
                                                }
                                            }
                                        } else {
                                            savePrefsToDownloads(
                                                "DantotsuSettings",
                                                PrefManager.exportAllPrefs(selected),
                                                context,
                                                null,
                                            )
                                        }
                                    }
                                    setNeutralButton(R.string.cancel) {}
                                    show()
                                }
                            },
                        ),
                        Settings(
                            type = 1,
                            name = getString(R.string.change_download_location),
                            desc = getString(R.string.change_download_location_desc),
                            icon = R.drawable.ic_round_source_24,
                            onClick = {
                                context.customAlertDialog().apply {
                                    setTitle(R.string.change_download_location)
                                    setMessage(R.string.download_location_msg)
                                    setPosButton(R.string.ok) {
                                        val oldUri = PrefManager.getVal<String>(PrefName.DownloadsDir)
                                        launcher.registerForCallback { success ->
                                            if (success) {
                                                toast(getString(R.string.please_wait))
                                                val newUri =
                                                    PrefManager.getVal<String>(PrefName.DownloadsDir)
                                                GlobalScope.launch(Dispatchers.IO) {
                                                    Injekt.get<DownloadsManager>().moveDownloadsDir(
                                                        context,
                                                        Uri.parse(oldUri),
                                                        Uri.parse(newUri),
                                                    ) { finished, message ->
                                                        if (finished) {
                                                            toast(getString(R.string.success))
                                                        } else {
                                                            toast(message)
                                                        }
                                                    }
                                                }
                                            } else {
                                                toast(getString(R.string.error))
                                            }
                                        }
                                        launcher.launch()
                                    }
                                    setNegButton(R.string.cancel)
                                    show()
                                }
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
                    ),
                )
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }
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

    private fun passwordAlertDialog(
        isExporting: Boolean,
        callback: (CharArray?) -> Unit,
    ) {
        val password = CharArray(16).apply { fill('0') }

        // Inflate the dialog layout
        val dialogView = DialogUserAgentBinding.inflate(layoutInflater)
        val box = dialogView.userAgentTextBox
        box.hint = getString(R.string.password)
        box.setSingleLine()

        val dialog =
            AlertDialog
                .Builder(this, R.style.MyPopup)
                .setTitle(getString(R.string.enter_password))
                .setView(dialogView.root)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel) { dialog, _ ->
                    password.fill('0')
                    dialog.dismiss()
                    callback(null)
                }.create()

        fun handleOkAction() {
            val editText = dialogView.userAgentTextBox
            if (editText.text?.isNotBlank() == true) {
                editText.text
                    ?.toString()
                    ?.trim()
                    ?.toCharArray(password)
                dialog.dismiss()
                callback(password)
            } else {
                toast(getString(R.string.password_cannot_be_empty))
            }
        }
        box.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleOkAction()
                true
            } else {
                false
            }
        }
        dialogView.subtitle.visibility = View.VISIBLE
        if (!isExporting) {
            dialogView.subtitle.text =
                getString(R.string.enter_password_to_decrypt_file)
        }

        dialog.window?.apply {
            setDimAmount(0.8f)
            attributes.windowAnimations = android.R.style.Animation_Dialog
        }
        dialog.show()

        // Override the positive button here
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            handleOkAction()
        }
    }

    private fun checkPermissionsAfterRestore() {
        val missingPermissions = mutableListOf<String>()
        var hasDisabledSettings = false

        // Check POST_NOTIFICATIONS permission for notification-related settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasNotificationPermission) {
                // Check if any notification settings are enabled
                val hasAnilistNotifications = PrefManager.getVal<Int>(PrefName.AnilistNotificationInterval) > 0
                val hasSubscriptionNotifications = PrefManager.getVal<Long>(PrefName.SubscriptionNotificationIntervalMinutes) > 0L
                val hasUnreadChapterNotifications = PrefManager.getVal<Long>(PrefName.UnreadChapterNotificationInterval) > 0L
                val hasCommentNotifications = PrefManager.getVal<Int>(PrefName.CommentNotificationInterval) > 0

                if (hasAnilistNotifications || hasSubscriptionNotifications ||
                    hasUnreadChapterNotifications || hasCommentNotifications) {
                    missingPermissions.add("Notifications")

                    // Disable notification settings
                    PrefManager.setVal(PrefName.AnilistNotificationInterval, 0)
                    PrefManager.setVal(PrefName.SubscriptionNotificationIntervalMinutes, 0L)
                    PrefManager.setVal(PrefName.UnreadChapterNotificationInterval, 0L)
                    PrefManager.setVal(PrefName.CommentNotificationInterval, 0)
                    hasDisabledSettings = true
                }
            }
        }

        // Check SCHEDULE_EXACT_ALARM permission for alarm manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)

            if (useAlarmManager) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val canScheduleExactAlarms = alarmManager.canScheduleExactAlarms()

                if (!canScheduleExactAlarms) {
                    missingPermissions.add("Schedule Exact Alarms")

                    // Disable alarm manager setting
                    PrefManager.setVal(PrefName.UseAlarmManager, false)
                    hasDisabledSettings = true
                }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            showPermissionWarningDialog(missingPermissions, hasDisabledSettings)
        } else {
            restartApp()
        }
    }

    private fun showPermissionWarningDialog(missingPermissions: List<String>, hasDisabledSettings: Boolean) {
        val permissionsList = missingPermissions.joinToString("\n• ", prefix = "• ")

        val message = if (hasDisabledSettings) {
            getString(R.string.restore_permissions_warning_disabled, permissionsList)
        } else {
            getString(R.string.restore_permissions_warning, permissionsList)
        }

        customAlertDialog().apply {
            setTitle(R.string.permissions_required)
            setMessage(message)
            setPosButton(R.string.ok) {
                restartApp()
            }
            setCancelable(false)
            show()
        }
    }
}
