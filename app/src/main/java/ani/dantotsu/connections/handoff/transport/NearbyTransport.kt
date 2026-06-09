package ani.dantotsu.connections.handoff.transport

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/** Primary transport: Google Nearby Connections (auto-negotiates Bluetooth/BLE/Wi-Fi). */
class NearbyTransport(private val context: Context) : HandoffTransport {

    override val tag = TAG

    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val localName: String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Dantotsu device" }

    private val endpoints = LinkedHashMap<String, HandoffEndpoint>()
    private var listener: TransportListener? = null
    private var sending = false
    private var pendingJson: String? = null
    private var stopped = false

    override fun startSending(listener: TransportListener) {
        this.listener = listener
        sending = true
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { listener.onError(it.localizedMessage ?: "Nearby discovery failed") }
    }

    override fun connectAndSend(endpointId: String, json: String) {
        pendingJson = json
        client.requestConnection(localName, endpointId.removePrefix("$TAG:"), connectionCallback)
            .addOnFailureListener { listener?.onError(it.localizedMessage ?: "Could not connect") }
    }

    override fun startReceiving(listener: TransportListener) {
        this.listener = listener
        sending = false
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(localName, SERVICE_ID, connectionCallback, options)
            .addOnFailureListener { listener.onError(it.localizedMessage ?: "Nearby advertising failed") }
    }

    override fun stop() {
        stopped = true
        listener = null
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAdvertising() }
        runCatching { client.stopAllEndpoints() }
        endpoints.clear()
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpoints["$TAG:$endpointId"] = HandoffEndpoint("$TAG:$endpointId", info.endpointName)
            listener?.onEndpointsChanged(TAG, endpoints.values.toList())
        }

        override fun onEndpointLost(endpointId: String) {
            endpoints.remove("$TAG:$endpointId")
            listener?.onEndpointsChanged(TAG, endpoints.values.toList())
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Handoff is an explicit user action between co-located devices, so auto-accept.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK ->
                    if (sending) pendingJson?.let {
                        client.sendPayload(endpointId, Payload.fromBytes(it.toByteArray()))
                    }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    listener?.onError("Connection rejected")

                else -> if (!stopped) listener?.onError("Connection failed")
            }
        }

        override fun onDisconnected(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            listener?.onReceived(String(bytes))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (sending && update.status == PayloadTransferUpdate.Status.SUCCESS) listener?.onSent()
        }
    }

    companion object {
        const val TAG = "nearby"
        private const val SERVICE_ID = "ani.dantotsu.handoff"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT

        /** Permissions Nearby Connections needs at runtime, by platform version. */
        fun requiredPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
                add(android.Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }
}
