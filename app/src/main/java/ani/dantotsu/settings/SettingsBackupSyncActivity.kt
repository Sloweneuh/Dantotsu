package ani.dantotsu.settings

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.sync.CloudSync
import ani.dantotsu.connections.sync.ExtensionSettingsStore
import ani.dantotsu.connections.sync.ExtensionSettingsSync
import ani.dantotsu.connections.sync.ExtensionSync
import ani.dantotsu.connections.sync.showCloudSyncConflictDialog
import ani.dantotsu.databinding.ActivitySettingsBackupSyncBinding
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.savePrefsToDownloads
import ani.dantotsu.settings.saving.BackupArchive
import ani.dantotsu.settings.saving.BackupTree
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.PreferenceKeystore
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Sub-screen of the Common settings grouping everything related to moving settings off the device:
 * local backup/restore (the existing .ani/.sani export-import) and cloud sync over the Anilist
 * account ([CloudSync]) — the manual "Sync now" action, the master enable toggle, and the
 * both-sides-changed conflict prompt.
 */
class SettingsBackupSyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBackupSyncBinding

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { ani.dantotsu.util.LanguageHelper.applyLanguageToContext(it) })
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsBackupSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SettingsRouter.handleHighlight(this, binding.backupSyncRecyclerView)

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
                                    if (BackupArchive.restore(this@SettingsBackupSyncActivity, decryptedJson)) {
                                        checkPermissionsAfterRestore()
                                    }
                                } else {
                                    toast(getString(R.string.password_cannot_be_empty))
                                }
                            }
                        } else if (name.endsWith(".ani")) {
                            val decryptedJson = jsonString.toString(Charsets.UTF_8)
                            if (BackupArchive.restore(this@SettingsBackupSyncActivity, decryptedJson)) {
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

        binding.settingsBackupSyncLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        binding.backupSyncSettingsBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val settingsList = arrayListOf(
            Settings(
                type = 1,
                name = getString(R.string.backup_restore),
                desc = getString(R.string.backup_restore_desc),
                icon = R.drawable.backup_restore,
                onClick = {
                    showBackupRestoreChooser(openDocumentLauncher)
                },
            ),
            Settings(
                type = 2,
                name = getString(R.string.cloud_sync),
                desc = getString(R.string.cloud_sync_desc),
                icon = R.drawable.ic_round_sync_24,
                isChecked = PrefManager.getVal(PrefName.CloudSyncEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.CloudSyncEnabled, isChecked)
                },
            ),
            Settings(
                type = 1,
                name = getString(R.string.cloud_sync_now),
                desc = getString(R.string.cloud_sync_now_desc),
                icon = R.drawable.ic_round_sync_24,
                onClick = {
                    when {
                        Anilist.token.isNullOrEmpty() ->
                            toast(getString(R.string.cloud_sync_no_account))

                        !PrefManager.getVal<Boolean>(PrefName.CloudSyncEnabled) ->
                            toast(getString(R.string.cloud_sync_is_disabled))

                        else -> {
                            toast(getString(R.string.please_wait))
                            GlobalScope.launch(Dispatchers.IO) {
                                when (val result = CloudSync.syncManual()) {
                                    is CloudSync.SyncOutcome.Conflict ->
                                        runOnUiThread {
                                            showConflictDialog(
                                                result.remotePayload, result.remoteTs, result.remoteDevice
                                            )
                                        }

                                    is CloudSync.SyncOutcome.Pulled ->
                                        runOnUiThread {
                                            toast(getString(R.string.cloud_sync_done_updated))
                                            applyRestore()
                                        }

                                    is CloudSync.SyncOutcome.Pushed ->
                                        toast(getString(R.string.cloud_sync_done))

                                    is CloudSync.SyncOutcome.UpToDate ->
                                        toast(getString(R.string.cloud_sync_up_to_date))

                                    is CloudSync.SyncOutcome.Failed ->
                                        toast(getString(R.string.cloud_sync_failed))

                                    else -> {} // Disabled/NoUser already guarded above
                                }
                            }
                        }
                    }
                },
            ),
            Settings(
                type = 2,
                name = getString(R.string.sync_extensions),
                desc = getString(R.string.sync_extensions_desc),
                icon = R.drawable.ic_extension,
                isChecked = PrefManager.getVal(PrefName.SyncExtensionsEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.SyncExtensionsEnabled, isChecked)
                },
            ),
            Settings(
                type = 1,
                name = getString(R.string.sync_extensions_now),
                desc = getString(R.string.sync_extensions_now_desc),
                icon = R.drawable.ic_extension,
                onClick = {
                    when {
                        Anilist.token.isNullOrEmpty() ->
                            toast(getString(R.string.cloud_sync_no_account))

                        !PrefManager.getVal<Boolean>(PrefName.SyncExtensionsEnabled) ->
                            toast(getString(R.string.sync_extensions_is_disabled))

                        else -> {
                            toast(getString(R.string.please_wait))
                            GlobalScope.launch(Dispatchers.IO) {
                                // Don't push here: computeDiff() compares against the other
                                // device's cloud set and publishes ours itself when appropriate.
                                val diff = ExtensionSync.computeDiff()
                                runOnUiThread {
                                    if (diff == null) {
                                        toast(getString(R.string.cloud_sync_failed))
                                    } else if (diff.toInstall.isEmpty() && diff.toRemove.isEmpty()) {
                                        toast(getString(R.string.cloud_sync_up_to_date))
                                    } else {
                                        showExtensionReconcileDialog(diff)
                                    }
                                }
                            }
                        }
                    }
                },
            ),
            Settings(
                type = 2,
                name = getString(R.string.sync_extension_settings),
                desc = getString(R.string.sync_extension_settings_desc),
                icon = R.drawable.ic_extension,
                isChecked = PrefManager.getVal(PrefName.SyncExtensionSettingsEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.SyncExtensionSettingsEnabled, isChecked)
                },
            ),
            Settings(
                type = 1,
                name = getString(R.string.force_upload),
                desc = getString(R.string.force_upload_desc),
                icon = R.drawable.ic_round_cloud_upload_24,
                onClick = {
                    if (Anilist.token.isNullOrEmpty()) {
                        toast(getString(R.string.cloud_sync_no_account))
                    } else {
                        customAlertDialog().apply {
                            setTitle(R.string.force_upload_confirm_title)
                            setMessage(R.string.force_upload_confirm_msg)
                            setPosButton(R.string.force_upload) {
                                toast(getString(R.string.please_wait))
                                GlobalScope.launch(Dispatchers.IO) {
                                    val settingsOk = CloudSync.forcePush()
                                    val extOk = ExtensionSync.forcePush()
                                    // Only push extension settings (may hold logins) when opted in.
                                    val extSettingsOk =
                                        if (PrefManager.getVal<Boolean>(PrefName.SyncExtensionSettingsEnabled))
                                            ExtensionSettingsSync.forcePush() else true
                                    runOnUiThread {
                                        toast(
                                            getString(
                                                if (settingsOk && extOk && extSettingsOk) R.string.force_upload_done
                                                else R.string.cloud_sync_failed
                                            )
                                        )
                                    }
                                }
                            }
                            setNegButton(R.string.cancel) {}
                            show()
                        }
                    }
                },
            ),
            Settings(
                type = 1,
                name = getString(R.string.force_download),
                desc = getString(R.string.force_download_desc),
                icon = R.drawable.ic_round_cloud_download_24,
                onClick = {
                    if (Anilist.token.isNullOrEmpty()) {
                        toast(getString(R.string.cloud_sync_no_account))
                    } else {
                        customAlertDialog().apply {
                            setTitle(R.string.force_download_confirm_title)
                            setMessage(R.string.force_download_confirm_msg)
                            setPosButton(R.string.force_download) {
                                toast(getString(R.string.please_wait))
                                GlobalScope.launch(Dispatchers.IO) {
                                    val ok = CloudSync.forcePull()
                                    if (PrefManager.getVal<Boolean>(PrefName.SyncExtensionSettingsEnabled))
                                        ExtensionSettingsSync.forcePull()
                                    runOnUiThread {
                                        if (ok) {
                                            toast(getString(R.string.force_download_done))
                                            applyRestore()
                                        } else {
                                            toast(getString(R.string.cloud_sync_failed))
                                        }
                                    }
                                }
                            }
                            setNegButton(R.string.cancel) {}
                            show()
                        }
                    }
                },
            ),
        )

        binding.backupSyncRecyclerView.adapter = SettingsAdapter(settingsList)
        binding.backupSyncRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun showConflictDialog(remotePayload: String, remoteTs: Long, remoteDevice: String?) =
        showCloudSyncConflictDialog(remotePayload, remoteTs, remoteDevice) { applyRestore() }

    @OptIn(DelicateCoroutinesApi::class)
    private fun showExtensionReconcileDialog(diff: ExtensionSync.Diff) {
        // One flat, user-driven list: installs first, then removals. Installs that can still be
        // found in the repos are pre-checked (additive, safe); removals are unchecked so deleting
        // an extension is always a deliberate opt-in.
        val items = diff.toInstall + diff.toRemove
        val checked = items.map { it.isInstall && it.available }.toBooleanArray()

        val pad = (8 * resources.displayMetrics.density).toInt()
        val recycler = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsBackupSyncActivity)
            adapter = ExtensionReconcileAdapter(items, checked)
            setPadding(0, pad, 0, pad)
        }

        AlertDialog.Builder(this, R.style.MyPopup)
            .setTitle(R.string.sync_extensions)
            .setView(recycler)
            .setPositiveButton(R.string.ext_reconcile_apply) { _, _ ->
                var installed = 0
                var removed = 0
                items.forEachIndexed { i, item ->
                    if (!checked[i]) return@forEachIndexed
                    if (item.isInstall) {
                        if (item.available) {
                            ExtensionSync.install(item)
                            installed++
                        }
                    } else {
                        ExtensionSync.uninstall(item)
                        removed++
                    }
                }
                // Do NOT push here: install/uninstall are async and localPayload() would still
                // reflect the pre-reconcile state. The SyncPushWorker enqueued from App.kt fires
                // once the installs have completed and the extension flow has settled.
                toast(getString(R.string.ext_reconcile_summary, installed, removed))
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
            .show()
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

    private fun showBackupRestoreChooser(
        openDocumentLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    ) {
        StoragePermissions.downloadsPermission(this)
        customAlertDialog().apply {
            setTitle(R.string.backup_restore)
            setMessage(R.string.backup_restore_chooser_msg)
            setPosButton(R.string.button_backup) {
                showBackupOptionsDialog()
            }
            setNegButton(R.string.button_restore) {
                openDocumentLauncher.launch(arrayOf("*/*"))
            }
            setNeutralButton(R.string.cancel) {}
            show()
        }
    }

    private fun showBackupOptionsDialog() {
        val context = this
        val dialogBinding =
            ani.dantotsu.databinding.DialogBackupOptionsBinding.inflate(layoutInflater)

        val dialog = AlertDialog.Builder(this, R.style.MyPopup)
            .setTitle(R.string.backup_select_what_msg)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.button_backup, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        var adapter: BackupOptionsAdapter? = null
        adapter = BackupOptionsAdapter {
            val count = adapter?.selectedPrefs()?.size ?: 0
            dialogBinding.backupSelectionSummary.text =
                resources.getQuantityString(R.plurals.backup_items_selected, count, count)
        }
        dialogBinding.backupRecycler.layoutManager = LinearLayoutManager(this)
        dialogBinding.backupRecycler.adapter = adapter
        dialogBinding.backupSelectionSummary.text =
            resources.getQuantityString(R.plurals.backup_items_selected, 0, 0)

        dialogBinding.backupSelectAll.setOnClickListener { adapter.selectAll() }
        dialogBinding.backupSelectNone.setOnClickListener { adapter.selectNone() }

        dialog.window?.apply {
            setDimAmount(0.5f)
            attributes.windowAnimations = android.R.style.Animation_Dialog
        }
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selected = adapter.selectedPrefs()
            if (selected.isEmpty()) {
                toast(R.string.no_settings_selected)
                return@setOnClickListener
            }
            val keys = selected.map { it.name }.toSet()
            val involvedLocations = BackupTree.involvedLocations
            val needsPassword = adapter.hasProtectedSelected()
            if (needsPassword) {
                passwordAlertDialog(true) { password ->
                    if (password != null) {
                        savePrefsToDownloads(
                            "DantotsuSettings",
                            BackupArchive.pack(
                                PrefManager.exportSelectedPrefs(involvedLocations, keys),
                                ExtensionSettingsStore.export(context),
                            ),
                            context,
                            password,
                        )
                        dialog.dismiss()
                    } else {
                        toast(R.string.password_cannot_be_empty)
                    }
                }
            } else {
                savePrefsToDownloads(
                    "DantotsuSettings",
                    BackupArchive.pack(
                        PrefManager.exportSelectedPrefs(involvedLocations, keys),
                        ExtensionSettingsStore.export(context),
                    ),
                    context,
                    null,
                )
                dialog.dismiss()
            }
        }
    }

    private fun reloadSourcesFromPrefs() {
        AnimeSources.pinnedAnimeSources =
            PrefManager.getNullableVal<List<String>>(PrefName.AnimeSourcesOrder, null)
                ?: emptyList()
        AnimeSources.performReorderAnimeSources()
        MangaSources.pinnedMangaSources =
            PrefManager.getNullableVal<List<String>>(PrefName.MangaSourcesOrder, null)
                ?: emptyList()
        MangaSources.performReorderMangaSources()
        NovelSources.pinnedNovelSources =
            PrefManager.getNullableVal<List<String>>(PrefName.NovelSourcesOrder, null)
                ?: emptyList()
        NovelSources.performReorderNovelSources()
    }

    private fun checkPermissionsAfterRestore() {
        reloadSourcesFromPrefs()
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
            applyRestore()
        }
    }

    private fun applyRestore() {
        PrefManager.setCustomVal("reload", true)
        recreate()
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
                applyRestore()
            }
            setCancelable(false)
            show()
        }
    }
}
