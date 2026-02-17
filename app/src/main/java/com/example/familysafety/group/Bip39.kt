package com.example.familysafety.group

import android.content.Context
import com.example.familysafety.R

/**
 * BIP-39 mnemonic utilities.
 * Uses the official BIP-39 English wordlist (2048 words) loaded from resources.
 */
object Bip39 {

    private const val PBKDF2_ITERATIONS = 2048
    private const val SEED_LENGTH = 64

    // Cached wordlist loaded from resources
    @Volatile
    private var wordlist: List<String>? = null

    /**
     * Initialize with context to load the wordlist from resources.
     * Must be called before generate12WordMnemonic().
     */
    fun initialize(context: Context) {
        if (wordlist == null) {
            synchronized(this) {
                if (wordlist == null) {
                    wordlist = loadWordlist(context)
                }
            }
        }
    }

    private fun loadWordlist(context: Context): List<String> {
        return context.resources.openRawResource(R.raw.bip39_english)
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
    }

    private fun getWordlist(): List<String> {
        return wordlist ?: throw IllegalStateException(
            "Bip39 not initialized. Call Bip39.initialize(context) first."
        )
    }

    /**
     * Generate a 12-word BIP-39 mnemonic per the standard.
     * 128 bits of entropy + 4-bit SHA-256 checksum = 132 bits = twelve 11-bit word indices.
     *
     * @return List of 12 words from the BIP-39 wordlist
     */
    fun generate12WordMnemonic(): List<String> {
        val words = getWordlist()
        val secureRandom = java.security.SecureRandom()

        // 1. Generate 128 bits (16 bytes) of entropy
        val entropy = ByteArray(16)
        secureRandom.nextBytes(entropy)

        // 2. SHA-256 checksum of entropy
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(entropy)

        // 3. Convert entropy to bit string and append first 4 checksum bits
        val bits = StringBuilder(132)
        for (byte in entropy) {
            val b = byte.toInt() and 0xFF
            for (bit in 7 downTo 0) {
                bits.append((b shr bit) and 1)
            }
        }
        val checksumByte = hash[0].toInt() and 0xFF
        for (bit in 7 downTo 4) {
            bits.append((checksumByte shr bit) and 1)
        }

        // 4. Split into twelve 11-bit indices and map to words
        return (0 until 12).map { i ->
            val index = Integer.parseInt(bits.substring(i * 11, (i + 1) * 11), 2)
            words[index]
        }
    }

    /**
     * Convert BIP-39 mnemonic to 64-byte seed.
     *
     * @param mnemonic Space-separated mnemonic words
     * @param passphrase Optional passphrase (BIP-39 calls this "salt")
     * @return 64-byte seed
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = mnemonic.trim().lowercase()
            .split("\\s+".toRegex())
            .joinToString(" ")

        val salt = "mnemonic$passphrase"

        return pbkdf2Sha512(
            password = normalizedMnemonic.toCharArray(),
            salt = salt.toByteArray(Charsets.UTF_8),
            iterations = PBKDF2_ITERATIONS,
            keyLength = SEED_LENGTH
        )
    }

    /**
     * PBKDF2-HMAC-SHA512 key derivation.
     */
    private fun pbkdf2Sha512(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int
    ): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, iterations, keyLength * 8)

        // Use BouncyCastle provider (already in dependencies)
        val factory = javax.crypto.SecretKeyFactory.getInstance(
            "PBKDF2WithHmacSHA512",
            org.bouncycastle.jce.provider.BouncyCastleProvider()
        )

        val key = factory.generateSecret(spec)
        return key.encoded
    }

    /**
     * Validate a BIP-39 mnemonic.
     *
     * @param mnemonic Space-separated mnemonic words
     * @return true if all words are in the BIP-39 wordlist
     */
    fun validateMnemonic(mnemonic: String): Boolean {
        val words = getWordlist()
        val mnemonicWords = mnemonic.trim().lowercase().split("\\s+".toRegex())
        return mnemonicWords.all { it in words }
    }
}
