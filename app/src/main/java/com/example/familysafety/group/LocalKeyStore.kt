package com.example.familysafety.group

/**
 * Interface for secure key storage.
 * Implementation uses Android Keystore-encrypted storage (AndroidKeyStoreLocalKeyStore).
 *
 * Keys are derived from a BIP-39 seed via SLIP-10:
 * - m/44'/1984'/accountIndex'/0' → Ed25519 (signing)
 * - m/44'/1984'/accountIndex'/1' → X25519 (encryption)
 */
interface LocalKeyStore {
    /** Get the Ed25519 private key (64-byte secret key derived from the stored seed). */
    fun getEd25519PrivateKey(): ByteArray

    /** Get the Ed25519 public key (32 bytes). */
    fun getEd25519PublicKey(): ByteArray

    /** Get the X25519 private key (32 bytes). */
    fun getX25519PrivateKey(): ByteArray

    /** Get the X25519 public key (32 bytes). */
    fun getX25519PublicKey(): ByteArray

    /**
     * Initialize keys from a BIP-39 seed using SLIP-10 derivation.
     *
     * @param seed 64-byte seed from BIP-39 mnemonic
     * @param accountIndex Account index (default 0)
     */
    fun initializeFromSeed(seed: ByteArray, accountIndex: Int = 0)

    /** Check if keys have been initialized. */
    fun isInitialized(): Boolean
}
