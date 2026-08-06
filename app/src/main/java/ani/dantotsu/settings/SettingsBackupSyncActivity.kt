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
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import android.content.Intent
import ani.dantotsu.connections.handoff.HandoffScanActivity
import ani.dantotsu.connections.sync.CloudSync
import ani.dantotsu.connections.sync.CloudWipe
import ani.dantotsu.connections.sync.applyScannedSyncCode
import ani.dantotsu.connections.sync.ExtensionSettingsStore
import ani.dantotsu.connections.sync.ExtensionSettingsSync
import ani.dantotsu.connections.sync.ExtensionSync
import ani.dantotsu.connections.sync.ExtensionSyncNotice
import ani.dantotsu.connections.sync.SyncConflictNotice
import ani.dantotsu.connections.sync.SyncStatus
import ani.dantotsu.connections.sync.SyncClock
import ani.dantotsu.connections.sync.SyncIdentity
import ani.dantotsu.connections.sync.showCloudSyncConflictDialog
import ani.dantotsu.connections.sync.showSyncCodeDialog
import ani.dantotsu.connections.sync.showSyncSetupDialog
import ani.dantotsu.databinding.ActivitySettingsBackupSyncBinding
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.savePrefsToDownloads
import ani.dantotsu.settings.saving.BackupArchive
import ani.dantotsu.settings.saving.BackupSection
import ani.dantotsu.settings.saving.BackupTree
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.settings.saving.internal.PreferenceKeystore
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import ani.dantotsu.util.AppNotices
import ani.dantotsu.util.StoragePermissions
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sub-screen of the Common settings grouping everything related to moving settings off the device:
 * local backup/restore (the existing .ani/.sani export-import) and cloud sync over the Anilist
 * account ([CloudSync]) — the manual "Sync now" action, the master enable toggle, and the
 * both-sides-changed conflict prompt.
 */
class SettingsBackupSyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBackupSyncBinding

    /** Held so the "Sync now" row can be re-described after something changes the cloud. */
    private var settingsItems: MutableList<Settings>? = null
    private var settingsAdapter: SettingsAdapter? = null

    /**
     * Scanning another device's sync-code QR. Registered here rather than in the dialog that offers
     * it: an activity result contract has to exist before the screen starts, which a dialog raised
     * from a click handler is far too late to do.
     */
    private val scanSyncCode =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val scanned = result.data?.getStringExtra(HandoffScanActivity.EXTRA_RAW_RESULT)
            // recreate() belongs inside the callback, not after the call: applyScannedSyncCode
            // returns as soon as linking succeeds, well before the async migration and the
            // linked-confirmation dialog it shows — recreating right after that return raced the
            // dialog and tore the activity down before it could appear.
            applyScannedSyncCode(scanned) { recreate() }
        }

    private fun launchSyncCodeScan() {
        scanSyncCode.launch(
            Intent(this, HandoffScanActivity::class.java)
                .putExtra(HandoffScanActivity.EXTRA_RAW_RESULT, true)
        )
    }

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

        // Nothing below the sync-code row can do anything without the secret — the nodes are named
        // from it — so they're greyed out rather than left tappable and silently inert. Captured
        // once because every path that changes it recreates this screen.
        val linked = SyncIdentity.isLinked()

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
                isEnabled = linked,
                isChecked = PrefManager.getVal(PrefName.CloudSyncEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.CloudSyncEnabled, isChecked)
                    // Turning it off here can strand a sync banner on this very screen, where no
                    // resume will come along to re-evaluate it.
                    AppNotices.dismissStale()
                },
            ),
            Settings(
                type = 1,
                // The only row that stays live when unlinked, because it is the way out of that
                // state. It names the action available rather than the thing it manages: there is
                // no code to show yet, so "Sync code" would be describing something that doesn't
                // exist.
                name = getString(if (linked) R.string.sync_code_title else R.string.sync_setup_title),
                desc = getString(
                    if (linked) R.string.sync_code_desc else R.string.sync_code_desc_unlinked
                ),
                icon = R.drawable.ic_round_lock_24,
                onClick = {
                    if (Anilist.token.isNullOrEmpty()) {
                        toast(getString(R.string.cloud_sync_no_account))
                    } else if (linked) {
                        showSyncCodeDialog(onChanged = { recreate() })
                    } else {
                        showSyncSetupDialog(onScan = { launchSyncCodeScan() }) { recreate() }
                    }
                },
            ),
            Settings(
                type = 1,
                name = getString(R.string.cloud_sync_now),
                // The row doubles as the status line. A sync that works is invisible by design, so
                // without this there was nothing anywhere saying whether it ever had.
                desc = lastSyncedLine(),
                icon = R.drawable.ic_round_sync_24,
                isEnabled = linked,
                onClick = { view ->
                    when {
                        Anilist.token.isNullOrEmpty() ->
                            toast(getString(R.string.cloud_sync_no_account))

                        !PrefManager.getVal<Boolean>(PrefName.CloudSyncEnabled) ->
                            toast(getString(R.string.cloud_sync_is_disabled))

                        !SyncIdentity.isLinked() ->
                            toast(getString(R.string.sync_not_linked))

                        else -> {
                            toast(getString(R.string.please_wait))
                            // A manual sync is a round trip to the cloud and back with no other
                            // progress to show, so spin the row's icon until it resolves.
                            view.settingsIcon.setSpinning(true)
                            GlobalScope.launch(Dispatchers.IO) {
                                val result = CloudSync.syncManual()
                                runOnUiThread { view.settingsIcon.setSpinning(false) }
                                when (result) {
                                    is CloudSync.SyncOutcome.Conflict ->
                                        runOnUiThread { showConflictDialog(result) }

                                    is CloudSync.SyncOutcome.Merged ->
                                        runOnUiThread {
                                            toast(getString(R.string.cloud_sync_merged))
                                            applyRestore()
                                        }

                                    is CloudSync.SyncOutcome.Pulled ->
                                        runOnUiThread {
                                            toast(getString(R.string.cloud_sync_done_updated))
                                            applyRestore()
                                        }

                                    // These two don't recreate the screen the way the others do, so
                                    // the row would keep showing the timestamps from before.
                                    is CloudSync.SyncOutcome.Pushed ->
                                        runOnUiThread {
                                            afterForcedSync(overwroteCloud = false)
                                            toast(getString(R.string.cloud_sync_done))
                                        }

                                    is CloudSync.SyncOutcome.UpToDate ->
                                        runOnUiThread {
                                            afterForcedSync(overwroteCloud = false)
                                            toast(getString(R.string.cloud_sync_up_to_date))
                                        }

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
                isEnabled = linked,
                isChecked = PrefManager.getVal(PrefName.SyncExtensionsEnabled),
                switch = { isChecked, _ ->
                    PrefManager.setVal(PrefName.SyncExtensionsEnabled, isChecked)
                    AppNotices.dismissStale()
                },
            ),
            Settings(
                type = 1,
                name = getString(R.string.sync_extensions_now),
                desc = getString(R.string.sync_extensions_now_desc),
                icon = R.drawable.ic_extension,
                isEnabled = linked,
                onClick = {
                    when {
                        Anilist.token.isNullOrEmpty() ->
                            toast(getString(R.string.cloud_sync_no_account))

                        !PrefManager.getVal<Boolean>(PrefName.SyncExtensionsEnabled) ->
                            toast(getString(R.string.sync_extensions_is_disabled))

                        !SyncIdentity.isLinked() ->
                            toast(getString(R.string.sync_not_linked))

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
                isEnabled = linked,
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
                isEnabled = linked,
                onClick = {
                    if (Anilist.token.isNullOrEmpty()) {
                        toast(getString(R.string.cloud_sync_no_account))
                    } else if (!SyncIdentity.isLinked()) {
                        toast(getString(R.string.sync_not_linked))
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
                                    val ok = settingsOk && extOk && extSettingsOk
                                    runOnUiThread {
                                        if (ok) afterForcedSync(overwroteCloud = true)
                                        toast(
                                            getString(
                                                if (ok) R.string.force_upload_done
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
                isEnabled = linked,
                onClick = {
                    if (Anilist.token.isNullOrEmpty()) {
                        toast(getString(R.string.cloud_sync_no_account))
                    } else if (!SyncIdentity.isLinked()) {
                        toast(getString(R.string.sync_not_linked))
                    } else {
                        customAlertDialog().apply {
                            setTitle(R.string.force_download_confirm_title)
                            setMessage(R.string.force_download_confirm_msg)
                            setPosButton(R.string.force_download) {
                                toast(getString(R.string.please_wait))
                                GlobalScope.launch(Dispatchers.IO) {
                                    val settingsOk = CloudSync.forcePull()
                                    // Was reported as success on the settings pull alone, so a
                                    // failed extension-settings pull looked like it had worked.
                                    val extOk =
                                        if (PrefManager.getVal<Boolean>(PrefName.SyncExtensionSettingsEnabled))
                                            ExtensionSettingsSync.forcePull() else true
                                    val ok = settingsOk && extOk
                                    runOnUiThread {
                                        if (ok) {
                                            // Before applyRestore, which recreates this screen —
                                            // the notices outlive it and would otherwise come
                                            // straight back on the rebuilt one.
                                            afterForcedSync(overwroteCloud = false)
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
            Settings(
                type = 1,
                name = getString(R.string.cloud_wipe),
                desc = getString(R.string.cloud_wipe_desc),
                icon = R.drawable.ic_round_delete_24,
                isEnabled = linked,
                onClick = {
                    if (Anilist.token.isNullOrEmpty()) {
                        toast(getString(R.string.cloud_sync_no_account))
                    } else {
                        // Only reachable while linked — the row is disabled otherwise, because
                        // without the code the encrypted copy can't even be named. Unlinking
                        // offers the wipe itself, which is the last moment it's possible.
                        customAlertDialog().apply {
                            setTitle(R.string.cloud_wipe_confirm_title)
                            setMessage(R.string.cloud_wipe_confirm_msg)
                            setPosButton(R.string.cloud_wipe) {
                                toast(getString(R.string.please_wait))
                                GlobalScope.launch(Dispatchers.IO) {
                                    val ok = CloudWipe.run()
                                    runOnUiThread {
                                        // The wipe cleared the notices' state and the local
                                        // baselines on a background thread; the cards and the
                                        // row still describing the deleted copy come down here.
                                        // Runs whether or not every node went — a partial wipe
                                        // leaves the row more wrong than a complete one.
                                        afterForcedSync(overwroteCloud = true)
                                        toast(
                                            getString(
                                                if (ok) R.string.cloud_wipe_done
                                                else R.string.cloud_wipe_partial
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
        )

        val adapter = SettingsAdapter(settingsList)
        settingsItems = settingsList
        settingsAdapter = adapter
        binding.backupSyncRecyclerView.adapter = adapter
        refreshCloudInfo()
        binding.backupSyncRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun showConflictDialog(conflict: CloudSync.SyncOutcome.Conflict) =
        showCloudSyncConflictDialog(conflict) { applyRestore() }

    /** "Last synced 3 minutes ago", or an invitation when it never has. */
    private fun lastSyncedLine(): String {
        val ts = CloudSync.lastSyncedAt()
        if (ts <= 0L) return getString(R.string.cloud_sync_never_synced)
        return getString(R.string.cloud_sync_last_synced, relativeTime(ts))
    }

    private fun relativeTime(ts: Long): CharSequence =
        android.text.format.DateUtils.getRelativeTimeSpanString(
            ts, SyncClock.nowCached(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        )

    /**
     * Adds what the *cloud* currently holds to the "Sync now" row — when it was last written and by
     * which device — under the line saying when this device last agreed with it.
     *
     * Those answer different questions. The local line tells you whether this device is behind; the
     * cloud line tells you what it would be catching up to, and from where. Seeing "saved 2 days
     * ago from Pixel 8" is what makes an unexpected sync result explainable.
     *
     * Fetched after the screen is up rather than before, so it never delays drawing, and left alone
     * entirely when it can't be read — an unreachable cloud is not an empty one, and the row must
     * not imply otherwise.
     */
    /**
     * Re-describes the "Sync now" row from scratch.
     *
     * Resets to the local line before re-fetching, so a failed read leaves no stale claim about the
     * cloud on screen — the previous copy's timestamp and device would otherwise sit there looking
     * current after the very action that replaced them.
     */
    private fun refreshCloudInfo() {
        val items = settingsItems ?: return
        val adapter = settingsAdapter ?: return
        val index = items.indexOfFirst { it.name == getString(R.string.cloud_sync_now) }
        if (index >= 0) {
            items[index] = items[index].copy(desc = lastSyncedLine())
            adapter.notifyItemChanged(index)
        }
        loadCloudInfoInto(items, adapter)
    }

    /**
     * Settles the local view of the world after the user has forced the cloud one way or the other.
     *
     * A forced sync is an answer to whatever sync was uncertain about, so anything still asking
     * about it is now wrong: a conflict banner has been decided by fiat, and — when this device
     * overwrote the cloud — so has any extension difference. Left alone they'd keep offering to
     * resolve something that no longer exists.
     *
     * @param overwroteCloud true for a force upload, where this device's extension list also became
     *   the cloud's. A force download doesn't touch the extension list, so its notice stands.
     */
    private fun afterForcedSync(overwroteCloud: Boolean) {
        SyncConflictNotice.clear()
        if (overwroteCloud) ExtensionSyncNotice.clear()
        SyncStatus.refresh()
        AppNotices.dismissStale()
        refreshCloudInfo()
    }

    private fun loadCloudInfoInto(items: MutableList<Settings>, adapter: SettingsAdapter) {
        if (!SyncIdentity.isLinked()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val info = runCatching { CloudSync.cloudInfo() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                val index = items.indexOfFirst { it.name == getString(R.string.cloud_sync_now) }
                if (index < 0) return@withContext
                val cloudLine = if (info.device.isNullOrBlank()) {
                    getString(R.string.cloud_sync_cloud_copy, relativeTime(info.ts))
                } else {
                    getString(
                        R.string.cloud_sync_cloud_copy_device, relativeTime(info.ts), info.device
                    )
                }
                items[index] = items[index].copy(desc = "${lastSyncedLine()}\n$cloudLine")
                adapter.notifyItemChanged(index)
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
            val withExtensionSettings = adapter.isSelected(BackupSection.ExtensionSettings)
            if (selected.isEmpty() && !withExtensionSettings) {
                toast(R.string.no_settings_selected)
                return@setOnClickListener
            }
            val keys = selected.map { it.name }.toSet()
            val involvedLocations = BackupTree.involvedLocations
            // Extension settings are now a row of their own, in a category flagged as holding
            // credentials, so ticking them asks for a password like everything else there — and
            // leaving them out keeps them out of the file entirely.
            val needsPassword = adapter.hasProtectedSelected()

            fun archive() = BackupArchive.pack(
                PrefManager.exportSelectedPrefs(involvedLocations, keys),
                if (withExtensionSettings) ExtensionSettingsStore.export(context) else null,
            )

            if (needsPassword) {
                passwordAlertDialog(true) { password ->
                    if (password != null) {
                        savePrefsToDownloads("DantotsuSettings", archive(), context, password)
                        dialog.dismiss()
                    } else {
                        toast(R.string.password_cannot_be_empty)
                    }
                }
            } else {
                savePrefsToDownloads("DantotsuSettings", archive(), context, null)
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
