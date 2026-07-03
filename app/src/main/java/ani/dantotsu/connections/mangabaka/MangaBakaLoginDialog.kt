package ani.dantotsu.connections.mangabaka

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaBakaLoginDialog : DialogFragment() {
    private var onLoginSuccess: (() -> Unit)? = null
    private var tokenInput: TextInputEditText? = null
    private var progressBar: ProgressBar? = null

    fun setOnLoginSuccessListener(listener: () -> Unit) {
        onLoginSuccess = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_login_mangabaka, null)
        tokenInput = view.findViewById(R.id.mangabakaToken)
        progressBar = view.findViewById(R.id.mangabakaLoginProgress)
        view.findViewById<View>(R.id.mangabakaGetToken)?.setOnClickListener {
            openLinkInBrowser("${MangaBaka.WEB_URL}/my/settings/api-and-apps")
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.MyPopup)
            .setTitle(getString(R.string.login_to_mangabaka))
            .setView(view)
            .setPositiveButton(getString(R.string.login), null)
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> dismiss() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val pat = tokenInput?.text?.toString()?.trim()

                if (pat.isNullOrBlank()) {
                    tokenInput?.error = getString(R.string.mangabaka_token_required)
                    return@setOnClickListener
                }

                positiveButton.isEnabled = false
                progressBar?.visibility = View.VISIBLE

                lifecycleScope.launch(Dispatchers.IO) {
                    val success = MangaBaka.login(pat)

                    withContext(Dispatchers.Main) {
                        progressBar?.visibility = View.GONE

                        if (success) {
                            Logger.log("MangaBaka: Login successful")
                            snackString(getString(R.string.mangabaka_login_success))
                            onLoginSuccess?.invoke()
                            dismiss()
                        } else {
                            Logger.log("MangaBaka: Login failed")
                            snackString(getString(R.string.mangabaka_login_failed))
                            positiveButton.isEnabled = true
                            tokenInput?.error = getString(R.string.mangabaka_token_invalid)
                        }
                    }
                }
            }
        }

        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tokenInput = null
        progressBar = null
    }
}
