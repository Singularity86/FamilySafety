package com.example.familysafety.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.DataInputStream
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
 *
 * Staying found. NSD reports a service once, when it appears, and again only after it has
 * been lost. A peer whose address is dropped — because a send failed while it dozed, or the
 * WiFi blipped — is therefore never reported again, so the service each peer advertised is
 * kept in [discoveredServices] and re-resolved after a failure, with backoff. Registration
 * and discovery are retried when they fail, and restarted when WiFi comes back.
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

    // Recreated by start(): stop() cancels it, and a cancelled scope silently ignores every
    // launch, so a stop followed by a start used to leave no server and no discovery.
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Peers discovered via NSD — keyed by the first 16 chars of their memberId
    private val peerAddresses = ConcurrentHashMap<String, InetSocketAddress>()
    // Every service NSD has reported and not yet lost, resolved or not — what a re-resolve needs.
    private val discoveredServices = ConcurrentHashMap<String, NsdServiceInfo>()
    // Consecutive failures per peer, for re-resolve backoff. Cleared when a peer resolves.
    private val peerFailures = ConcurrentHashMap<String, Int>()
    private val reResolveJobs = ConcurrentHashMap<String, Job>()
    private val resolveQueue = ConcurrentLinkedQueue<ResolveRequest>()
    private val isResolving = AtomicBoolean(false)

    // Outbound TCP connections — keyed by 16-char prefix
    private val connections = ConcurrentHashMap<String, Socket>()
    private val sendLocks = ConcurrentHashMap<String, Mutex>()

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    private var localMemberId: String? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationFailures = 0
    private var discoveryFailures = 0
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

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
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        registrationFailures = 0
        discoveryFailures = 0
        startServer()
        watchWifi()
    }

    fun stop() {
        unwatchWifi()
        runCatching { registrationListener?.let { nsdManager?.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        peerAddresses.clear()
        discoveredServices.clear()
        peerFailures.clear()
        reResolveJobs.clear()
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

                // A non-positive length, or one wildly beyond the cap, means the stream is
                // desynchronised — there is no reliable resynchronisation point, so close.
                if (len <= 0 || len > MAX_MESSAGE_BYTES * 16) {
                    Timber.w("Desynchronised frame size $len from peer — closing connection")
                    break
                }

                // An oversized but plausible frame is a real message we cannot accept — most
                // likely a manifest that has outgrown the cap. The length prefix says exactly
                // how many bytes it occupies, so skip it and keep the connection. Closing
                // here, which is what this used to do, discarded every *subsequent* message
                // on the socket too: one oversized manifest silently killed LAN delivery for
                // chat, location and file chunks until the peer reconnected.
                if (len > MAX_MESSAGE_BYTES) {
                    Timber.w("Oversized frame ($len bytes) from peer — skipping, keeping connection")
                    var remaining = len
                    val skipped = try {
                        while (remaining > 0) {
                            val n = input.skipBytes(remaining)
                            if (n <= 0) break
                            remaining -= n
                        }
                        remaining == 0
                    } catch (e: Exception) {
                        false
                    }
                    if (!skipped) break
                    continue
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
        peerAddresses.containsKey(memberId.take(LanPeerPolicy.PEER_PREFIX_LENGTH))

    /**
     * Send an encrypted message to [recipientMemberId] over local WiFi.
     * Returns true if the message was delivered successfully; false if the peer is
     * unreachable or the send fails (caller should fall back to MQTT).
     */
    suspend fun send(topic: String, payloadString: String, recipientMemberId: String): Boolean {
        val prefix = recipientMemberId.take(LanPeerPolicy.PEER_PREFIX_LENGTH)
        val address = peerAddresses[prefix] ?: return false
        // One sender per peer at a time. Two concurrent sends could interleave on the shared
        // socket — one frame's length header followed by another frame's body — and the
        // receiver would read garbage until the connection was dropped.
        val lock = sendLocks.getOrPut(prefix) { Mutex() }
        return withContext(Dispatchers.IO) { lock.withLock {
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

                // The receiver enforces this cap; the sender never did. Writing a frame the
                // peer will discard wastes the round trip and, worse, reports success — the
                // caller then skips the MQTT fallback and the message is simply lost.
                // Returning false here routes it over the relay instead.
                if (bytes.size > MAX_MESSAGE_BYTES) {
                    Timber.w(
                        "LocalTransport frame for $topic is ${bytes.size} bytes, over the " +
                            "${MAX_MESSAGE_BYTES} cap — falling back to MQTT"
                    )
                    return@withContext false
                }

                // Header and body in one write, so the peer never sees a header alone.
                val frame = java.nio.ByteBuffer.allocate(4 + bytes.size)
                    .putInt(bytes.size)
                    .put(bytes)
                    .array()
                val out = socket.getOutputStream()
                out.write(frame)
                out.flush()
                Timber.d("LocalTransport sent to ${prefix.take(8)} on $topic")
                true
            } catch (e: Exception) {
                Timber.d("LocalTransport send to $prefix failed (${e.message}), MQTT fallback")
                connections.remove(prefix)?.let { runCatching { it.close() } }
                // The address may be stale (new DHCP lease, new port after an app restart), so
                // drop it — but schedule a re-resolve. Dropping it was all this used to do, and
                // NSD never reports a still-present service again, so one failed send while the
                // peer dozed lost it for good: "0 local peers" on a shared WiFi.
                peerAddresses.remove(prefix)
                onPeerReachabilityChanged?.invoke(prefix, false, null)
                scheduleReResolve(prefix)
                false
            }
        } }
    }

    // =========================================================================
    // NSD REGISTRATION
    // =========================================================================

    private fun registerNsdService() {
        val memberId = localMemberId ?: return
        // Encode the first 16 chars of memberId in the service name for peer identification
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = LanPeerPolicy.SERVICE_NAME_PREFIX + memberId.take(LanPeerPolicy.PEER_PREFIX_LENGTH)
            serviceType = SERVICE_TYPE
            port = serverPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registrationFailures = 0
                Timber.i("NSD registered: ${info.serviceName} on port $serverPort")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                val delayMs = LanPeerPolicy.retryDelayMs(registrationFailures++)
                Timber.e("NSD registration failed: $errorCode — retrying in ${delayMs / 1000}s")
                if (registrationListener === this) registrationListener = null
                scope.launch {
                    delay(delayMs)
                    if (localMemberId != null && registrationListener == null) registerNsdService()
                }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Timber.d("NSD service unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Timber.w(e, "NSD registerService threw")
            listener.onRegistrationFailed(serviceInfo, -1)
        }
    }

    // =========================================================================
    // NSD DISCOVERY
    // =========================================================================

    private fun startNsdDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryFailures = 0
                Timber.d("NSD discovery started for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Timber.d("NSD discovery stopped")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val peerPrefix = LanPeerPolicy.peerPrefixFromServiceName(serviceInfo.serviceName) ?: return
                if (peerPrefix == localMemberId?.take(LanPeerPolicy.PEER_PREFIX_LENGTH)) return // skip ourselves
                discoveredServices[peerPrefix] = serviceInfo
                Timber.d("NSD found peer: $peerPrefix, queueing resolve...")
                enqueueResolve(peerPrefix, serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val peerPrefix = LanPeerPolicy.peerPrefixFromServiceName(serviceInfo.serviceName) ?: return
                // A renamed duplicate ("… (2)") can be lost while the peer's live registration
                // stays up; only forget the peer when the service we are tracking is the one lost.
                val tracked = discoveredServices[peerPrefix]
                if (tracked != null && tracked.serviceName != serviceInfo.serviceName) return
                discoveredServices.remove(peerPrefix)
                reResolveJobs.remove(peerPrefix)?.cancel()
                peerFailures.remove(peerPrefix)
                peerAddresses.remove(peerPrefix)
                connections.remove(peerPrefix)?.let { runCatching { it.close() } }
                onPeerReachabilityChanged?.invoke(peerPrefix, false, null)
                Timber.d("Peer lost: $peerPrefix")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                val delayMs = LanPeerPolicy.retryDelayMs(discoveryFailures++)
                Timber.e("NSD discovery start failed: $errorCode — retrying in ${delayMs / 1000}s")
                if (discoveryListener === this) discoveryListener = null
                scope.launch {
                    delay(delayMs)
                    if (localMemberId != null && discoveryListener == null) startNsdDiscovery()
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("NSD discovery stop failed: $errorCode")
            }
        }
        discoveryListener = listener
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Timber.w(e, "NSD discoverServices threw")
            listener.onStartDiscoveryFailed(SERVICE_TYPE, -1)
        }
    }

    private fun enqueueResolve(peerPrefix: String, serviceInfo: NsdServiceInfo) {
        if (peerAddresses.containsKey(peerPrefix)) return
        if (resolveQueue.any { it.peerPrefix == peerPrefix }) return
        resolveQueue.offer(ResolveRequest(peerPrefix, serviceInfo))
        drainResolveQueue()
    }

    /**
     * Re-resolve a known peer after a failure, waiting longer each consecutive time so a phone
     * that is genuinely away is not hammered. Cancelled if NSD reports the peer lost.
     */
    private fun scheduleReResolve(peerPrefix: String) {
        if (reResolveJobs[peerPrefix]?.isActive == true) return
        val attempt = peerFailures.merge(peerPrefix, 1, Int::plus)!! - 1
        val delayMs = LanPeerPolicy.retryDelayMs(attempt)
        reResolveJobs[peerPrefix] = scope.launch {
            delay(delayMs)
            reResolveJobs.remove(peerPrefix)
            val info = discoveredServices[peerPrefix] ?: return@launch
            Timber.d("Re-resolving LAN peer $peerPrefix (attempt ${attempt + 1})")
            enqueueResolve(peerPrefix, info)
        }
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
            scheduleReResolve(request.peerPrefix)
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
                    scheduleReResolve(peerPrefix)
                    completeResolve()
                    return
                }
                val port = serviceInfo.port
                val address = InetSocketAddress(host, port)
                peerAddresses[peerPrefix] = address
                peerFailures.remove(peerPrefix)
                onPeerReachabilityChanged?.invoke(peerPrefix, true, address)
                Timber.i("Peer resolved: $peerPrefix @ $host:$port")
                completeResolve()
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD resolve failed for $peerPrefix: $errorCode")
                scheduleReResolve(peerPrefix)
                completeResolve()
            }
        }
    }

    // =========================================================================
    // WIFI CHANGES
    // =========================================================================

    /**
     * Restart registration and discovery when WiFi comes back. A registration made while the
     * phone was on mobile data, or on a WiFi network it has since left, is not reliably carried
     * over to the new network, and neither is discovery, so peers on the new network never see
     * or are seen by this phone until the app restarts.
     */
    private fun watchWifi() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        var wifiWasLost = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The first onAvailable just reports the WiFi already connected at start.
                if (!wifiWasLost) return
                wifiWasLost = false
                scope.launch { restartNsd("WiFi reconnected") }
            }
            override fun onLost(network: Network) {
                wifiWasLost = true
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            wifiCallback = callback
        } catch (e: Exception) {
            Timber.w(e, "Could not watch WiFi for LAN restarts")
        }
    }

    private fun unwatchWifi() {
        val callback = wifiCallback ?: return
        wifiCallback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private fun restartNsd(reason: String) {
        if (localMemberId == null || serverSocket?.isClosed != false) return
        Timber.i("Restarting LAN discovery: $reason")
        runCatching { registrationListener?.let { nsdManager?.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        // Addresses from the old network are meaningless on the new one. Peers re-announce
        // themselves to the fresh discovery below.
        peerAddresses.keys.toList().forEach { prefix ->
            peerAddresses.remove(prefix)
            onPeerReachabilityChanged?.invoke(prefix, false, null)
        }
        discoveredServices.clear()
        reResolveJobs.values.forEach { it.cancel() }
        reResolveJobs.clear()
        peerFailures.clear()
        resolveQueue.clear()
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        registrationFailures = 0
        discoveryFailures = 0
        registerNsdService()
        startNsdDiscovery()
    }
}
