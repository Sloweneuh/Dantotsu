package ani.dantotsu.connections.handoff

import android.content.Context
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
        // Local discovery can be turned off in settings; QR/sharing-code still work without it.
        if (!localDiscoveryEnabled()) return
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
        if (!localDiscoveryEnabled()) return
        transports.forEach { runCatching { it.startReceiving(transportListener) } }
    }

    companion object {
        /** Whether local Nearby/LAN discovery & advertising is allowed (user setting). */
        fun localDiscoveryEnabled(): Boolean =
            PrefManager.getVal(PrefName.HandoffDiscoveryEnabled)
    }

    fun stop() {
        listener = null
        sending = false
        sendQueue.clear()
        transports.forEach { runCatching { it.stop() } }
        byTransport.clear()
    }
}
