package com.example.familysafety.transport

import com.example.familysafety.core.hexToBytes
import com.example.familysafety.core.toHex
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupKeys
import com.example.familysafety.group.GroupStateManager
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Peer-to-peer transport over local WiFi using NSD (mDNS) for discovery and TCP for delivery.
 *
 * When family members share a network, messages go device to device without the internet
 * broker, which is what keeps the family working with no internet at all. MQTT is the fallback
 * when a peer isn't reachable here, or a LAN send isn't acknowledged.
 *
 * Every connection is an authenticated, encrypted session with one roster member ([LanProtocol],
 * remediation 4.1). Before, anyone on the network could push frames under any topic, the service
 * name carried half the member ID, and a device advertising a member's name got their traffic.
 *
 * Flow:
 *   1. [start] registers this phone under today's name for it ([LanProtocol.serviceName]) and
 *      discovers the family's; the name changes at each UTC midnight.
 *   2. A resolved peer's address is cached; [isReachable] reports it.
 *   3. [send] opens (or reuses) a session as client, sends one frame and waits for its ack.
 *   4. Inbound sessions deliver frames for our inboxes and family topics to [onMessageReceived].
 *
 * Staying found. NSD reports a service once, when it appears, and again only after it has
 * been lost, so the service each peer advertised is kept in [discoveredServices] and re-resolved
 * after a failure, with backoff. Registration and discovery are retried when they fail, and
 * restarted when WiFi comes back or the day changes.
 */
@Singleton
class LocalTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val groupStateManager: GroupStateManager,
    private val e2eeManager: E2EEManager
) {
    companion object {
        private const val SERVICE_TYPE = "_familysafety._tcp."
        // Max 1 MB per message (safety guard against malformed frames)
        private const val MAX_MESSAGE_BYTES = 1_048_576
        // Handshake frames are small; anything bigger before authentication is hostile.
        private const val MAX_HANDSHAKE_BYTES = 4_096
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val ACK_TIMEOUT_MS = 5_000
        // A family is at most a dozen phones; more open connections than this is a flood.
        private const val MAX_INBOUND_CONNECTIONS = 16
    }

    private data class ResolveRequest(
        val memberId: String,
        val serviceInfo: NsdServiceInfo
    )

    /** An outbound session: the socket, its cipher, and the id of the next frame. */
    private class Outbound(val socket: Socket, val session: LanHandshake.Session) {
        val input = DataInputStream(socket.getInputStream())
        val output: OutputStream = socket.getOutputStream()
        var nextId = 1L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    // Recreated by start(): stop() cancels it, and a cancelled scope silently ignores every
    // launch, so a stop followed by a start used to leave no server and no discovery.
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Peers resolved via NSD, keyed by member ID
    private val peerAddresses = ConcurrentHashMap<String, InetSocketAddress>()
    // Every service NSD has reported and not yet lost, resolved or not — what a re-resolve needs.
    private val discoveredServices = ConcurrentHashMap<String, NsdServiceInfo>()
    // Consecutive failures per peer, for re-resolve backoff. Cleared when a peer resolves.
    private val peerFailures = ConcurrentHashMap<String, Int>()
    private val reResolveJobs = ConcurrentHashMap<String, Job>()
    private val resolveQueue = ConcurrentLinkedQueue<ResolveRequest>()
    private val isResolving = AtomicBoolean(false)

    // Outbound sessions, keyed by member ID
    private val connections = ConcurrentHashMap<String, Outbound>()
    private val sendLocks = ConcurrentHashMap<String, Mutex>()
    private val inboundCount = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var serverPort = 0
    private var localMemberId: String? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationFailures = 0
    private var discoveryFailures = 0
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var dayJob: Job? = null

    /**
     * Invoked on the IO dispatcher for each authenticated frame: [senderId] is the member the
     * session proved to be, not anything the frame claims.
     */
    var onMessageReceived: ((topic: String, payloadString: String, senderId: String) -> Unit)? = null

    /** Invoked when NSD resolves or loses a family member's phone. */
    var onPeerReachabilityChanged: ((memberId: String, reachable: Boolean, address: InetSocketAddress?) -> Unit)? = null

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
        scheduleDayChange()
    }

    fun stop() {
        unwatchWifi()
        dayJob?.cancel()
        dayJob = null
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
        connections.values.forEach { runCatching { it.socket.close() } }
        connections.clear()
        serverSocket?.close()
        serverSocket = null
        scope.cancel()
        localMemberId = null
        Timber.i("LocalTransport stopped")
    }

    // =========================================================================
    // FAMILY STATE
    // =========================================================================

    private fun family(): GroupDefinition? = groupStateManager.groupDefinition.value

    /** Every family key, for recognising names; empty for a family created before group keys existed. */
    private fun familyKeys(def: GroupDefinition): List<String> = GroupKeys.ring(def).map { it.key }

    private fun handshake(def: GroupDefinition, me: String) = LanHandshake(
        myMemberId = me,
        groupId = def.groupId,
        sharedSecretWith = { id ->
            if (id == me) null
            else family()?.findMemberById(id)?.let { member ->
                runCatching { e2eeManager.sharedSecretWith(member.memberId, member.x25519PublicKey.hexToBytes()) }.getOrNull()
            }
        },
        randomNonce = { ByteArray(32).also(random::nextBytes).toHex() }
    )

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
                acceptLoop(server)
            } catch (e: Exception) {
                Timber.e(e, "LocalTransport server failed to start")
            }
        }
    }

    /**
     * Tests: listen on loopback without NSD, Wi-Fi or day-change hooks, and return the port.
     * Peers are then pointed at it with [setPeerAddressForTest].
     */
    @VisibleForTesting
    internal fun listenForTest(memberId: String): Int {
        localMemberId = memberId
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        serverSocket = server
        serverPort = server.localPort
        scope.launch { acceptLoop(server) }
        return serverPort
    }

    @VisibleForTesting
    internal fun setPeerAddressForTest(memberId: String, address: InetSocketAddress) {
        peerAddresses[memberId] = address
    }

    private suspend fun acceptLoop(server: ServerSocket) = coroutineScope {
        while (!server.isClosed) {
            try {
                val socket = withContext(Dispatchers.IO) { server.accept() }
                if (inboundCount.incrementAndGet() > MAX_INBOUND_CONNECTIONS) {
                    inboundCount.decrementAndGet()
                    Timber.w("Too many LAN connections — refusing one")
                    runCatching { socket.close() }
                    continue
                }
                launch {
                    try { handleInboundConnection(socket) } finally { inboundCount.decrementAndGet() }
                }
            } catch (e: Exception) {
                if (!server.isClosed) Timber.e(e, "TCP accept error")
                break
            }
        }
    }

    private suspend fun handleInboundConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val me = localMemberId ?: return@withContext
            val def = family() ?: return@withContext
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // Authenticate before reading anything else; a silent or hostile peer is cut off.
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val hs = handshake(def, me)
            val hello = readFrame(input, MAX_HANDSHAKE_BYTES)?.let { decodeOrNull<LanHello>(it) } ?: return@withContext
            val (welcome, pending) = hs.serverRespond(hello) ?: run {
                Timber.w("LAN hello refused (not a family member, or not addressed to us)")
                return@withContext
            }
            writeFrame(output, json.encodeToString(welcome).toByteArray())
            val confirm = readFrame(input, MAX_HANDSHAKE_BYTES)?.let { decodeOrNull<LanConfirm>(it) } ?: return@withContext
            val session = hs.serverFinish(pending, confirm) ?: run {
                Timber.w("LAN session from ${pending.clientId.take(8)} failed key confirmation")
                return@withContext
            }
            socket.soTimeout = 0
            Timber.d("LAN session open from ${session.peerId.take(8)}")

            while (!socket.isClosed) {
                val sealed = readFrame(input, MAX_MESSAGE_BYTES + 64) ?: break
                val plain = session.cipher.open(sealed)
                if (plain == null) {
                    Timber.w("LAN frame from ${session.peerId.take(8)} failed to open — closing")
                    break
                }
                val frame = decodeOrNull<LanFrame>(plain) ?: continue
                val topic = frame.topic ?: continue
                val payload = frame.payload ?: continue
                val current = family() ?: break
                // A member removed since the session opened is cut off at their next frame.
                if (current.findMemberById(session.peerId) == null) break
                if (!LanProtocol.topicAllowed(topic, me, current.groupId)) {
                    Timber.w("LAN frame from ${session.peerId.take(8)} for $topic is not ours — dropped")
                    continue
                }
                Timber.d("LocalTransport received $topic from ${session.peerId.take(8)}")
                onMessageReceived?.invoke(topic, payload, session.peerId)
                writeFrame(output, session.cipher.seal(json.encodeToString(LanFrame(ack = frame.id)).toByteArray()))
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

    /** Returns true if [memberId]'s phone has been found on the local network. */
    fun isReachable(memberId: String): Boolean = peerAddresses.containsKey(memberId)

    /**
     * Send [payloadString] to [recipientMemberId] over local WiFi.
     * Returns true only when the peer acknowledged it; false if the peer is unreachable, isn't
     * who it advertised itself as, or didn't ack (caller should fall back to MQTT).
     */
    suspend fun send(topic: String, payloadString: String, recipientMemberId: String): Boolean {
        val address = peerAddresses[recipientMemberId] ?: return false
        // One sender per peer at a time: frames on a session must go out, and be acked, in order.
        val lock = sendLocks.getOrPut(recipientMemberId) { Mutex() }
        return withContext(Dispatchers.IO) { lock.withLock {
            try {
                val conn = connections[recipientMemberId]?.takeUnless { it.socket.isClosed }
                    ?: openSession(recipientMemberId, address)?.also { connections[recipientMemberId] = it }
                    ?: throw IllegalStateException("handshake failed")

                val id = conn.nextId++
                val plain = json.encodeToString(LanFrame(id = id, topic = topic, payload = payloadString)).toByteArray()

                // The receiver enforces this cap. Sending a frame it will discard would report
                // success and skip the MQTT fallback; returning false routes it over the relay.
                if (plain.size > MAX_MESSAGE_BYTES) {
                    Timber.w(
                        "LocalTransport frame for $topic is ${plain.size} bytes, over the " +
                            "$MAX_MESSAGE_BYTES cap — falling back to MQTT"
                    )
                    return@withContext false
                }

                writeFrame(conn.output, conn.session.cipher.seal(plain))
                // Delivered only once the peer says so (F21): a write that reached nobody, or
                // reached a device that isn't our member, is not a delivery.
                conn.socket.soTimeout = ACK_TIMEOUT_MS
                val ackFrame = readFrame(conn.input, MAX_HANDSHAKE_BYTES)
                    ?.let { conn.session.cipher.open(it) }
                    ?.let { decodeOrNull<LanFrame>(it) }
                if (ackFrame?.ack != id) throw IllegalStateException("no ack for frame $id")
                Timber.d("LocalTransport sent to ${recipientMemberId.take(8)} on $topic")
                true
            } catch (e: Exception) {
                Timber.d("LocalTransport send to ${recipientMemberId.take(8)} failed (${e.message}), MQTT fallback")
                connections.remove(recipientMemberId)?.let { runCatching { it.socket.close() } }
                // The address may be stale (new DHCP lease, new port after an app restart), so
                // drop it — but schedule a re-resolve. NSD never reports a still-present service
                // again, so dropping it alone lost the peer for good.
                peerAddresses.remove(recipientMemberId)
                onPeerReachabilityChanged?.invoke(recipientMemberId, false, null)
                scheduleReResolve(recipientMemberId)
                false
            }
        } }
    }

    /** Connect and run the client side of the handshake, or null if the peer can't prove itself. */
    private fun openSession(memberId: String, address: InetSocketAddress): Outbound? {
        val me = localMemberId ?: return null
        val def = family() ?: return null
        val socket = Socket().apply { connect(address, 3_000) }
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val hs = handshake(def, me)
            val pending = hs.clientHello(memberId)
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            writeFrame(out, json.encodeToString(pending.hello).toByteArray())
            val welcome = readFrame(input, MAX_HANDSHAKE_BYTES)?.let { decodeOrNull<LanWelcome>(it) }
                ?: throw IllegalStateException("no welcome")
            val (confirm, session) = hs.clientFinish(pending, welcome)
                ?: throw IllegalStateException("peer at $address is not ${memberId.take(8)}")
            writeFrame(out, json.encodeToString(confirm).toByteArray())
            return Outbound(socket, session)
        } catch (e: Exception) {
            runCatching { socket.close() }
            Timber.w("LAN handshake with ${memberId.take(8)} failed: ${e.message}")
            return null
        }
    }

    // =========================================================================
    // FRAMING
    // =========================================================================

    /** One length-prefixed frame, or null at end of stream or if it's over [max]. */
    private fun readFrame(input: DataInputStream, max: Int): ByteArray? {
        val len = try { input.readInt() } catch (e: Exception) { return null }
        // No resynchronisation point exists in a stream, so any bad length ends the session.
        if (len <= 0 || len > max) return null
        val bytes = ByteArray(len)
        return try { input.readFully(bytes); bytes } catch (e: Exception) { null }
    }

    /** Header and body in one write, so the peer never sees a header alone. */
    private fun writeFrame(out: OutputStream, body: ByteArray) {
        val frame = java.nio.ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
        out.write(frame)
        out.flush()
    }

    private inline fun <reified T> decodeOrNull(bytes: ByteArray): T? =
        runCatching { json.decodeFromString<T>(String(bytes, Charsets.UTF_8)) }.getOrNull()

    // =========================================================================
    // NSD REGISTRATION
    // =========================================================================

    private fun registerNsdService() {
        val memberId = localMemberId ?: return
        // No family key (a family from before 1.12.0) means no name the family can recognise.
        val key = family()?.let { GroupKeys.current(it)?.key } ?: run {
            Timber.w("No family key — LAN advertising off")
            return
        }
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = LanProtocol.serviceName(key, memberId, LanProtocol.utcDay(System.currentTimeMillis()))
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

    /** The family member [serviceName] belongs to today, or null for strangers and ourselves. */
    private fun memberFor(serviceName: String): String? {
        val def = family() ?: return null
        val me = localMemberId ?: return null
        val others = def.members.map { it.memberId }.filter { it != me }
        return LanProtocol.memberForServiceName(serviceName, others, familyKeys(def), System.currentTimeMillis())
    }

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
                val memberId = memberFor(serviceInfo.serviceName) ?: return
                discoveredServices[memberId] = serviceInfo
                Timber.d("NSD found ${memberId.take(8)}, queueing resolve...")
                enqueueResolve(memberId, serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val memberId = memberFor(serviceInfo.serviceName) ?: return
                // A renamed duplicate ("… (2)") can be lost while the peer's live registration
                // stays up; only forget the peer when the service we are tracking is the one lost.
                val tracked = discoveredServices[memberId]
                if (tracked != null && tracked.serviceName != serviceInfo.serviceName) return
                discoveredServices.remove(memberId)
                reResolveJobs.remove(memberId)?.cancel()
                peerFailures.remove(memberId)
                peerAddresses.remove(memberId)
                connections.remove(memberId)?.let { runCatching { it.socket.close() } }
                onPeerReachabilityChanged?.invoke(memberId, false, null)
                Timber.d("Peer lost: ${memberId.take(8)}")
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

    private fun enqueueResolve(memberId: String, serviceInfo: NsdServiceInfo) {
        if (peerAddresses.containsKey(memberId)) return
        if (resolveQueue.any { it.memberId == memberId }) return
        resolveQueue.offer(ResolveRequest(memberId, serviceInfo))
        drainResolveQueue()
    }

    /**
     * Re-resolve a known peer after a failure, waiting longer each consecutive time so a phone
     * that is genuinely away is not hammered. Cancelled if NSD reports the peer lost.
     */
    private fun scheduleReResolve(memberId: String) {
        if (reResolveJobs[memberId]?.isActive == true) return
        val attempt = peerFailures.merge(memberId, 1, Int::plus)!! - 1
        val delayMs = LanPeerPolicy.retryDelayMs(attempt)
        reResolveJobs[memberId] = scope.launch {
            delay(delayMs)
            reResolveJobs.remove(memberId)
            val info = discoveredServices[memberId] ?: return@launch
            Timber.d("Re-resolving LAN peer ${memberId.take(8)} (attempt ${attempt + 1})")
            enqueueResolve(memberId, info)
        }
    }

    private fun drainResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) return
        val request = resolveQueue.poll() ?: run {
            isResolving.set(false)
            return
        }
        try {
            nsdManager?.resolveService(request.serviceInfo, makeResolveListener(request.memberId))
                ?: completeResolve()
        } catch (e: Exception) {
            Timber.w(e, "NSD resolve could not start for ${request.memberId.take(8)}")
            scheduleReResolve(request.memberId)
            completeResolve()
        }
    }

    private fun completeResolve() {
        isResolving.set(false)
        drainResolveQueue()
    }

    private fun makeResolveListener(memberId: String): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: run {
                    scheduleReResolve(memberId)
                    completeResolve()
                    return
                }
                val port = serviceInfo.port
                val address = InetSocketAddress(host, port)
                peerAddresses[memberId] = address
                peerFailures.remove(memberId)
                onPeerReachabilityChanged?.invoke(memberId, true, address)
                Timber.i("Peer resolved: ${memberId.take(8)} @ $host:$port")
                completeResolve()
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("NSD resolve failed for ${memberId.take(8)}: $errorCode")
                scheduleReResolve(memberId)
                completeResolve()
            }
        }
    }

    // =========================================================================
    // WIFI AND DAY CHANGES
    // =========================================================================

    /** Re-advertise under the new day's name just after each UTC midnight (F19). */
    private fun scheduleDayChange() {
        dayJob?.cancel()
        dayJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val nextDay = (LanProtocol.utcDay(now) + 1) * LanProtocol.DAY_MS
                delay(nextDay - now + 5_000)
                restartNsd("new day")
            }
        }
    }

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
        peerAddresses.keys.toList().forEach { memberId ->
            peerAddresses.remove(memberId)
            onPeerReachabilityChanged?.invoke(memberId, false, null)
        }
        discoveredServices.clear()
        reResolveJobs.values.forEach { it.cancel() }
        reResolveJobs.clear()
        peerFailures.clear()
        resolveQueue.clear()
        connections.values.forEach { runCatching { it.socket.close() } }
        connections.clear()
        registrationFailures = 0
        discoveryFailures = 0
        registerNsdService()
        startNsdDiscovery()
    }
}
