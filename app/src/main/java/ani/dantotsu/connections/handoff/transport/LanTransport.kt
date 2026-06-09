package ani.dantotsu.connections.handoff.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import ani.dantotsu.util.Logger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Wi-Fi/LAN fallback transport: advertises and discovers over mDNS (Android NSD) and transfers
 * the payload over a plain TCP socket. Requires both devices on the same network, but works in
 * environments where Nearby Connections is unreliable (notably WSA).
 */
class LanTransport(context: Context) : HandoffTransport {

    override val tag = TAG

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val localName: String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Dantotsu device" }

    private var listener: TransportListener? = null
    private val endpoints = LinkedHashMap<String, HandoffEndpoint>()
    private val resolved = HashMap<String, Pair<String, Int>>() // endpointId -> host:port

    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var stopped = false

    // NSD allows only one resolveService() in flight at a time; firing several concurrently fails
    // with "listener already in use", so those services are silently never resolved (and never
    // reported). Serialize resolves through this queue.
    private val resolveLock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var registrationRetried = false

    override fun startSending(listener: TransportListener) {
        this.listener = listener
        startDiscovery()
    }

    override fun connectAndSend(endpointId: String, json: String) {
        val target = resolved[endpointId] ?: run {
            Logger.log("Handoff/LAN: no resolved address for $endpointId")
            listener?.onError("Device no longer reachable"); return
        }
        Logger.log("Handoff/LAN: connecting to ${target.first}:${target.second} for $endpointId")
        thread {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.first, target.second), CONNECT_TIMEOUT_MS)
                    socket.getOutputStream().apply { write(json.toByteArray()); flush() }
                }
            }.onSuccess {
                Logger.log("Handoff/LAN: sent to ${target.first}:${target.second}")
                main.post { listener?.onSent() }
            }.onFailure {
                Logger.log("Handoff/LAN: send to ${target.first}:${target.second} failed: ${it.message}")
                main.post { listener?.onError(it.localizedMessage ?: "LAN send failed") }
            }
        }
    }

    override fun startReceiving(listener: TransportListener) {
        this.listener = listener
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: run {
            listener.onError("Could not open LAN socket"); return
        }
        serverSocket = server
        Logger.log("Handoff/LAN: listening on port ${server.localPort}")
        thread {
            while (!stopped) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Logger.log("Handoff/LAN: incoming connection from ${socket.inetAddress?.hostAddress}")
                handleClient(socket)
            }
        }
        registerService(server.localPort)
    }

    private fun handleClient(socket: Socket) {
        runCatching {
            socket.use { it.getInputStream().readBytes() }
        }.getOrNull()?.let { bytes ->
            val json = String(bytes)
            main.post { listener?.onReceived(json) }
        }
    }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = localName
            serviceType = SERVICE_TYPE
            setPort(port)
            // Stamp our stable device id (mDNS TXT record) so the sender can dedupe this receiver
            // against the same device found over Nearby.
            setAttribute(ATTR_DEVICE_ID, HandoffDevice.id)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Logger.log("Handoff/LAN: registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.log("Handoff/LAN: registration failed (code $errorCode)")
                // A stale registration from a previous instance the OS still holds fails with
                // ALREADY_ACTIVE; clear it and re-register once so the device is advertised.
                if (!stopped && !registrationRetried) {
                    registrationRetried = true
                    runCatching { nsd.unregisterService(this) }
                    main.postDelayed({ if (!stopped) registerService(port) }, REREGISTER_DELAY_MS)
                }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
    }

    private fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { listener?.onError("LAN discovery failed") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                // Ignore our own advertisement.
                if (service.serviceName == localName) return
                enqueueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val id = "$TAG:${service.serviceName}"
                endpoints.remove(id)
                resolved.remove(id)
                main.post { listener?.onEndpointsChanged(TAG, endpoints.values.toList()) }
            }
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
    }

    private fun enqueueResolve(service: NsdServiceInfo) {
        val startNow: Boolean
        synchronized(resolveLock) {
            resolveQueue.addLast(service)
            startNow = !resolving
            if (startNow) resolving = true
        }
        if (startNow) resolveNext()
    }

    private fun resolveNext() {
        val service = synchronized(resolveLock) {
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host: InetAddress? = info.host
                val deviceId = info.attributes?.get(ATTR_DEVICE_ID)?.let { String(it) }
                // A renamed copy of our own service (NSD appends a suffix on name clashes) resolves
                // back to our id — skip it.
                if (host != null && deviceId != HandoffDevice.id) {
                    val id = "$TAG:${info.serviceName}"
                    resolved[id] = host.hostAddress.orEmpty() to info.port
                    Logger.log("Handoff/LAN: resolved ${info.serviceName} -> ${host.hostAddress}:${info.port}")
                    endpoints[id] = HandoffEndpoint(id, info.serviceName, deviceId)
                    main.post { listener?.onEndpointsChanged(TAG, endpoints.values.toList()) }
                }
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.log("Handoff/LAN: resolve failed for ${info.serviceName} (code $errorCode)")
                resolveNext()
            }
        }
        runCatching { nsd.resolveService(service, resolveListener) }
            .onFailure { resolveNext() }
    }

    override fun stop() {
        stopped = true
        listener = null
        main.removeCallbacksAndMessages(null)
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        runCatching { serverSocket?.close() }
        registrationListener = null
        discoveryListener = null
        serverSocket = null
        endpoints.clear()
        resolved.clear()
        synchronized(resolveLock) {
            resolveQueue.clear()
            resolving = false
        }
    }

    companion object {
        const val TAG = "lan"
        private const val SERVICE_TYPE = "_dantotsuho._tcp."
        private const val ATTR_DEVICE_ID = "id"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val REREGISTER_DELAY_MS = 600L
    }
}
