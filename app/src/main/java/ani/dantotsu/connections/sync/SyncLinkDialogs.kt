package ani.dantotsu.connections.sync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.handoff.HandoffQr
import ani.dantotsu.databinding.DialogSyncCodeBinding
import ani.dantotsu.databinding.DialogSyncLinkedBinding
import ani.dantotsu.databinding.DialogUserAgentBinding
import ani.dantotsu.loadImage
import ani.dantotsu.toast
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The linking UI: how a sync secret gets from the device that made it to the ones that need it.
 *
 * Nothing here talks to the network. The code shown *is* the secret — it is never uploaded, so
 * there is no dead-drop to intercept and no window in which it exists anywhere but on the two
 * screens involved. The QR is a convenience for phones; typing the code is the mechanism.
 */

/**
 * Shows this device's code so another one can join, with a QR for devices that can scan.
 *
 * @param onChanged invoked when the link state changed here (i.e. the user unlinked).
 * @param onClosed invoked when the dialog goes away having changed nothing — used by the
 *   create-a-code flow to refresh the screen underneath only once the code has been read.
 */
fun Activity.showSyncCodeDialog(onChanged: () -> Unit, onClosed: (() -> Unit)? = null) {
    // A linked device always holds a code, so this is a guard rather than a case: fall back to
    // setup rather than showing an empty dialog if the secret ever turns out to be unreadable.
    val code = SyncIdentity.displayCode() ?: run {
        showSyncSetupDialog(onChanged = onChanged)
        return
    }

    val binding = DialogSyncCodeBinding.inflate(layoutInflater)
    binding.syncCodeText.text = code
    binding.syncCodeQr.setImageBitmap(HandoffQr.encode(code.replace("-", "")))

    var dialogRef: android.app.AlertDialog? = null
    customAlertDialog().apply {
        setTitle(R.string.sync_code_title)
        setCustomView(binding.root)
        // A dialog's default width can't fit the grouped code on one line, and the QR wants the
        // room too. Captured before show, resized right after it — the window has no decor view
        // until then. Done synchronously rather than from a show listener: that callback is a
        // posted Handler message, dispatched after the first frame has already gone out, so the
        // dialog would flash at its default width before snapping to this one.
        attach { dialogRef = it }
        setPosButton(R.string.sync_code_copy) {
            copyToClipboard(code)
            toast(getString(R.string.sync_code_copied))
        }
        setNegButton(R.string.close) {}
        setNeutralButton(R.string.sync_unlink) { confirmUnlink(onChanged) }
        onClosed?.let { onDismiss(it) }
        show()
    }
    dialogRef?.widenToScreen()
}

/** Takes the width a dialog is normally capped at and gives it nearly the whole screen. */
private fun android.app.AlertDialog.widenToScreen() {
    runCatching {
        val width = (context.resources.displayMetrics.widthPixels * 0.95f).toInt()
        window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

/**
 * Offers the ways a device with no secret can get one. Creating one makes this device the origin;
 * the other two join an existing set.
 *
 * @param onScan when provided, offered as a shortcut for entering the code by hand. Callers supply
 *   it by launching [ani.dantotsu.connections.handoff.HandoffScanActivity] in raw mode — an
 *   activity result has to be registered before the screen starts, which a dialog can't do for
 *   itself. Devices without a camera (and desktop-class installs generally) simply won't pass one.
 */
fun Activity.showSyncSetupDialog(onScan: (() -> Unit)? = null, onChanged: () -> Unit) {
    customAlertDialog().apply {
        setTitle(R.string.sync_setup_title)
        setMessage(R.string.sync_setup_message)
        setPosButton(R.string.sync_setup_create) { createCode(onChanged) }
        setNegButton(R.string.sync_setup_enter) { promptForCode(onScan, onChanged) }
        show()
    }
}

/** Applies a code that came back from the scanner. @return whether it was accepted. */
fun Activity.applyScannedSyncCode(scanned: String?, onChanged: () -> Unit): Boolean {
    if (scanned == null || !SyncIdentity.linkWithCode(scanned)) {
        toast(getString(R.string.sync_code_invalid))
        return false
    }
    migrateThen { showSyncLinkedDialog(onClosed = onChanged) }
    return true
}

/**
 * Creates the secret, seeds the cloud with this device, and shows the code to carry across.
 *
 * All three matter and in that order. The migration is part of linking because this is the first
 * moment there is somewhere safe to put data currently sitting in the open. The upload is what
 * makes the *second* device's setup work at all — without it the cloud stays empty until something
 * happens to trigger a background push, and a user who links two devices back to back finds nothing
 * to sync. And the code has to be on screen at the end, because carrying it to the other device is
 * the only reason any of this was done.
 *
 * The previous version refreshed the settings screen first, which destroyed the activity the code
 * dialog was about to be shown on — so it silently never appeared.
 */
private fun Activity.createCode(onChanged: () -> Unit) {
    SyncIdentity.generateCode()
    // Straight to the code — the upload is not something to make the user wait behind. Four network
    // round trips could take the better part of a minute on a bad connection, and none of it
    // changes what the code says.
    showSyncCodeDialogThen(onChanged)
    // Unscoped on purpose: this has to outlive the dialog and the screen refresh that follows it.
    CoroutineScope(Dispatchers.IO).launch {
        runCatching { SyncMigration.run() }
            .onFailure { Logger.log("SyncLink: migration threw: ${it.message}") }
        // Linking reset this device's baselines, so every module now sees itself as changed and
        // uploads in full. Each is a no-op when its own toggle is off.
        runCatching { CloudSync.pushNow() }
        runCatching { ProgressSync.pushNow() }
        runCatching { ExtensionSync.pushNow() }
        runCatching { ExtensionSettingsSync.pushNow() }
        Logger.log("SyncLink: seeded the cloud from this device")
    }
}

/** Shows the code, and refreshes the caller once it's dismissed rather than out from under it. */
private fun Activity.showSyncCodeDialogThen(onChanged: () -> Unit) {
    var changed = false
    showSyncCodeDialog(onChanged = { changed = true }, onClosed = {
        if (!changed) onChanged()
    })
}

private fun Activity.promptForCode(onScan: (() -> Unit)?, onChanged: () -> Unit) {
    val binding = DialogUserAgentBinding.inflate(layoutInflater)
    binding.userAgentTextBox.apply {
        hint = getString(R.string.sync_code_hint)
        setSingleLine()
        // The alphabet has no lowercase, and a user typing the separators shouldn't be punished.
        filters = arrayOf(android.text.InputFilter.AllCaps())
    }

    customAlertDialog().apply {
        setTitle(R.string.sync_setup_enter)
        setMessage(R.string.sync_code_enter_hint)
        setCustomView(binding.root)
        onScan?.let { setNeutralButton(R.string.sync_code_scan) { it() } }
        setPosButton(R.string.ok) {
            val entered = binding.userAgentTextBox.text?.toString().orEmpty()
            if (SyncIdentity.linkWithCode(entered)) {
                migrateThen { showSyncLinkedDialog(onClosed = onChanged) }
            } else {
                // The checksum caught it. Without that check this would have silently linked to an
                // empty corner of the database and looked like "sync just stopped working".
                toast(getString(R.string.sync_code_invalid))
            }
        }
        setNegButton(R.string.cancel) {}
        show()
    }
}

/**
 * Unlinking, with the way out of the trap it would otherwise create.
 *
 * The cloud copy is addressed by a path derived from the sync code, so giving up the code without
 * keeping it means the stored data can no longer be read *or deleted* — by this device or any
 * other. It doesn't disappear; it becomes unreachable, permanently. So the wipe is offered here,
 * while the key to do it still exists, rather than left to a later attempt that would silently
 * accomplish nothing.
 */
private fun Activity.confirmUnlink(onChanged: () -> Unit) {
    customAlertDialog().apply {
        setTitle(R.string.sync_unlink)
        setMessage(R.string.sync_unlink_confirm)
        setPosButton(R.string.sync_unlink) {
            SyncIdentity.unlink()
            onChanged()
            toast(getString(R.string.sync_unlinked))
        }
        setNeutralButton(R.string.sync_unlink_and_wipe) { wipeThenUnlink(onChanged) }
        setNegButton(R.string.cancel) {}
        show()
    }
}

/** Deletes the cloud copy first — it can only be done while this device still holds the code. */
private fun Activity.wipeThenUnlink(onChanged: () -> Unit) {
    toast(getString(R.string.please_wait))
    CoroutineScope(Dispatchers.IO).launch {
        val wiped = runCatching { CloudWipe.run() }.getOrDefault(false)
        // Unlink regardless: the user asked to, and refusing would strand them with a code they
        // wanted rid of. A failed wipe is reported, not silently swallowed.
        SyncIdentity.unlink()
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            onChanged()
            toast(
                getString(
                    if (wiped) R.string.sync_unlinked_and_wiped else R.string.cloud_wipe_partial
                )
            )
        }
    }
}

/**
 * Runs the plaintext migration for a device that has just been linked, then [then].
 *
 * A failure isn't surfaced as an error: the user asked to link, and linking worked. The old data is
 * simply left where it is and the next link attempt tries again — which is the safe direction,
 * since the alternative to a failed copy is a successful delete.
 *
 * Deliberately doesn't refresh the caller's screen itself — [then] is responsible for that, once
 * whatever it shows (the linked-confirmation dialog) has been seen. Refreshing here used to race
 * it: a caller-supplied `recreate()` tore the activity down before the dialog could appear, so the
 * feedback silently never showed.
 */
private fun Activity.migrateThen(then: () -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        val moved = runCatching { SyncMigration.run() }.getOrElse {
            Logger.log("SyncLink: migration threw: ${it.message}")
            false
        }
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            if (!moved) Logger.log("SyncLink: legacy data left in place for now")
            then()
        }
    }
}

/**
 * Feedback after a code is accepted: which account it's now scoped to, and what the cloud already
 * holds — so linking isn't just a toast the user has to take on faith, the same way scanning a
 * handoff QR shows what was received before anything happens with it.
 *
 * The avatar/name are this device's own signed-in Anilist identity, not anything carried by the
 * code itself — the code only proves which cloud node to talk to, and that node is scoped by
 * whichever account reads it (see [SyncIdentity]). Showing it here is a sanity check: if it's not
 * the account you meant to sync, the code was for a different one.
 *
 * @param onClosed run once the dialog is dismissed — this is where the caller's screen refresh
 *   belongs, so it can't tear the dialog down before the user has seen it.
 */
private fun Activity.showSyncLinkedDialog(onClosed: () -> Unit) {
    val binding = DialogSyncLinkedBinding.inflate(layoutInflater)
    binding.syncLinkedAvatar.loadImage(Anilist.avatar)
    binding.syncLinkedName.text = Anilist.username ?: getString(R.string.unknown)
    binding.syncLinkedInfo.text = getString(R.string.please_wait)

    // The default dialog width is narrow enough that the device-info line wraps awkwardly and the
    // whole card reads as cramped; widen it the same way the sync-code dialog does. Resized
    // synchronously right after show(), not from a show listener — see the comment in
    // showSyncCodeDialog for why the listener causes a visible flash at the default width.
    var dialogRef: android.app.AlertDialog? = null
    customAlertDialog().apply {
        setTitle(R.string.sync_linked)
        setCustomView(binding.root)
        attach { dialogRef = it }
        setPosButton(R.string.ok) {}
        onDismiss(onClosed)
        show()
    }
    dialogRef?.widenToScreen()

    CoroutineScope(Dispatchers.IO).launch {
        val info = runCatching { CloudSync.cloudInfo() }.getOrNull()
        withContext(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            binding.syncLinkedInfo.text = when {
                info == null -> getString(R.string.cloud_sync_never_synced)
                info.device.isNullOrBlank() ->
                    getString(R.string.cloud_sync_cloud_copy, relativeTime(info.ts))

                else -> getString(
                    R.string.cloud_sync_cloud_copy_device, relativeTime(info.ts), info.device
                )
            }
        }
    }
}

private fun relativeTime(ts: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(ts, SyncClock.nowCached(), DateUtils.MINUTE_IN_MILLIS)

private fun Context.copyToClipboard(text: String) {
    runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.sync_code_title), text))
    }
}
