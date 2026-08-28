package ani.dantotsu.connections.simkl

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.snackString
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Simkl OAuth PIN flow: request a code, show it, let the user approve it at `simkl.com/pin`, and
 * poll until Simkl hands back an access token.
 */
class SimklLoginDialog : DialogFragment() {
    private var onLoginSuccess: (() -> Unit)? = null
    private var codeView: TextView? = null
    private var statusView: TextView? = null
    private var progress: ProgressBar? = null

    fun setOnLoginSuccessListener(listener: () -> Unit) {
        onLoginSuccess = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_login_simkl, null)
        codeView = view.findViewById(R.id.simklPinCode)
        statusView = view.findViewById(R.id.simklPinStatus)
        progress = view.findViewById(R.id.simklPinProgress)
        view.findViewById<View>(R.id.simklOpenPin)?.setOnClickListener {
            openLinkInBrowser("${ani.dantotsu.connections.simkl.Simkl.WEB_URL}/pin")
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.MyPopup)
            .setTitle(getString(R.string.simkl_pin_title))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> dismiss() }
            .create()

        if (!Simkl.isConfigured()) {
            codeView?.text = "—"
            statusView?.text = getString(R.string.simkl_not_configured)
            return dialog
        }

        startPinFlow()
        return dialog
    }

    private fun startPinFlow() = lifecycleScope.launch(Dispatchers.IO) {
        val pin = tryWithSuspend {
            client.get(
                "${Simkl.API_URL}/oauth/pin?client_id=${Simkl.CLIENT_ID}",
                mapOf("simkl-api-key" to Simkl.CLIENT_ID),
            ).parsed<PinResponse>()
        }
        if (pin?.userCode == null) {
            withContext(Dispatchers.Main) {
                statusView?.text = getString(R.string.simkl_login_failed)
                progress?.visibility = View.GONE
            }
            return@launch
        }
        withContext(Dispatchers.Main) {
            codeView?.text = pin.userCode
            statusView?.text = getString(R.string.simkl_pin_waiting)
        }

        val interval = (pin.interval ?: 5).coerceIn(2, 30)
        val deadline = System.currentTimeMillis() + (pin.expiresIn ?: 900) * 1000L
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val poll = tryWithSuspend {
                client.get(
                    "${Simkl.API_URL}/oauth/pin/${pin.userCode}?client_id=${Simkl.CLIENT_ID}",
                    mapOf("simkl-api-key" to Simkl.CLIENT_ID),
                    cacheTime = 0,
                ).parsed<PollResponse>()
            }
            if (poll?.result == "OK" && !poll.accessToken.isNullOrBlank()) {
                val ok = Simkl.onToken(poll.accessToken)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Logger.log("Simkl: Login successful")
                        snackString(getString(R.string.simkl_login_success))
                        onLoginSuccess?.invoke()
                    } else {
                        snackString(getString(R.string.simkl_login_failed))
                    }
                    dismiss()
                }
                return@launch
            }
        }
        withContext(Dispatchers.Main) {
            statusView?.text = getString(R.string.simkl_login_failed)
            progress?.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        codeView = null
        statusView = null
        progress = null
    }

    @Serializable
    private data class PinResponse(
        val result: String? = null,
        @kotlinx.serialization.SerialName("user_code") val userCode: String? = null,
        @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long? = null,
        val interval: Long? = null,
    )

    @Serializable
    private data class PollResponse(
        val result: String? = null,
        @kotlinx.serialization.SerialName("access_token") val accessToken: String? = null,
    )
}
