package ani.dantotsu.connections.mangaupdates

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

class MangaUpdatesLoginDialog : DialogFragment() {
    private var onLoginSuccess: (() -> Unit)? = null
    private var usernameInput: TextInputEditText? = null
    private var passwordInput: TextInputEditText? = null
    private var progressBar: ProgressBar? = null

    fun setOnLoginSuccessListener(listener: () -> Unit) {
        onLoginSuccess = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_login_mangaupdates, null)
        usernameInput = view.findViewById(R.id.mangaupdatesUsername)
        passwordInput = view.findViewById(R.id.mangaupdatesPassword)
        progressBar = view.findViewById(R.id.mangaupdatesLoginProgress)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.MyPopup)
            .setTitle("Login to MangaUpdates")
            .setView(view)
            .setPositiveButton("Login", null)
            .setNegativeButton("Cancel") { _, _ -> dismiss() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val username = usernameInput?.text?.toString()?.trim()
                val password = passwordInput?.text?.toString()?.trim()

                if (username.isNullOrBlank()) {
                    usernameInput?.error = "Username required"
                    return@setOnClickListener
                }

                if (password.isNullOrBlank()) {
                    passwordInput?.error = "Password required"
                    return@setOnClickListener
                }

                // Disable button and show progress
                positiveButton.isEnabled = false
                progressBar?.visibility = View.VISIBLE

                lifecycleScope.launch(Dispatchers.IO) {
                    val success = MangaUpdates.login(username, password)

                    withContext(Dispatchers.Main) {
                        progressBar?.visibility = View.GONE

                        if (success) {
                            Logger.log("MangaUpdates: Login successful")
                            snackString("Successfully logged into MangaUpdates")
                            onLoginSuccess?.invoke()
                            dismiss()
                        } else {
                            Logger.log("MangaUpdates: Login failed")
                            snackString("Login failed. Check your credentials.")
                            positiveButton.isEnabled = true
                            passwordInput?.error = "Invalid credentials"
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

