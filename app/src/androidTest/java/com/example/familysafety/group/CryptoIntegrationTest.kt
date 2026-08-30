package com.example.familysafety.group

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for the cryptographic stack.
 *
 * These tests run on a real Android device/emulator and verify:
 * - BIP-39 mnemonic → seed derivation
 * - SLIP-10 key derivation (Ed25519 + X25519)
 * - Ed25519 signing and verification
 * - X25519 encryption and decryption
 * - Member ID derivation
 *
 * No mocks - this is the real crypto stack.
 */
@RunWith(AndroidJUnit4::class)
class CryptoIntegrationTest {

    private lateinit var context: android.content.Context

    // Standard BIP-39 test mnemonic (DO NOT use in production!)
    // This is the "abandon x11 + about" test vector from BIP-39
    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    // Second mnemonic for two-party tests
    private val testMnemonic2 = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // The wordlist is read from a resource and is not loaded until something asks for
        // it. In the app that happens in OnboardingViewModel, which is the only caller of
        // the two functions that need it; a test reaching validateMnemonic directly has to
        // do it itself or the call throws before it asserts anything.
        Bip39.initialize(context)
    }

    // =========================================================================
    // BIP-39 SEED DERIVATION TESTS
    // =========================================================================

    @Test
    fun bip39_mnemonicToSeed_producesCorrectLength() {
        val seed = Bip39.mnemonicToSeed(testMnemonic)

        assertEquals("Seed should be 64 bytes", 64, seed.size)
    }

    @Test
    fun bip39_mnemonicToSeed_isDeterministic() {
        val seed1 = Bip39.mnemonicToSeed(testMnemonic)
        val seed2 = Bip39.mnemonicToSeed(testMnemonic)

        assertArrayEquals("Same mnemonic should produce same seed", seed1, seed2)
    }

    @Test
    fun bip39_mnemonicToSeed_differentMnemonicsProduceDifferentSeeds() {
        val seed1 = Bip39.mnemonicToSeed(testMnemonic)
        val seed2 = Bip39.mnemonicToSeed(testMnemonic2)

        assertFalse(
            "Different mnemonics should produce different seeds",
            seed1.contentEquals(seed2)
        )
    }

    @Test
    fun bip39_mnemonicToSeed_passphraseChangesSeed() {
        val seedNoPass = Bip39.mnemonicToSeed(testMnemonic, "")
        val seedWithPass = Bip39.mnemonicToSeed(testMnemonic, "mysecretpassphrase")

        assertFalse(
            "Passphrase should change the derived seed",
            seedNoPass.contentEquals(seedWithPass)
        )
    }

    @Test
    fun bip39_validateMnemonic_acceptsValidMnemonics() {
        assertTrue(Bip39.validateMnemonic(testMnemonic)) // 12 words
        assertTrue(Bip39.validateMnemonic(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon agent"
        )) // 18 words
        assertTrue(Bip39.validateMnemonic(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        )) // 24 words
    }

    @Test
    fun bip39_validateMnemonic_rejectsInvalidWordCounts() {
        assertFalse(Bip39.validateMnemonic("abandon abandon abandon")) // 3 words
        assertFalse(Bip39.validateMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon")) // 10 words
    }

    // =========================================================================
    // SLIP-10 KEY DERIVATION TESTS
    // =========================================================================

    @Test
    fun slip10_deriveEd25519_producesCorrectKeyLengths() {
        val seed = Bip39.mnemonicToSeed(testMnemonic)
        val keyPair = Slip10.deriveEd25519(seed, FamilySafeKeyPaths.signingKeyPath(0))

        assertEquals("Ed25519 private key should be 32 bytes", 32, keyPair.privateKey.size)
        assertEquals("Ed25519 public key should be 32 bytes", 32, keyPair.publicKey.size)
    }

    @Test
    fun slip10_deriveX25519_producesCorrectKeyLengths() {
        val seed = Bip39.mnemonicToSeed(testMnemonic)
        val keyPair = Slip10.deriveX25519(seed, FamilySafeKeyPaths.encryptionKeyPath(0))

        assertEquals("X25519 private key should be 32 bytes", 32, keyPair.privateKey.size)
        assertEquals("X25519 public key should be 32 bytes", 32, keyPair.publicKey.size)
    }

    @Test
    fun slip10_derivation_isDeterministic() {
        val seed = Bip39.mnemonicToSeed(testMnemonic)

        val keyPair1 = Slip10.deriveEd25519(seed, FamilySafeKeyPaths.signingKeyPath(0))
        val keyPair2 = Slip10.deriveEd25519(seed, FamilySafeKeyPaths.signingKeyPath(0))

        assertArrayEquals("Same seed + path should produce same private key", keyPair1.privateKey, keyPair2.privateKey)
        assertArrayEquals("Same seed + path should produce same public key", keyPair1.publicKey, keyPair2.publicKey)
    }

    @Test
    fun slip10_differentAccounts_produceDifferentKeys() {
        val seed = Bip39.mnemonicToSeed(testMnemonic)

        val account0 = Slip10.deriveEd25519(seed, FamilySafeKeyPaths.signingKeyPath(0))
        val account1 = Slip10.deriveEd25519(seed, FamilySafeKeyPaths.signingKeyPath(1))

        assertFalse(
            "Different account indices should produce different keys",
            account0.privateKey.contentEquals(account1.privateKey)
        )
    }

    @Test
    fun slip10_signingAndEncryptionKeys_areDifferent() {
        val seed = Bip39.mnemonicToSeed(testMnemonic)

        val signingKey = Slip10.deriveEd25519(seed, FamilySafeKeyPaths.signingKeyPath(0))
        val encryptionKey = Slip10.deriveX25519(seed, FamilySafeKeyPaths.encryptionKeyPath(0))

        assertFalse(
            "Signing and encryption keys should be different",
            signingKey.privateKey.contentEquals(encryptionKey.privateKey)
        )
    }

    // =========================================================================
    // FAMILYSAFE KEY DERIVATION TESTS
    // =========================================================================

    @Test
    fun familySafeKeyDerivation_derivesAllKeys() {
        val seed = Bip39.mnemonicToSeed(testMnemonic)
        val derivation = FamilySafeKeyDerivation(seed)
        val keys = derivation.deriveKeysForAccount(0)

        // Check signing key pair
        assertEquals(32, keys.signingKeyPair.privateKey.size)
        assertEquals(32, keys.signingKeyPair.publicKey.size)

        // Check encryption key pair
        assertEquals(32, keys.encryptionKeyPair.privateKey.size)
        assertEquals(32, keys.encryptionKeyPair.publicKey.size)

        // Check derived values
        assertEquals(64, keys.ed25519PublicKeyHex.length) // 32 bytes = 64 hex chars
        assertEquals(64, keys.x25519PublicKeyHex.length)
        assertEquals(32, keys.memberId.length) // 16 bytes = 32 hex chars
    }

    @Test
    fun familySafeKeyDerivation_memberIdIsDeterministic() {
        val seed = Bip39.mnemonicToSeed(testMnemonic)

        val keys1 = FamilySafeKeyDerivation(seed).deriveKeysForAccount(0)
        val keys2 = FamilySafeKeyDerivation(seed).deriveKeysForAccount(0)

        assertEquals(
            "Same seed should produce same member ID",
            keys1.memberId,
            keys2.memberId
        )
    }

    // =========================================================================
    // ED25519 SIGNING TESTS (via LazysodiumCryptoProvider)
    // =========================================================================

    @Test
    fun ed25519_signAndVerify_roundTrip() {
        // Setup: Initialize keystore with test mnemonic
        val keyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(keyStore)

        val seed = Bip39.mnemonicToSeed(testMnemonic)
        keyStore.initializeFromSeed(seed, 0)

        val cryptoProvider = LazysodiumCryptoProvider(keyStore)

        // Sign a message
        val message = "Hello, Family!".toByteArray(Charsets.UTF_8)
        val signature = cryptoProvider.sign(message)

        // Verify the signature
        val publicKey = cryptoProvider.getLocalEd25519PublicKey()
        val isValid = cryptoProvider.verifySignature(message, signature, publicKey)

        assertTrue("Signature should be valid", isValid)
    }

    @Test
    fun ed25519_signatureIsCorrectLength() {
        val keyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(keyStore)

        val seed = Bip39.mnemonicToSeed(testMnemonic)
        keyStore.initializeFromSeed(seed, 0)

        val cryptoProvider = LazysodiumCryptoProvider(keyStore)
        val signature = cryptoProvider.sign("test".toByteArray())

        assertEquals("Ed25519 signature should be 64 bytes", 64, signature.size)
    }

    @Test
    fun ed25519_tamperedMessageFailsVerification() {
        val keyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(keyStore)

        val seed = Bip39.mnemonicToSeed(testMnemonic)
        keyStore.initializeFromSeed(seed, 0)

        val cryptoProvider = LazysodiumCryptoProvider(keyStore)

        // Sign original message
        val originalMessage = "Hello, Family!".toByteArray(Charsets.UTF_8)
        val signature = cryptoProvider.sign(originalMessage)

        // Try to verify with tampered message
        val tamperedMessage = "Hello, Family?".toByteArray(Charsets.UTF_8)
        val publicKey = cryptoProvider.getLocalEd25519PublicKey()
        val isValid = cryptoProvider.verifySignature(tamperedMessage, signature, publicKey)

        assertFalse("Tampered message should fail verification", isValid)
    }

    @Test
    fun ed25519_wrongPublicKeyFailsVerification() {
        // Setup Alice
        val aliceKeyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(aliceKeyStore)
        val aliceSeed = Bip39.mnemonicToSeed(testMnemonic)
        aliceKeyStore.initializeFromSeed(aliceSeed, 0)
        val aliceCrypto = LazysodiumCryptoProvider(aliceKeyStore)

        // Get Bob's public key (different seed)
        val bobSeed = Bip39.mnemonicToSeed(testMnemonic2)
        val bobKeys = FamilySafeKeyDerivation(bobSeed).deriveKeysForAccount(0)
        val bobPublicKey = bobKeys.ed25519PublicKeyHex

        // Alice signs a message
        val message = "Hello".toByteArray()
        val signature = aliceCrypto.sign(message)

        // Try to verify with Bob's public key (should fail)
        val isValid = aliceCrypto.verifySignature(message, signature, bobPublicKey)

        assertFalse("Verification with wrong public key should fail", isValid)
    }

    // =========================================================================
    // X25519 ENCRYPTION TESTS
    // =========================================================================

    @Test
    fun x25519_encryptAndDecrypt_roundTrip() {
        // Setup Alice
        val aliceKeyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(aliceKeyStore)
        val aliceSeed = Bip39.mnemonicToSeed(testMnemonic)
        aliceKeyStore.initializeFromSeed(aliceSeed, 0)
        val aliceCrypto = LazysodiumCryptoProvider(aliceKeyStore)

        // Capture Alice's public key NOW, before we switch keystores
        val aliceX25519PublicKey = aliceCrypto.getLocalX25519PublicKey()

        // Setup Bob (we need his keys but can't use two keystores simultaneously,
        // so we'll derive his keys manually)
        val bobSeed = Bip39.mnemonicToSeed(testMnemonic2)
        val bobKeys = FamilySafeKeyDerivation(bobSeed).deriveKeysForAccount(0)

        // Alice encrypts a message for Bob
        val plaintext = "Meet at the park at 3pm".toByteArray(Charsets.UTF_8)
        val ciphertext = aliceCrypto.encryptForRecipient(plaintext, bobKeys.x25519PublicKeyHex)

        // Ciphertext should be larger than plaintext (nonce + MAC)
        assertTrue(
            "Ciphertext should include nonce and MAC overhead",
            ciphertext.size > plaintext.size
        )

        // Now switch to Bob's keystore to decrypt
        clearKeyStore(aliceKeyStore)
        val bobSeed2 = Bip39.mnemonicToSeed(testMnemonic2)  // Re-derive since bobSeed was zeroed
        aliceKeyStore.initializeFromSeed(bobSeed2, 0)
        val bobCrypto = LazysodiumCryptoProvider(aliceKeyStore)

        // Bob decrypts the message from Alice (using Alice's public key we saved earlier)
        val decrypted = bobCrypto.decryptFromSender(ciphertext, aliceX25519PublicKey)

        assertNotNull("Decryption should succeed", decrypted)
        assertEquals(
            "Decrypted message should match original",
            String(plaintext, Charsets.UTF_8),
            String(decrypted!!, Charsets.UTF_8)
        )
    }

    @Test
    fun x25519_decryptWithWrongKey_fails() {
        // Setup Alice
        val keyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(keyStore)
        val aliceSeed = Bip39.mnemonicToSeed(testMnemonic)
        keyStore.initializeFromSeed(aliceSeed, 0)
        val aliceCrypto = LazysodiumCryptoProvider(keyStore)

        // Bob's keys
        val bobSeed = Bip39.mnemonicToSeed(testMnemonic2)
        val bobKeys = FamilySafeKeyDerivation(bobSeed).deriveKeysForAccount(0)

        // Alice encrypts for Bob
        val plaintext = "Secret message".toByteArray()
        val ciphertext = aliceCrypto.encryptForRecipient(plaintext, bobKeys.x25519PublicKeyHex)

        // Try to decrypt as Alice (wrong recipient) - use a third party's key
        val eveKeys = FamilySafeKeyDerivation(
            Bip39.mnemonicToSeed("legal winner thank year wave sausage worth useful legal winner thank yellow")
        ).deriveKeysForAccount(0)

        val decrypted = aliceCrypto.decryptFromSender(ciphertext, eveKeys.x25519PublicKeyHex)

        assertNull("Decryption with wrong key should fail", decrypted)
    }

    @Test
    fun x25519_tamperedCiphertext_failsDecryption() {
        // Setup
        val keyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(keyStore)
        val aliceSeed = Bip39.mnemonicToSeed(testMnemonic)
        keyStore.initializeFromSeed(aliceSeed, 0)
        val aliceCrypto = LazysodiumCryptoProvider(keyStore)

        val bobSeed = Bip39.mnemonicToSeed(testMnemonic2)
        val bobKeys = FamilySafeKeyDerivation(bobSeed).deriveKeysForAccount(0)

        // Encrypt
        val plaintext = "Secret message".toByteArray()
        val ciphertext = aliceCrypto.encryptForRecipient(plaintext, bobKeys.x25519PublicKeyHex)

        // Tamper with ciphertext
        val tamperedCiphertext = ciphertext.copyOf()
        tamperedCiphertext[tamperedCiphertext.size - 1] = (tamperedCiphertext.last() + 1).toByte()

        // Switch to Bob and try to decrypt
        clearKeyStore(keyStore)
        keyStore.initializeFromSeed(bobSeed, 0)
        val bobCrypto = LazysodiumCryptoProvider(keyStore)

        val alicePublicKey = FamilySafeKeyDerivation(aliceSeed).deriveKeysForAccount(0).x25519PublicKeyHex
        val decrypted = bobCrypto.decryptFromSender(tamperedCiphertext, alicePublicKey)

        assertNull("Tampered ciphertext should fail authentication", decrypted)
    }

    // =========================================================================
    // MEMBER ID DERIVATION TESTS
    // =========================================================================

    @Test
    fun memberId_derivedFromPublicKey_isCorrectFormat() {
        val keyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(keyStore)

        val seed = Bip39.mnemonicToSeed(testMnemonic)
        keyStore.initializeFromSeed(seed, 0)

        val cryptoProvider = LazysodiumCryptoProvider(keyStore)
        val publicKey = cryptoProvider.getLocalEd25519PublicKey()
        val memberId = cryptoProvider.deriveMemberId(publicKey)

        // Member ID should be 32 hex characters (16 bytes)
        assertEquals("Member ID should be 32 hex characters", 32, memberId.length)
        assertTrue(
            "Member ID should be valid hex",
            memberId.all { it in '0'..'9' || it in 'a'..'f' }
        )
    }

    @Test
    fun memberId_isDeterministic() {
        val keyStore = AndroidKeyStoreLocalKeyStore(context)
        clearKeyStore(keyStore)

        val seed = Bip39.mnemonicToSeed(testMnemonic)
        keyStore.initializeFromSeed(seed, 0)

        val cryptoProvider = LazysodiumCryptoProvider(keyStore)
        val publicKey = cryptoProvider.getLocalEd25519PublicKey()

        val memberId1 = cryptoProvider.deriveMemberId(publicKey)
        val memberId2 = cryptoProvider.deriveMemberId(publicKey)

        assertEquals("Same public key should produce same member ID", memberId1, memberId2)
    }

    @Test
    fun memberId_differentKeys_produceDifferentIds() {
        val keys1 = FamilySafeKeyDerivation(Bip39.mnemonicToSeed(testMnemonic)).deriveKeysForAccount(0)
        val keys2 = FamilySafeKeyDerivation(Bip39.mnemonicToSeed(testMnemonic2)).deriveKeysForAccount(0)

        assertNotEquals(
            "Different users should have different member IDs",
            keys1.memberId,
            keys2.memberId
        )
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    /**
     * Clear the keystore between tests to ensure isolation.
     */
    private fun clearKeyStore(keyStore: AndroidKeyStoreLocalKeyStore) {
        try {
            keyStore.destroyKeys()
        } catch (e: Exception) {
            // Ignore - keys may not exist yet
        }
    }
}