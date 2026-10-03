package com.example.familysafety.billing

import com.example.familysafety.group.hexToByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/**
 * A developer-issued "this family gets the paid features free" code.
 *
 * Signed offline by `tools/gen_grant_code.py`, which is committed — only the private key it
 * reads is not (see that script's header). Verification here uses BouncyCastle's Ed25519
 * rather than the app's usual Lazysodium provider so it can run in a plain JVM unit test
 * (`LazysodiumCryptoProvider` loads a native library at construction and cannot be
 * instantiated off-device — the same reason `E2EEManager` and `VaultKeyDerivation` can't be
 * either). Ed25519 is a fixed, standard algorithm, so a key generated with PyNaCl on the
 * signing side verifies correctly here with no compatibility gap.
 *
 * The signed message never states which group it is for — it is built from *this device's
 * own* groupId at verify time. A code minted for one family simply fails to verify against
 * any other family's groupId, without needing to carry the id as a separate, checkable field.
 */
@Serializable
data class GrantCodePayload(
    /** 0 means "never expires." Anything else is compared against wall-clock time. */
    val expiryEpochMs: Long,
    val signatureHex: String
)

sealed class GrantCodeResult {
    data class Valid(val expiresAtEpochMs: Long?) : GrantCodeResult()
    object InvalidFormat : GrantCodeResult()
    object BadSignature : GrantCodeResult()
    object Expired : GrantCodeResult()
}

object GrantCode {

    private val json = Json { ignoreUnknownKeys = true }

    fun signingMessage(groupId: String, expiryEpochMs: Long): ByteArray =
        "GRANT:$groupId:$expiryEpochMs".toByteArray(Charsets.UTF_8)

    fun encode(payload: GrantCodePayload): String =
        Base64.getEncoder().encodeToString(json.encodeToString(payload).toByteArray(Charsets.UTF_8))

    /**
     * @param code as pasted by the user, base64 of the JSON payload.
     * @param groupId this device's own current group id — never taken from the code itself.
     * @param publicKeyHex the developer's grant-signing public key, baked into the app.
     */
    fun verify(
        code: String,
        groupId: String,
        publicKeyHex: String,
        nowMs: Long
    ): GrantCodeResult {
        val payload = try {
            json.decodeFromString<GrantCodePayload>(
                String(Base64.getDecoder().decode(code.trim()), Charsets.UTF_8)
            )
        } catch (e: Exception) {
            return GrantCodeResult.InvalidFormat
        }

        val signatureBytes = try {
            payload.signatureHex.hexToByteArray()
        } catch (e: Exception) {
            return GrantCodeResult.InvalidFormat
        }
        if (signatureBytes.size != 64) return GrantCodeResult.InvalidFormat

        val publicKeyBytes = try {
            publicKeyHex.hexToByteArray()
        } catch (e: Exception) {
            return GrantCodeResult.InvalidFormat
        }
        if (publicKeyBytes.size != 32) return GrantCodeResult.InvalidFormat

        val message = signingMessage(groupId, payload.expiryEpochMs)
        val signer = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicKeyBytes, 0))
            update(message, 0, message.size)
        }
        if (!signer.verifySignature(signatureBytes)) return GrantCodeResult.BadSignature

        if (payload.expiryEpochMs != 0L && nowMs > payload.expiryEpochMs) {
            return GrantCodeResult.Expired
        }
        return GrantCodeResult.Valid(payload.expiryEpochMs.takeIf { it != 0L })
    }
}
