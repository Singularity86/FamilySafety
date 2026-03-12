package com.example.familysafety.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates local-network avatar sync:
 *
 *  1. HTTP server (NanoHTTPD) — serves this device's avatar on an ephemeral port.
 *  2. mDNS (NsdManager) — registers the device and discovers peers.
 *  3. Fetch — when a peer is found whose avatarHash differs from our cache, fetch via HTTP.
 *  4. In-memory cache — exposes [memberAvatars] StateFlow for the UI.
 *
 * Avatar bytes travel only over the local network (plain HTTP is acceptable for photos).
 * The avatarHash travels via MQTT with the GroupDefinition so remote members still see
 * whether a new avatar is available once they're on the same network.
 */
@Singleton
class AvatarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: AvatarStore,
    private val groupStateManager: GroupStateManager,
    private val localMemberId: LocalMemberId
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _memberAvatars = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    val memberAvatars: StateFlow<Map<String, Bitmap?>> = _memberAvatars.asStateFlow()

    private var httpServer: AvatarHttpServer? = null
    private var discovery: AvatarDiscovery? = null

    /** Call once after onboarding is complete and the group definition is loaded. */
    fun initialize() {
        loadCachedAvatars()
        startHttpServer()
        observeGroupMembership()
        startNsd()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Pick a new avatar from [uri] (gallery or camera), compress it, save locally,
     * update the avatarHash in the group definition (triggers MQTT broadcast), and
     * update the in-memory cache so the UI reflects the change immediately.
     */
    suspend fun setMyAvatar(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open image"))

            // Force sRGB so Display P3 photos (Galaxy S-series) don't get
            // hue/saturation shifted when rendered on an sRGB canvas.
            val opts = BitmapFactory.Options().apply {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val original = BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream.close()

            original ?: return@withContext Result.failure(Exception("Cannot decode image"))

            val scaled = scaleBitmap(original, maxDimension = 512)
            if (scaled !== original) original.recycle()

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            scaled.recycle()

            val jpeg = out.toByteArray()
            val myId = localMemberId.value

            store.save(myId, jpeg)

            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            updateCache(myId, bitmap)

            val hash = AvatarStore.sha256Hex(jpeg)
            groupStateManager.updateLocalMemberAvatarHash(hash)

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "AvatarRepository: setMyAvatar failed")
            Result.failure(e)
        }
    }

    /**
     * Save an already-decoded [Bitmap] as the current user's avatar.
     * Used by the crop screen, which decodes the image once and passes the cropped result.
     */
    suspend fun setMyAvatarFromBitmap(bitmap: Bitmap): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val scaled = scaleBitmap(bitmap, maxDimension = 512)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== bitmap) scaled.recycle()
            val jpeg = out.toByteArray()
            val myId = localMemberId.value
            store.save(myId, jpeg)
            updateCache(myId, BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            groupStateManager.updateLocalMemberAvatarHash(AvatarStore.sha256Hex(jpeg))
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "AvatarRepository: setMyAvatarFromBitmap failed")
            Result.failure(e)
        }
    }

    fun cleanup() {
        try { httpServer?.stop() } catch (_: Exception) {}
        discovery?.stop()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal — startup
    // -------------------------------------------------------------------------

    private fun loadCachedAvatars() {
        val members = groupStateManager.groupDefinition.value?.members ?: return
        val map = members.associate { member ->
            val bytes = store.load(member.memberId)
            member.memberId to bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        _memberAvatars.value = map
    }

    private fun startHttpServer() {
        val server = AvatarHttpServer(store, localMemberId.value)
        try {
            server.start()
            httpServer = server
            Timber.d("AvatarRepository: HTTP server started on port ${server.listeningPort}")
        } catch (e: Exception) {
            Timber.e(e, "AvatarRepository: failed to start HTTP server")
        }
    }

    /** Keep the in-memory avatar map in sync as members join/leave. */
    private fun observeGroupMembership() {
        scope.launch {
            groupStateManager.groupDefinition.collect { groupDef ->
                groupDef ?: return@collect
                val memberIds = groupDef.members.map { it.memberId }.toSet()
                val current = _memberAvatars.value.toMutableMap()

                // Add entries for new members
                groupDef.members.forEach { member ->
                    if (!current.containsKey(member.memberId)) {
                        current[member.memberId] = store.load(member.memberId)
                            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                }
                // Remove entries for members who left
                current.keys.removeAll { it !in memberIds }
                _memberAvatars.value = current
            }
        }
    }

    /**
     * Start mDNS registration + discovery. Must run on a thread with a Looper (Main).
     * Catches SecurityException gracefully if NEARBY_WIFI_DEVICES is not yet granted.
     */
    private fun startNsd() {
        val port = httpServer?.listeningPort?.takeIf { it > 0 } ?: return

        scope.launch(Dispatchers.Main) {
            try {
                val disc = AvatarDiscovery(context, scope) { memberId, host, fetchPort ->
                    fetchAvatarIfNeeded(memberId, host, fetchPort)
                }
                disc.start(localMemberId.value, port)
                discovery = disc
            } catch (e: SecurityException) {
                Timber.w("AvatarRepository: NSD requires NEARBY_WIFI_DEVICES (not yet granted)")
            } catch (e: Exception) {
                Timber.e(e, "AvatarRepository: NSD start failed")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal — avatar fetch
    // -------------------------------------------------------------------------

    private suspend fun fetchAvatarIfNeeded(memberId: String, host: String, port: Int) {
        val myId = localMemberId.value
        if (memberId == myId) return

        val member = groupStateManager.groupDefinition.value?.findMemberById(memberId) ?: return
        val expectedHash = member.avatarHash ?: return // member has no avatar set

        if (store.cachedHash(memberId) == expectedHash) {
            Timber.d("AvatarRepository: $memberId avatar already up to date")
            return
        }

        Timber.d("AvatarRepository: fetching avatar for $memberId from $host:$port")
        withContext(Dispatchers.IO) {
            try {
                val conn = URL("http://$host:$port/avatar").openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 10_000
                conn.connect()

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Timber.w("AvatarRepository: HTTP ${conn.responseCode} from $memberId")
                    return@withContext
                }

                val bytes = conn.inputStream.readBytes()
                conn.disconnect()

                // Verify hash to ensure integrity
                val actualHash = AvatarStore.sha256Hex(bytes)
                if (actualHash != expectedHash) {
                    Timber.w("AvatarRepository: hash mismatch for $memberId")
                    return@withContext
                }

                store.save(memberId, bytes)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                updateCache(memberId, bitmap)
                Timber.i("AvatarRepository: cached avatar for $memberId")
            } catch (e: Exception) {
                Timber.e(e, "AvatarRepository: fetch failed for $memberId")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun updateCache(memberId: String, bitmap: Bitmap?) {
        _memberAvatars.update { it + (memberId to bitmap) }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
