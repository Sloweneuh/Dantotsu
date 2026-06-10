package ani.dantotsu.connections.handoff.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.api.ApiException
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

/**
 * Primary transport: Google Nearby Connections.
 *
 * To avoid force-enabling the user's radios (a plain advertise/discover call makes GMS silently
 * switch on Bluetooth *and* Wi-Fi), this transport:
 *  - runs in [AdvertisingOptions.Builder.setLowPower] / [DiscoveryOptions.Builder.setLowPower]
 *    mode, restricting Nearby to BLE so it never touches Wi-Fi (the [LanTransport] is the
 *    high-bandwidth path when Wi-Fi is already on); and
 *  - is gated on Bluetooth already being enabled — when it's off we simply don't start Nearby
 *    rather than turning it on, leaving LAN/QR/sharing-code as the fallback.
 */
class NearbyTransport(private val context: Context) : HandoffTransport {

    override val tag = TAG

    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val displayName: String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Dantotsu device" }
    // What we advertise: our stable device id + display name, so the sender can dedupe across
    // transports and still show a friendly name. Nearby's endpoint name has ample room for this.
    private val localName: String = "${HandoffDevice.id}$NAME_SEP$displayName"

    private val endpoints = LinkedHashMap<String, HandoffEndpoint>()
    private var listener: TransportListener? = null
    private var sending = false
    private var pendingJson: String? = null
    private var stopped = false

    private val main = Handler(Looper.getMainLooper())
    private var retriedDiscovery = false
    private var retriedAdvertising = false

    override fun startSending(listener: TransportListener) {
        this.listener = listener
        sending = true
        retriedDiscovery = false
        // Don't force Bluetooth on; if it's off, let LAN/QR/sharing-code handle the send.
        if (!bluetoothEnabled()) return
        startDiscoveryInternal()
    }

    private fun startDiscoveryInternal() {
        if (stopped) return
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).setLowPower(true).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { e ->
                // A leftover discovery session (e.g. from a previous transport instance GMS still
                // holds) makes this fail with ALREADY_DISCOVERING and would silently leave us not
                // scanning. Clear it and retry once so this instance owns the live session.
                if ((e as? ApiException)?.statusCode ==
                    ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING
                ) {
                    if (!retriedDiscovery) {
                        retriedDiscovery = true
                        runCatching { client.stopDiscovery() }
                        main.postDelayed({ startDiscoveryInternal() }, RETRY_DELAY_MS)
                    }
                } else {
                    listener?.onError(e.localizedMessage ?: "Nearby discovery failed")
                }
            }
    }

    override fun connectAndSend(endpointId: String, json: String) {
        pendingJson = json
        client.requestConnection(localName, endpointId.removePrefix("$TAG:"), connectionCallback)
            .addOnFailureListener { listener?.onError(it.localizedMessage ?: "Could not connect") }
    }

    override fun startReceiving(listener: TransportListener) {
        this.listener = listener
        sending = false
        retriedAdvertising = false
        // Don't force Bluetooth on just to stay discoverable; LAN advertising still runs.
        if (!bluetoothEnabled()) return
        startAdvertisingInternal()
    }

    private fun startAdvertisingInternal() {
        if (stopped) return
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).setLowPower(true).build()
        client.startAdvertising(localName, SERVICE_ID, connectionCallback, options)
            .addOnFailureListener { e ->
                // ALREADY_ADVERTISING means a stale session is still registered in GMS (often from
                // an activity transition that recreated this transport). That old session is wired
                // to a dead callback, so we'd look discoverable but silently drop connections.
                // Clear it and retry once so incoming connections reach this instance.
                if ((e as? ApiException)?.statusCode ==
                    ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING
                ) {
                    if (!retriedAdvertising) {
                        retriedAdvertising = true
                        runCatching { client.stopAdvertising() }
                        runCatching { client.stopAllEndpoints() }
                        main.postDelayed({ startAdvertisingInternal() }, RETRY_DELAY_MS)
                    }
                } else {
                    listener?.onError(e.localizedMessage ?: "Nearby advertising failed")
                }
            }
    }

    /** Whether Bluetooth is already on. Reading adapter state needs no runtime permission. */
    private fun bluetoothEnabled(): Boolean = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.isEnabled == true
    }.getOrDefault(false)

    override fun stop() {
        stopped = true
        listener = null
        main.removeCallbacksAndMessages(null)
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAdvertising() }
        runCatching { client.stopAllEndpoints() }
        endpoints.clear()
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Advertised name is "deviceIddisplayName"; split it back out (tolerating an
            // un-stamped name from an older/other build).
            val parts = info.endpointName.split(NAME_SEP, limit = 2)
            val deviceId = if (parts.size == 2) parts[0] else null
            val name = if (parts.size == 2) parts[1] else info.endpointName
            endpoints["$TAG:$endpointId"] = HandoffEndpoint("$TAG:$endpointId", name, deviceId)
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
        // Unit Separator: delimits the device id from the display name in the advertised name;
        // it won't occur in a real device name.
        private const val NAME_SEP = '\u001f'
        private const val RETRY_DELAY_MS = 600L
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
