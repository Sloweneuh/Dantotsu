package ani.dantotsu.connections.handoff.transport

/** A discovered receiver. [id] is prefixed with the transport tag so a manager can route back. */
data class HandoffEndpoint(val id: String, val name: String)

/** Callbacks shared by all handoff transports. All callbacks are delivered on the main thread. */
interface TransportListener {
    /** Sender only: the set of receivers visible to [transportTag] changed. */
    fun onEndpointsChanged(transportTag: String, endpoints: List<HandoffEndpoint>) {}

    /** Receiver only: a payload (JSON) arrived. */
    fun onReceived(json: String) {}

    /** Sender only: the payload was delivered. */
    fun onSent() {}

    fun onError(message: String) {}
}

/**
 * A peer-to-peer channel for handing a payload between two co-located devices, with no server
 * and no account. Two implementations exist: [NearbyTransport] (Google Nearby Connections, the
 * primary path) and [LanTransport] (mDNS/NSD + TCP socket, a Wi-Fi fallback that also works in
 * environments where Nearby is unreliable, e.g. WSA).
 */
interface HandoffTransport {
    val tag: String

    /** Begin discovering receivers (sender role). */
    fun startSending(listener: TransportListener)

    /** Connect to [endpointId] (as reported via [TransportListener.onEndpointsChanged]) and send [json]. */
    fun connectAndSend(endpointId: String, json: String)

    /** Begin advertising and waiting for an incoming payload (receiver role). */
    fun startReceiving(listener: TransportListener)

    fun stop()
}
