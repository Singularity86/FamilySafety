package com.example.familysafety.vault

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a vault code into a key, and is the only thing standing between a stolen container
 * and its contents.
 *
 * The code is **never stored and never hard-coded**. It exists as a key-derivation input and
 * nothing else — there is no hash of it on disk to compare against, which is what leaves
 * nothing to find and nothing to verify a guess against locally beyond trying it.
 *
 * A hard-coded code would repeat the F2 mistake exactly: `magic` and `bananas` were lifted
 * straight out of the release `BuildConfig` during the security review, and a vault code in
 * the APK would be every bit as recoverable.
 *
 * ## Why Argon2id
 *
 * A code a person can remember has far less entropy than a key. Argon2id makes each guess
 * cost memory and time rather than a hash, so an attacker holding the container is reduced to
 * a slow, expensive search instead of a fast one. It comes free from a dependency already
 * present: libsodium's `crypto_pwhash` *is* Argon2id, and `LazySodiumAndroid` already
 * implements [PwHash] — the app simply never used that interface, only `Sign` and `Box`.
 *
 * ## Interactive, not moderate
 *
 * libsodium's MODERATE preset asks for 256 MiB, which on a phone means thrashing or an OOM.
 * INTERACTIVE — 64 MiB, 2 passes — is the preset intended for exactly this case and takes
 * well under a second on the hardware this app targets. It still has to run off the main
 * thread.
 *
 * ## Constant work, deliberately
 *
 * Derivation costs the same whether the code is right, wrong, or the decoy: nothing here can
 * tell, because nothing here compares anything. Opening then tries every slot regardless of
 * outcome. There is no early exit and therefore no timing that distinguishes a correct code
 * from an incorrect one — which matters, because a timing difference would be as good as an
 * error message, and an error message would prove a correct code exists.
 */
@Singleton
class VaultKeyDerivation @Inject constructor() {

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    /**
     * Derive the 32-byte key for [code] within [groupId].
     *
     * The salt is the group id, so the same code in two different families yields different
     * keys and a container cannot be attacked with work done against another family's. It is
     * not secret — a salt does not need to be — it only needs to be distinct per family, and
     * libsodium requires it to be exactly [PwHash.SALTBYTES], so it is hashed to that width.
     *
     * @throws IllegalStateException if libsodium refuses, which means out of memory rather
     *   than a bad code — there is no such thing as a bad code here.
     */
    fun deriveKey(code: String, groupId: String): ByteArray {
        val key = ByteArray(KEY_BYTES)
        val password = code.toByteArray(Charsets.UTF_8)
        val ok = sodium.cryptoPwHash(
            key,
            key.size,
            password,
            password.size,
            saltFor(groupId),
            PwHash.OPSLIMIT_INTERACTIVE,
            NativeLong(PwHash.MEMLIMIT_INTERACTIVE.toLong()),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13
        )
        check(ok) { "vault key derivation failed" }
        return key
    }

    /** SHA-256 of the group id, truncated to libsodium's required salt width. */
    private fun saltFor(groupId: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(groupId.toByteArray(Charsets.UTF_8))
            .copyOf(PwHash.SALTBYTES)

    companion object {
        const val KEY_BYTES = 32

        /**
         * Shortest code accepted.
         *
         * Not a strength check on a specific code — there is no way to reject a code without
         * admitting which ones are real. It only stops a code so short that Argon2id's cost
         * cannot make up the difference.
         */
        const val MIN_CODE_LENGTH = 6
    }
}
