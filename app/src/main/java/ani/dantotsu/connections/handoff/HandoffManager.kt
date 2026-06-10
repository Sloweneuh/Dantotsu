package ani.dantotsu.connections.handoff

import android.content.Context
import android.os.Build
import ani.dantotsu.connections.handoff.transport.HandoffEndpoint
import ani.dantotsu.connections.handoff.transport.HandoffTransport
import ani.dantotsu.connections.handoff.transport.LanTransport
import ani.dantotsu.connections.handoff.transport.NearbyTransport
import ani.dantotsu.connections.handoff.transport.TransportListener
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * Runs every [HandoffTransport] at once and presents them as a single channel: discovered
 * receivers from Nearby and LAN are merged into one list, and [sendTo] routes back to whichever
 * transport found the endpoint (its id is tag-prefixed). This makes the LAN path a transparent
 * fallback for Nearby rather than a manual mode switch.
 *
 * A receiver advertises over every transport at once, so the same device is discovered once per
 * transport. The merged list is de-duplicated by device name; [sendTo] then tries every transport
 * that advertised that name (preferred first), so collapsing the duplicates never loses a path.
 */
class HandoffManager(context: Context) {

    interface Listener {
        fun onEndpointsChanged(endpoints: List<HandoffEndpoint>) {}
        fun onReceived(payload: HandoffPayload) {}
        fun onSent() {}
        fun onError(message: String) {}
    }

    private val appContext = context.applicationContext
    private val transports: List<HandoffTransport> =
        listOf(NearbyTransport(context), LanTransport(context))

    private val byTransport = HashMap<String, List<HandoffEndpoint>>()
    private var listener: Listener? = null
    private var payloadJson: String? = null
    private var sent = false

    // Fallback queue for the in-flight send: the remaining transports advertising the chosen
    // device, tried in turn if the preferred one's connection fails.
    private val sendQueue = ArrayDeque<HandoffEndpoint>()
    private var sending = false

    private val transportListener = object : TransportListener {
        override fun onEndpointsChanged(transportTag: String, endpoints: List<HandoffEndpoint>) {
            byTransport[transportTag] = endpoints
            listener?.onEndpointsChanged(mergedEndpoints())
        }

        override fun onReceived(json: String) {
            HandoffPayload.fromJson(json)?.let { listener?.onReceived(it) }
                ?: listener?.onError("Received invalid data")
        }

        override fun onSent() {
            sending = false
            if (!sent) {
                sent = true
                listener?.onSent()
            }
        }

        override fun onError(message: String) {
            // While a send is in flight, a failure just means try the next transport for the same
            // device rather than surfacing an error the user can't act on.
            if (sending) trySendNext(message)
            else listener?.onError(message)
        }
    }

    /** All discovered endpoints, flattened in transport-preference order. */
    private fun allEndpoints(): List<HandoffEndpoint> =
        transports.flatMap { byTransport[it.tag].orEmpty() }

    /** Dedup key: the device's stable id when advertised, else its display name. */
    private fun HandoffEndpoint.dedupKey(): String = deviceId ?: name

    /** One entry per device (the preferred transport's), so duplicates don't show. */
    private fun mergedEndpoints(): List<HandoffEndpoint> {
        val byDevice = LinkedHashMap<String, HandoffEndpoint>()
        allEndpoints().forEach { byDevice.putIfAbsent(it.dedupKey(), it) }
        return byDevice.values.toList()
    }

    fun startSending(payload: HandoffPayload, listener: Listener) {
        this.listener = listener
        this.payloadJson = payload.toJson()
        sent = false
        sending = false
        sendQueue.clear()
        // Off in settings, or an unsupported (WSA/emulator) device; QR/sharing-code still work.
        if (!localDiscoveryActive(appContext)) return
        transports.forEach { runCatching { it.startSending(transportListener) } }
    }

    fun sendTo(endpointId: String) {
        if (payloadJson == null) return
        // Send to every transport advertising this device (the tapped entry is just one of them),
        // preferred first, falling back on failure.
        val tapped = allEndpoints().firstOrNull { it.id == endpointId }
        val candidates =
            if (tapped != null) allEndpoints().filter { it.dedupKey() == tapped.dedupKey() }
            else allEndpoints().filter { it.id == endpointId }
        sendQueue.clear()
        sendQueue.addAll(candidates)
        sending = true
        trySendNext(null)
    }

    private fun trySendNext(lastError: String?) {
        val json = payloadJson ?: return
        val endpoint = sendQueue.removeFirstOrNull() ?: run {
            sending = false
            listener?.onError(lastError ?: "Couldn't reach device")
            return
        }
        val transport = transports.firstOrNull { endpoint.id.startsWith("${it.tag}:") }
            ?: return trySendNext(lastError)
        transport.connectAndSend(endpoint.id, json)
    }

    fun startReceiving(listener: Listener) {
        this.listener = listener
        if (!localDiscoveryActive(appContext)) return
        transports.forEach { runCatching { it.startReceiving(transportListener) } }
    }

    companion object {
        /** Whether local Nearby/LAN discovery & advertising is allowed (user setting). */
        fun localDiscoveryEnabled(): Boolean =
            PrefManager.getVal(PrefName.HandoffDiscoveryEnabled)

        @Volatile private var virtualDevice: Boolean? = null

        // WSA host packages; present even when the build spoofs its props (declared in <queries>).
        private val WSA_PACKAGES = listOf(
            "com.microsoft.windows.userexperiencehost",
            "com.microsoft.windows.systemapp",
        )

        /**
         * WSA and emulators can't use Nearby/LAN reliably (no real Bluetooth, isolated networking),
         * so local discovery is suppressed there and only the QR/sharing-code paths are offered.
         *
         * Detection combines [Build] heuristics with a check for the WSA host packages — the latter
         * catches community/Magisk WSA builds that spoof their props to look like a real phone.
         * Strong signals only, to avoid false-positiving custom ROMs. Cached (device is constant).
         */
        fun isVirtualDevice(context: Context): Boolean {
            virtualDevice?.let { return it }
            return computeVirtualDevice(context.applicationContext).also { virtualDevice = it }
        }

        private fun computeVirtualDevice(context: Context): Boolean {
            val props = listOf(
                Build.FINGERPRINT, Build.MODEL, Build.MANUFACTURER, Build.BRAND,
                Build.DEVICE, Build.PRODUCT, Build.HARDWARE, Build.BOARD
            ).joinToString(" ").lowercase()
            val wsa = props.contains("subsystem for android") || props.contains("windows") ||
                props.contains("wsa") || Build.MANUFACTURER.equals("Microsoft Corporation", true) ||
                WSA_PACKAGES.any {
                    runCatching { context.packageManager.getPackageInfo(it, 0); true }
                        .getOrDefault(false)
                }
            val emulator = props.contains("emulator") || props.contains("goldfish") ||
                props.contains("ranchu") || props.contains("vbox86") ||
                props.contains("sdk_gphone") || props.contains("genymotion") ||
                props.contains("google_sdk") || props.contains("android sdk built for") ||
                Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.startsWith("unknown") ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
            return wsa || emulator
        }

        /** Local discovery runs only when the user enabled it AND the device can support it. */
        fun localDiscoveryActive(context: Context): Boolean =
            localDiscoveryEnabled() && !isVirtualDevice(context)
    }

    fun stop() {
        listener = null
        sending = false
        sendQueue.clear()
        transports.forEach { runCatching { it.stop() } }
        byTransport.clear()
    }
}
