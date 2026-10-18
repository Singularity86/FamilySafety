package com.example.familysafety.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import com.example.familysafety.group.ConnectionState
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.transport.LocalTransport
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
 *  1. When a family member's phone is on the same network and their roster avatarHash differs
 *     from our cache, ask them for it over the authenticated LAN session ([LocalTransport]).
 *  2. Answer such requests with our own photo, to that member only.
 *  3. In-memory cache — exposes [memberAvatars] StateFlow for the UI.
 *
 * Avatar bytes travel only over the local network, inside the LAN session (4.1, F20). They used
 * to come from a plain HTTP server whose access token was printed in its network advertisement,
 * so anyone on the same Wi-Fi could download every member's photo. The avatarHash travels via
 * MQTT with the GroupDefinition, so a member far away still learns a new photo exists and gets
 * it next time they share a network.
 */
@Singleton
class AvatarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: AvatarStore,
    private val groupStateManager: GroupStateManager,
    private val localMemberId: LocalMemberId,
    private val localTransport: LocalTransport
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _memberAvatars = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    val memberAvatars: StateFlow<Map<String, Bitmap?>> = _memberAvatars.asStateFlow()

    // When we last asked each member for which hash, so a slow or silent peer isn't re-asked
    // on every roster or presence change.
    private val lastRequest = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** Call once after onboarding is complete and the group definition is loaded. */
    fun initialize() {
        loadCachedAvatars()
        observeGroupMembership()
        requestStalePhotosOnLan()
    }

    /**
     * A photo request or reply that arrived over an authenticated LAN session from [senderId].
     * Topics: `familysafe/{me}/avatar/request` `{"hash"}` and `familysafe/{me}/avatar/data`
     * `{"hash","jpeg"}` (base64).
     */
    suspend fun handleLanMessage(topic: String, payload: String, senderId: String) {
        val me = localMemberId.value
        val sender = groupStateManager.groupDefinition.value?.findMemberById(senderId) ?: return
        when {
            topic == "familysafe/$me/avatar/request" -> {
                val bytes = store.load(me) ?: return
                val reply = avatarJson.encodeToString(
                    AvatarData.serializer(),
                    AvatarData(AvatarStore.sha256Hex(bytes), java.util.Base64.getEncoder().encodeToString(bytes))
                )
                localTransport.send("familysafe/${sender.memberId}/avatar/data", reply, sender.memberId)
            }
            topic == "familysafe/$me/avatar/data" -> {
                val data = runCatching { avatarJson.decodeFromString(AvatarData.serializer(), payload) }.getOrNull() ?: return
                val bytes = runCatching { java.util.Base64.getDecoder().decode(data.jpeg) }.getOrNull() ?: return
                if (bytes.size > MAX_AVATAR_BYTES) {
                    Timber.w("AvatarRepository: avatar from ${senderId.take(8)} larger than $MAX_AVATAR_BYTES bytes — ignored")
                    return
                }
                // Only the photo the roster says this member has, so no one can swap it.
                if (AvatarStore.sha256Hex(bytes) != sender.avatarHash) {
                    Timber.w("AvatarRepository: hash mismatch for ${senderId.take(8)}")
                    return
                }
                store.save(sender.memberId, bytes)
                updateCache(sender.memberId, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                Timber.i("AvatarRepository: cached avatar for ${senderId.take(8)}")
            }
        }
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
     * Ask each member on the same network for their photo when the roster's hash for it isn't
     * the one we hold. Re-checked whenever the roster or anyone's connection changes; a request
     * for the same hash is repeated at most every [REQUEST_COOLDOWN_MS].
     */
    private fun requestStalePhotosOnLan() {
        scope.launch {
            combine(groupStateManager.groupDefinition, groupStateManager.memberStates) { def, states -> def to states }
                .collect { (def, states) ->
                    def ?: return@collect
                    val me = localMemberId.value
                    val now = System.currentTimeMillis()
                    for (member in def.members) {
                        if (member.memberId == me) continue
                        val wanted = member.avatarHash ?: continue
                        if (store.cachedHash(member.memberId) == wanted) continue
                        if (states[member.memberId]?.connectionState !is ConnectionState.Local) continue
                        val last = lastRequest[member.memberId]
                        if (last != null && last.first == wanted && now - last.second < REQUEST_COOLDOWN_MS) continue
                        lastRequest[member.memberId] = wanted to now
                        val request = avatarJson.encodeToString(AvatarRequest.serializer(), AvatarRequest(wanted))
                        launch {
                            localTransport.send("familysafe/${member.memberId}/avatar/request", request, member.memberId)
                        }
                    }
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

    @kotlinx.serialization.Serializable
    private data class AvatarRequest(val hash: String)

    @kotlinx.serialization.Serializable
    private data class AvatarData(val hash: String, val jpeg: String)

    companion object {
        const val MAX_AVATAR_BYTES = 512 * 1024
        private const val REQUEST_COOLDOWN_MS = 60_000L
        private val avatarJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** Up to [limit] bytes of [input], or null if there are more. */
        internal fun readCapped(input: java.io.InputStream, limit: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > limit) return null
                out.write(buffer, 0, n)
            }
        }
    }
}
