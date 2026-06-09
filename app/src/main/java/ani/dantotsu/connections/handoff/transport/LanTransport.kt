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
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
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
                resolve(service)
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

    private fun resolve(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host: InetAddress = info.host ?: return
                val id = "$TAG:${info.serviceName}"
                resolved[id] = host.hostAddress.orEmpty() to info.port
                Logger.log("Handoff/LAN: resolved ${info.serviceName} -> ${host.hostAddress}:${info.port}")
                endpoints[id] = HandoffEndpoint(id, info.serviceName)
                main.post { listener?.onEndpointsChanged(TAG, endpoints.values.toList()) }
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.log("Handoff/LAN: resolve failed for ${info.serviceName} (code $errorCode)")
            }
        }
        runCatching { nsd.resolveService(service, resolveListener) }
    }

    override fun stop() {
        stopped = true
        listener = null
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        runCatching { serverSocket?.close() }
        registrationListener = null
        discoveryListener = null
        serverSocket = null
        endpoints.clear()
        resolved.clear()
    }

    companion object {
        const val TAG = "lan"
        private const val SERVICE_TYPE = "_dantotsuho._tcp."
        private const val CONNECT_TIMEOUT_MS = 8000
    }
}
