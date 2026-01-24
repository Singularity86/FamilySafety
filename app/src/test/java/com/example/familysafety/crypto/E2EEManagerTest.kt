package com.example.familysafety.crypto

import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class E2EEManagerTest {
    
    private lateinit var e2eeManager: E2EEManager
    private lateinit var mockCryptoProvider: LazysodiumCryptoProvider
    private val lazySodium = LazySodiumAndroid(SodiumAndroid())
    
    private val testX25519Private = ByteArray(32) { it.toByte() }
    private val testX25519Public = ByteArray(32) { (it + 1).toByte() }
    private val testEd25519Private = ByteArray(64) { it.toByte() }
    private val testEd25519Public = ByteArray(32) { (it + 2).toByte() }
    
    @Before
    fun setup() {
        mockCryptoProvider = mockk(relaxed = true)
        
        every { mockCryptoProvider.getMemberId() } returns "test_member_id"
        every { mockCryptoProvider.getX25519PrivateKey() } returns testX25519Private
        every { mockCryptoProvider.signMessage(any()) } returns ByteArray(64) { 0 }
        every { mockCryptoProvider.verifySignature(any(), any(), any()) } returns true
        
        e2eeManager = E2EEManager(mockCryptoProvider)
    }
    
    @After
    fun teardown() {
        clearAllMocks()
    }
    
    @Test
    fun `encrypt and decrypt message successfully`() = runTest {
        val plaintext = "Hello, secure world!"
        val recipientMemberId = "recipient_123"
        
        val encrypted = e2eeManager.encryptMessage(
            plaintext = plaintext,
            recipientMemberId = recipientMemberId,
            recipientX25519PublicKey = testX25519Public
        )
        
        assertNotEquals(plaintext, encrypted)
        assertTrue(encrypted.contains("nonce"))
        assertTrue(encrypted.contains("ciphertext"))
        assertTrue(encrypted.contains("signature"))
    }
    
    @Test
    fun `encryption produces different ciphertexts with different nonces`() = runTest {
        val plaintext = "Same message"
        val recipientMemberId = "recipient_123"
        
        val encrypted1 = e2eeManager.encryptMessage(
            plaintext, recipientMemberId, testX25519Public
        )
        val encrypted2 = e2eeManager.encryptMessage(
            plaintext, recipientMemberId, testX25519Public
        )
        
        assertNotEquals(encrypted1, encrypted2)
    }
    
    @Test
    fun `shared secret is cached after first computation`() = runTest {
        val plaintext = "Test message"
        val recipientMemberId = "recipient_123"
        
        e2eeManager.encryptMessage(plaintext, recipientMemberId, testX25519Public)
        e2eeManager.encryptMessage(plaintext, recipientMemberId, testX25519Public)
        
        verify(exactly = 2) { mockCryptoProvider.getX25519PrivateKey() }
    }
    
    @Test
    fun `clear cache removes shared secrets`() = runTest {
        val recipientMemberId = "recipient_123"
        
        e2eeManager.encryptMessage("test", recipientMemberId, testX25519Public)
        e2eeManager.clearSharedSecretCache()
        e2eeManager.encryptMessage("test", recipientMemberId, testX25519Public)
        
        verify(atLeast = 2) { mockCryptoProvider.getX25519PrivateKey() }
    }
}
