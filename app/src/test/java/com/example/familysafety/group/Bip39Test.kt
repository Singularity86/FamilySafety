package com.example.familysafety.group

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Bip39Test {

    companion object {
        // 2048 unique words required by BIP-39 (indices 0..2047 used as 11-bit values)
        val TEST_WORDLIST: List<String> = (0 until 2048).map { "word%04d".format(it) }
    }

    @Before
    fun injectWordlist() {
        val field = Bip39::class.java.getDeclaredField("wordlist")
        field.isAccessible = true
        field.set(Bip39, TEST_WORDLIST)
    }

    // ── generate12WordMnemonic ───────────────────────────────────

    @Test
    fun `generate12WordMnemonic returns exactly 12 words`() {
        val mnemonic = Bip39.generate12WordMnemonic()
        assertEquals(12, mnemonic.size)
    }

    @Test
    fun `generate12WordMnemonic all words are from the wordlist`() {
        val mnemonic = Bip39.generate12WordMnemonic()
        val wordSet = TEST_WORDLIST.toSet()
        mnemonic.forEach { word ->
            assertTrue("'$word' not in wordlist", word in wordSet)
        }
    }

    @Test
    fun `generate12WordMnemonic produces different results on successive calls`() {
        // With 128 bits of entropy the probability of collision is astronomically small
        val m1 = Bip39.generate12WordMnemonic()
        val m2 = Bip39.generate12WordMnemonic()
        assertNotEquals(m1, m2)
    }

    @Test
    fun `generate12WordMnemonic word indices are within valid BIP39 range`() {
        val mnemonic = Bip39.generate12WordMnemonic()
        mnemonic.forEach { word ->
            val index = TEST_WORDLIST.indexOf(word)
            assertTrue("Index $index out of range", index in 0 until 2048)
        }
    }

    // ── mnemonicToSeed ───────────────────────────────────────────

    @Test
    fun `mnemonicToSeed returns 64-byte seed`() {
        val seed = Bip39.mnemonicToSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        assertEquals(64, seed.size)
    }

    @Test
    fun `mnemonicToSeed is deterministic for same input`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed1 = Bip39.mnemonicToSeed(mnemonic)
        val seed2 = Bip39.mnemonicToSeed(mnemonic)
        assertArrayEquals(seed1, seed2)
    }

    @Test
    fun `mnemonicToSeed different passphrase produces different seed`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed1 = Bip39.mnemonicToSeed(mnemonic, passphrase = "")
        val seed2 = Bip39.mnemonicToSeed(mnemonic, passphrase = "TREZOR")
        assertFalse(seed1.contentEquals(seed2))
    }

    @Test
    fun `mnemonicToSeed normalizes extra whitespace`() {
        val mnemonic = "abandon  abandon   abandon"
        val normalized = "abandon abandon abandon"
        // Both should produce same seed since whitespace is normalized
        val seed1 = Bip39.mnemonicToSeed(mnemonic)
        val seed2 = Bip39.mnemonicToSeed(normalized)
        assertArrayEquals(seed1, seed2)
    }

    @Test
    fun `mnemonicToSeed normalizes uppercase to lowercase`() {
        val lower = "abandon abandon abandon"
        val upper = "ABANDON ABANDON ABANDON"
        val seed1 = Bip39.mnemonicToSeed(lower)
        val seed2 = Bip39.mnemonicToSeed(upper)
        assertArrayEquals(seed1, seed2)
    }

    // ── validateMnemonic ─────────────────────────────────────────

    @Test
    fun `validateMnemonic returns true when all words are in wordlist`() {
        val mnemonic = "word0000 word0001 word0002 word0003 word0004 word0005 word0006 word0007 word0008 word0009 word0010 word0011"
        assertTrue(Bip39.validateMnemonic(mnemonic))
    }

    @Test
    fun `validateMnemonic returns false when a word is not in wordlist`() {
        val mnemonic = "word0000 notaword word0002"
        assertFalse(Bip39.validateMnemonic(mnemonic))
    }

    @Test
    fun `validateMnemonic is case-insensitive`() {
        val mnemonic = "WORD0000 WORD0001 WORD0002"
        // words are lowercased before lookup, so "word0000" must be in TEST_WORDLIST
        assertTrue(Bip39.validateMnemonic(mnemonic))
    }

    @Test
    fun `getWordlist throws when not initialized`() {
        // Clear wordlist via reflection
        val field = Bip39::class.java.getDeclaredField("wordlist")
        field.isAccessible = true
        field.set(Bip39, null)

        try {
            Bip39.generate12WordMnemonic()
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not initialized") == true)
        } finally {
            // Restore so other tests are not affected
            field.set(Bip39, TEST_WORDLIST)
        }
    }
}
