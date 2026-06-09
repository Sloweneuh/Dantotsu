package ani.dantotsu.connections.handoff

import android.content.Context
import ani.dantotsu.connections.handoff.transport.HandoffEndpoint
import ani.dantotsu.connections.handoff.transport.HandoffTransport
import ani.dantotsu.connections.handoff.transport.LanTransport
import ani.dantotsu.connections.handoff.transport.NearbyTransport
import ani.dantotsu.connections.handoff.transport.TransportListener

/**
 * Runs every [HandoffTransport] at once and presents them as a single channel: discovered
 * receivers from Nearby and LAN are merged into one list, and [sendTo] routes back to whichever
 * transport found the endpoint (its id is tag-prefixed). This makes the LAN path a transparent
 * fallback for Nearby rather than a manual mode switch.
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

    private val transportListener = object : TransportListener {
        override fun onEndpointsChanged(transportTag: String, endpoints: List<HandoffEndpoint>) {
            byTransport[transportTag] = endpoints
            listener?.onEndpointsChanged(byTransport.values.flatten())
        }

        override fun onReceived(json: String) {
            HandoffPayload.fromJson(json)?.let { listener?.onReceived(it) }
                ?: listener?.onError("Received invalid data")
        }

        override fun onSent() {
            if (!sent) {
                sent = true
                listener?.onSent()
            }
        }

        override fun onError(message: String) {
            listener?.onError(message)
        }
    }

    fun startSending(payload: HandoffPayload, listener: Listener) {
        this.listener = listener
        this.payloadJson = payload.toJson()
        transports.forEach { runCatching { it.startSending(transportListener) } }
    }

    fun sendTo(endpointId: String) {
        val json = payloadJson ?: return
        val transport = transports.firstOrNull { endpointId.startsWith("${it.tag}:") } ?: return
        transport.connectAndSend(endpointId, json)
    }

    fun startReceiving(listener: Listener) {
        this.listener = listener
        transports.forEach { runCatching { it.startReceiving(transportListener) } }
    }

    fun stop() {
        listener = null
        transports.forEach { runCatching { it.stop() } }
        byTransport.clear()
    }
}
