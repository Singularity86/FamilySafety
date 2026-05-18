package com.example.familysafety.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Peer-to-peer transport over local WiFi using NSD (mDNS) for discovery and TCP for delivery.
 *
 * When all devices are on the same network, messages are routed device-to-device
 * without touching the internet broker. MQTT is used as fallback when a peer is
 * not locally reachable.
 *
 * Flow:
 *   1. [start] registers an NSD service so peers can find this device.
 *   2. [start] begins NSD discovery to find other family members on the same WiFi.
 *   3. When a peer is resolved, their address is cached in [peerAddresses].
 *   4. [isReachable] returns true if a peer has been resolved.
 *   5. [send] delivers an encrypted payload to a peer via a persistent TCP connection.
 *   6. Incoming messages are forwarded to [onMessageReceived].
 */
@Singleton
class LocalTransport @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val SERVICE_TYPE = "_familysafety._tcp."
        // Max 1 MB per message (safety guard against malformed frames)
        private const val MAX_MESSAGE_BYTES = 1_048_576
    }

    @Serializable
    private data class LocalMessage(
        val topic: String,
        val senderId: String,
        val payloadBase64: String
    )

    private data class ResolveRequest(
        val peerPrefix: String,
        val serviceInfo: NsdServiceInfo
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Peers discovered via NSD — keyed by the first 16 chars of their memberId
    private val peerAddresses = ConcurrentHashMap<String, InetSocketAddress>()
    private val resolveQueue = ConcurrentLinkedQueue<ResolveRequest>()
    private val isResolving = AtomicBoolean(false)

    // Outbound TCP connections — keyed by 16-char prefix
    private val connections = ConcurrentHashMap<String, Socket>()

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    private var localMemberId: String? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Invoked on the IO dispatcher when a framed message arrives from a peer. */
    var onMessageReceived: ((topic: String, payloadString: String) -> Unit)? = null

    /** Invoked when NSD resolves or loses a local peer, keyed by memberId prefix. */
    var onPeerReachabilityChanged: ((peerPrefix: String, reachable: Boolean, address: InetSocketAddress?) -> Unit)? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    fun start(memberId: String) {
        if (localMemberId != null) return
        localMemberId = memberId
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        startServer()
    }

    fun stop() {
        runCatching { registrationListener?.let { nsdManager?.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } }
        peerAddresses.clear()
        resolveQueue.clear()
        isResolving.set(false)
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        serverSocket?.close()
        serverSocket = null
        scope.cancel()
        localMemberId = null
        Timber.i("LocalTransport stopped")
    }

    // =========================================================================
    // TCP SERVER
    // =========================================================================

    private fun startServer() {
        scope.launch {
            try {
                val server = ServerSocket(0) // let the OS pick an available port
                serverSocket = server
                serverPort = server.localPort
                Timber.i("LocalTransport listening on port $serverPort")

                registerNsdService()
                startNsdDiscovery()

                while (!server.isClosed) {
                    try {
                        val socket = withContext(Dispatchers.IO) { server.accept() }
                        launch { handleInboundConnection(socket) }
                    } catch (e: Exception) {
                        if (!server.isClosed) Timber.e(e, "TCP accept error")
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "LocalTransport server failed to start")
            }
        }
    }

    private suspend fun handleInboundConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = DataInputStream(socket.getInputStream())
            while (!socket.isClosed) {
                val len = try { input.readInt() } catch (e: Exception) { break }
                if (len <= 0 || len > MAX_MESSAGE_BYTES) {
                    Timber.w("Invalid frame size $len from peer — closing connection")
                    break
                }
                val bytes = ByteArray(len)
                try { input.readFully(bytes) } catch (e: Exception) { break }

                val msg = try {
                    json.decodeFromString<LocalMessage>(String(bytes))
                } catch (e: Exception) {
                    Timber.w("Failed to parse local message frame")
                    null
                } ?: continue // skip malformed frame, keep connection alive

                val payloadString = try {
                    String(Base64.getDecoder().decode(msg.payloadBase64))
                } catch (e: Exception) {
                    Timber.w("Failed to decode base64 payload")
                    null
                } ?: continue

                Timber.d("LocalTransport received ${msg.topic} from ${msg.senderId.take(8)}")
                onMessageReceived?.invoke(msg.topic, payloadString)
            }
        } catch (e: Exception) {
            Timber.d("Inbound connection closed: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // =========================================================================
    // PUBLISH
    // =========================================================================

    /**
     * Returns true if [memberId] has been discovered on the local network.
     */
    fun isReachable(memberId: String): Boolean =
        peerAddresses.containsKey(memberId.take(16))

    /**
     * Send an encrypted message to [recipientMemberId] over local WiFi.
     * Returns true if the message was delivered successfully; false if the peer is
     * unreachable or the send fails (caller should fall back to MQTT).
     */
    suspend fun send(topic: String, payloadString: String, recipientMemberId: String): Boolean {
        val prefix = recipientMemberId.take(16)
        val address = peerAddresses[prefix] ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val socket = connections[prefix]?.takeUnless { it.isClosed }
                    ?: Socket().apply { connect(address, 3_000) }.also { connections[prefix] = it }

                val senderId = localMemberId ?: return@withContext false
                val msg = LocalMessage(
                    topic = topic,
                    senderId = senderId,
                    payloadBase64 = Base64.getEncoder()
                        .encodeToString(payloadString.toByteArray())
                )
                val bytes = json.encodeToString(msg).toByteArray()
                val out = DataOutputStream(socket.getOutputStream())
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
                Timber.d("LocalTransport sent to ${prefix.take(8)} on $topic")
                true
            } catch (e: Exception) {
                Timber.d("LocalTransport send to $prefix failed (${e.message}), MQTT fallback")
                connections.remove(prefix)?.let { runCatching { it.close() } }
                peerAddresses.remove(prefix) // remove stale entry so we re-resolve next time
                onPeerReachabilityChanged?.invoke(prefix, false, null)
                false
            }
        }
    }

    // =========================================================================
    // NSD REGISTRATION
    // =========================================================================

    private fun registerNsdService() {
        val memberId = localMemberId ?: return
        // Encode the first 16 chars of memberId in the service name for peer identification
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "familysafety-${memberId.take(16)}"
            serviceType = SERVICE_TYPE
            port = serverPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Timber.i("NSD registered: ${info.serviceName} on port $serverPort")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Timber.d("NSD service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    // =========================================================================
    // NSD DISCOVERY
    // =========================================================================

    private fun startNsdDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.d("NSD discovery started for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Timber.d("NSD discovery stopped")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith("familysafety-")) return
                val peerPrefix = serviceInfo.serviceName.removePrefix("familysafety-")
                if (peerPrefix == localMemberId?.take(16)) return // skip ourselves
                Timber.d("NSD found peer: $peerPrefix, queueing resolve...")
                enqueueResolve(peerPrefix, serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith("familysafety-")) return
                val peerPrefix = serviceInfo.serviceName.removePrefix("familysafety-")
                peerAddresses.remove(peerPrefix)
                connections.remove(peerPrefix)?.let { runCatching { it.close() } }
                onPeerReachabilityChanged?.invoke(peerPrefix, false, null)
                Timber.d("Peer lost: $peerPrefix")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("NSD discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("NSD discovery stop failed: $errorCode")
            }
        }
        discoveryListener = listener
        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun enqueueResolve(peerPrefix: String, serviceInfo: NsdServiceInfo) {
        if (peerAddresses.containsKey(peerPrefix)) return
        if (resolveQueue.any { it.peerPrefix == peerPrefix }) return
        resolveQueue.offer(ResolveRequest(peerPrefix, serviceInfo))
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) return
        val request = resolveQueue.poll() ?: run {
            isResolving.set(false)
            return
        }
        try {
            nsdManager?.resolveService(request.serviceInfo, makeResolveListener(request.peerPrefix))
                ?: completeResolve()
        } catch (e: Exception) {
            Timber.w(e, "NSD resolve could not start for ${request.peerPrefix}")
            completeResolve()
        }
    }

    private fun completeResolve() {
        isResolving.set(false)
        drainResolveQueue()
    }

    private fun makeResolveListener(peerPrefix: String): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: run {
                    completeResolve()
                    return
                }
                val port = serviceInfo.port
                val address = InetSocketAddress(host, port)
                peerAddresses[peerPrefix] = address
                onPeerReachabilityChanged?.invoke(peerPrefix, true, address)
                Timber.i("Peer resolved: $peerPrefix @ $host:$port")
                completeResolve()
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD resolve failed for $peerPrefix: $errorCode")
                completeResolve()
            }
        }
    }
}
