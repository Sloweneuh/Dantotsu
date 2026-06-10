package ani.dantotsu.connections.handoff

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.connections.handoff.transport.HandoffEndpoint
import ani.dantotsu.connections.handoff.transport.NearbyTransport
import ani.dantotsu.databinding.BottomSheetHandoffBinding
import ani.dantotsu.databinding.ItemHandoffDeviceBinding
import ani.dantotsu.snackString
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.launch

/**
 * "Continue on another device" sheet.
 *
 * [MODE_SEND]: opened with a [HandoffPayload]; discovers nearby receivers over every transport
 * and pushes the payload on tap. Also offers a QR-code fallback (the other device scans it with
 * any camera and the [dantotsu://handoff] deep link opens it).
 *
 * [MODE_RECEIVE]: informational. Receiving runs globally while the app is open (see
 * [GlobalHandoffReceiver]), so this just tells the user the device is discoverable.
 */
class HandoffBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetHandoffBinding? = null
    private val binding get() = _binding!!

    private var manager: HandoffManager? = null
    private var sending = false

    private val mode: Int get() = arguments?.getInt(ARG_MODE) ?: MODE_RECEIVE
    private val payload: HandoffPayload?
        get() = @Suppress("DEPRECATION") arguments?.getSerializable(ARG_PAYLOAD) as? HandoffPayload

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startSending()
            else {
                snackString(getString(R.string.handoff_permission_needed))
                showQrFallback() // still works without Nearby permissions
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetHandoffBinding.inflate(inflater, container, false)
        dialog?.window?.statusBarColor = Color.TRANSPARENT
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (mode == MODE_SEND) {
            binding.handoffTitle.text = getString(R.string.continue_on_another_device)
            binding.handoffSubtitle.text = getString(R.string.handoff_media_title, payload?.title ?: "")
            binding.handoffStatus.text = getString(R.string.handoff_searching)
            binding.handoffQr.visibility = View.VISIBLE
            binding.handoffQr.setOnClickListener { showQrFallback() }
            binding.handoffShareCode.visibility = View.VISIBLE
            binding.handoffShareCode.setOnClickListener { shareViaCode() }

            val missing = NearbyTransport.requiredPermissions().filter {
                ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startSending() else permissionLauncher.launch(missing.toTypedArray())
        } else {
            val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            binding.handoffTitle.text = getString(R.string.receive_from_another_device)
            binding.handoffSubtitle.text = getString(R.string.handoff_receive_hint)
            binding.handoffStatus.text = getString(R.string.handoff_discoverable_as, name)
            binding.handoffProgress.visibility = View.GONE

            binding.handoffReceiveQr.visibility = View.VISIBLE
            binding.handoffReceiveQr.setOnClickListener {
                startActivity(Intent(requireContext(), HandoffScanActivity::class.java))
                dismissAllowingStateLoss()
            }
            binding.handoffReceiveCode.visibility = View.VISIBLE
            binding.handoffReceiveCode.setOnClickListener { promptForCode() }
        }
    }

    /** Send side: upload the payload to the cloud and show the generated sharing code. */
    private fun shareViaCode() {
        val payload = payload ?: return
        snackString(getString(R.string.handoff_uploading_code))
        CloudHandoff.upload(payload) { code ->
            if (_binding == null) return@upload
            if (code == null) snackString(getString(R.string.handoff_code_upload_failed))
            else showCodeDialog(code)
        }
    }

    private fun showCodeDialog(code: String) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val view = TextView(requireContext()).apply {
            text = code
            textSize = 32f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.2f
            setPadding(pad, pad, pad, pad)
        }
        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.handoff_share_code))
            setMessage(getString(R.string.handoff_share_code_hint))
            setCustomView(view)
            setPosButton(R.string.close) {}
            show()
        }
    }

    /** Receive side: ask for a sharing code, fetch the payload, then open it on this device. */
    private fun promptForCode() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.handoff_enter_code_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.2f
            setPadding(pad, pad, pad, pad)
        }
        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.handoff_receive_via_code))
            setMessage(getString(R.string.handoff_enter_code_message))
            setCustomView(input)
            setPosButton(R.string.ok) {
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isNotEmpty()) fetchByCode(code)
            }
            setNegButton(R.string.cancel) {}
            show()
        }
    }

    private fun fetchByCode(code: String) {
        snackString(getString(R.string.handoff_fetching_code))
        val activity = activity as? AppCompatActivity
        CloudHandoff.fetch(code) { payload ->
            if (payload == null) {
                snackString(getString(R.string.handoff_code_not_found))
                return@fetch
            }
            activity?.lifecycleScope?.launch {
                HandoffNavigator.navigate(activity.applicationContext, payload)
            }
            dismissAllowingStateLoss()
        }
    }

    private fun startSending() {
        val payload = payload ?: run { dismissAllowingStateLoss(); return }
        manager = HandoffManager(requireContext().applicationContext).also {
            it.startSending(payload, sendListener)
        }
    }

    private fun showQrFallback() {
        val link = payload?.toDeepLink() ?: return
        val bitmap = HandoffQr.encode(link) ?: run {
            snackString(getString(R.string.handoff_qr_failed)); return
        }
        val image = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            adjustViewBounds = true
        }
        requireContext().customAlertDialog().apply {
            setTitle(getString(R.string.continue_on_another_device))
            setMessage(getString(R.string.handoff_qr_hint))
            setCustomView(image)
            setPosButton(R.string.close) {}
            show()
        }
    }

    private val sendListener = object : HandoffManager.Listener {
        override fun onEndpointsChanged(endpoints: List<HandoffEndpoint>) {
            if (_binding == null) return
            binding.handoffDeviceContainer.removeAllViews()
            binding.handoffStatus.text =
                getString(if (endpoints.isEmpty()) R.string.handoff_no_devices else R.string.handoff_searching)
            endpoints.forEach { endpoint ->
                val item = ItemHandoffDeviceBinding.inflate(
                    layoutInflater, binding.handoffDeviceContainer, false
                )
                item.handoffDeviceButton.text = endpoint.name
                item.handoffDeviceButton.setOnClickListener {
                    if (sending) return@setOnClickListener
                    sending = true
                    binding.handoffStatus.text = getString(R.string.handoff_sending, endpoint.name)
                    manager?.sendTo(endpoint.id)
                }
                binding.handoffDeviceContainer.addView(item.root)
            }
        }

        override fun onSent() {
            snackString(getString(R.string.handoff_sent, payload?.title ?: ""))
            dismissAllowingStateLoss()
        }

        override fun onError(message: String) {
            // Allow retrying a different device after a failed/timed-out send.
            sending = false
            if (_binding != null) binding.handoffStatus.text = message
        }
    }

    override fun onDestroyView() {
        manager?.stop()
        manager = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val MODE_SEND = 0
        const val MODE_RECEIVE = 1
        private const val ARG_MODE = "mode"
        private const val ARG_PAYLOAD = "payload"

        fun send(payload: HandoffPayload) = HandoffBottomSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_MODE, MODE_SEND)
                putSerializable(ARG_PAYLOAD, payload)
            }
        }

        fun receive() = HandoffBottomSheet().apply {
            arguments = Bundle().apply { putInt(ARG_MODE, MODE_RECEIVE) }
        }
    }
}
