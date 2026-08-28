package ani.dantotsu.connections.kitsu

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KitsuLoginDialog : DialogFragment() {
    private var onLoginSuccess: (() -> Unit)? = null
    private var usernameInput: TextInputEditText? = null
    private var passwordInput: TextInputEditText? = null
    private var progressBar: ProgressBar? = null

    fun setOnLoginSuccessListener(listener: () -> Unit) {
        onLoginSuccess = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_login_kitsu, null)
        usernameInput = view.findViewById(R.id.kitsuUsername)
        passwordInput = view.findViewById(R.id.kitsuPassword)
        progressBar = view.findViewById(R.id.kitsuLoginProgress)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.MyPopup)
            .setTitle(getString(R.string.login_to_kitsu))
            .setView(view)
            .setPositiveButton(getString(R.string.login), null)
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> dismiss() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val username = usernameInput?.text?.toString()?.trim()
                val password = passwordInput?.text?.toString()

                if (username.isNullOrBlank()) {
                    usernameInput?.error = getString(R.string.kitsu_username_required)
                    return@setOnClickListener
                }
                if (password.isNullOrBlank()) {
                    passwordInput?.error = getString(R.string.kitsu_password_required)
                    return@setOnClickListener
                }

                positiveButton.isEnabled = false
                progressBar?.visibility = View.VISIBLE

                lifecycleScope.launch(Dispatchers.IO) {
                    val success = Kitsu.login(username, password)
                    withContext(Dispatchers.Main) {
                        progressBar?.visibility = View.GONE
                        if (success) {
                            Logger.log("Kitsu: Login successful")
                            snackString(getString(R.string.kitsu_login_success))
                            onLoginSuccess?.invoke()
                            dismiss()
                        } else {
                            Logger.log("Kitsu: Login failed")
                            snackString(getString(R.string.kitsu_login_failed))
                            positiveButton.isEnabled = true
                            passwordInput?.error = getString(R.string.kitsu_login_failed)
                        }
                    }
                }
            }
        }

        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        usernameInput = null
        passwordInput = null
        progressBar = null
    }
}
