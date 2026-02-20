package com.example.familysafety.avatar

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Registers this device on the LAN via mDNS (_familysafety._tcp) and discovers
 * other devices. When a peer is resolved, [onPeerResolved] is called with their
 * memberId, IP address, and HTTP server port.
 *
 * NSD callbacks require a thread with a Looper. We dispatch NSD operations to
 * Dispatchers.Main to satisfy this requirement.
 */
class AvatarDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPeerResolved: suspend (memberId: String, host: String, port: Int) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolveMutex = Mutex()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    companion object {
        private const val SERVICE_TYPE = "_familysafety._tcp"
        private const val ATTR_MEMBER_ID = "mid"
        private const val TAG = "AvatarDiscovery"
    }

    /** Start both registration and discovery. Must be called from a thread with a Looper. */
    fun start(myMemberId: String, serverPort: Int) {
        registerSelf(myMemberId, serverPort)
        startDiscovery()
    }

    fun stop() {
        try { registrationListener?.let { nsdManager.unregisterService(it) } }
        catch (e: Exception) { Timber.w(e, "$TAG: unregister failed") }

        try {
            if (isDiscovering) {
                discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            }
        } catch (e: Exception) { Timber.w(e, "$TAG: stop discovery failed") }

        registrationListener = null
        discoveryListener = null
        isDiscovering = false
    }

    private fun registerSelf(memberId: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "fs-${memberId.take(16)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ATTR_MEMBER_ID, memberId)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) =
                Timber.d("$TAG: Registered as ${i.serviceName}")
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) =
                Timber.e("$TAG: Registration failed: $code")
            override fun onServiceUnregistered(i: NsdServiceInfo) =
                Timber.d("$TAG: Unregistered")
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) =
                Timber.w("$TAG: Unregistration failed: $code")
        }
        registrationListener = listener

        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: registerService failed")
        }
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Timber.e("$TAG: Discovery start failed: $code")
                isDiscovering = false
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) =
                Timber.w("$TAG: Discovery stop failed: $code")
            override fun onDiscoveryStarted(type: String) {
                Timber.d("$TAG: Discovery started")
                isDiscovering = true
            }
            override fun onDiscoveryStopped(type: String) {
                Timber.d("$TAG: Discovery stopped")
                isDiscovering = false
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.trimEnd('.') == SERVICE_TYPE) {
                    scope.launch(Dispatchers.Main) { resolveService(info) }
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) =
                Timber.d("$TAG: Service lost: ${info.serviceName}")
        }
        discoveryListener = listener

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: discoverServices failed")
        }
    }

    // NsdManager only allows one resolve at a time on older APIs — serialize with a mutex.
    private suspend fun resolveService(info: NsdServiceInfo) {
        resolveMutex.withLock {
            try {
                val resolved = suspendCoroutine<NsdServiceInfo?> { cont ->
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                            Timber.w("$TAG: Resolve failed for ${i.serviceName}: $code")
                            cont.resume(null)
                        }
                        override fun onServiceResolved(i: NsdServiceInfo) = cont.resume(i)
                    })
                } ?: return@withLock

                val memberId = resolved.attributes[ATTR_MEMBER_ID]
                    ?.let { String(it) }
                    ?: return@withLock

                val host = resolved.host?.hostAddress ?: return@withLock
                val port = resolved.port

                Timber.d("$TAG: Resolved $memberId @ $host:$port")
                onPeerResolved(memberId, host, port)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: resolveService exception")
            }
        }
    }
}
