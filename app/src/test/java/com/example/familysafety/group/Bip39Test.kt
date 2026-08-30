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

    // These need the real wordlist: a checksum is a fact about the actual BIP-39 indices,
    // and the synthetic word0000..word2047 list cannot express one. The vectors below are
    // the canonical all-zero-entropy phrases from the BIP-39 spec.

    private val realWordlist: List<String> by lazy {
        val file = java.io.File("src/main/res/raw/bip39_english.txt")
        assertTrue("real wordlist not found at ${file.absolutePath}", file.exists())
        file.readLines().filter { it.isNotBlank() }
    }

    private fun withRealWordlist(block: () -> Unit) {
        val field = Bip39::class.java.getDeclaredField("wordlist")
        field.isAccessible = true
        field.set(Bip39, realWordlist)
        try {
            block()
        } finally {
            field.set(Bip39, TEST_WORDLIST)
        }
    }

    private val valid12 = "abandon ".repeat(11) + "about"
    private val valid18 = "abandon ".repeat(17) + "agent"
    private val valid24 = "abandon ".repeat(23) + "art"

    @Test
    fun `validateMnemonic accepts the canonical 12, 18 and 24 word phrases`() = withRealWordlist {
        assertTrue(Bip39.validateMnemonic(valid12))
        assertTrue(Bip39.validateMnemonic(valid18))
        assertTrue(Bip39.validateMnemonic(valid24))
    }

    @Test
    fun `validateMnemonic rejects a phrase whose checksum does not match`() = withRealWordlist {
        // The canonical phrase with its last word replaced. Every word is real and the
        // count is right; only the checksum says otherwise — which is exactly the case a
        // typo produces, and exactly what the old implementation accepted.
        assertFalse(Bip39.validateMnemonic("abandon ".repeat(11) + "abandon"))
        assertFalse(Bip39.validateMnemonic("abandon ".repeat(11) + "zoo"))
    }

    @Test
    fun `validateMnemonic rejects words in the wrong order`() = withRealWordlist {
        val words = valid24.split(" ").toMutableList()
        words[0] = words[23].also { words[23] = words[0] }
        assertFalse(Bip39.validateMnemonic(words.joinToString(" ")))
    }

    @Test
    fun `validateMnemonic rejects lengths BIP-39 does not define`() = withRealWordlist {
        assertFalse("three words is not a phrase", Bip39.validateMnemonic("abandon abandon abandon"))
        assertFalse(Bip39.validateMnemonic("abandon ".repeat(9).trim()))          // 9
        assertFalse(Bip39.validateMnemonic("abandon ".repeat(10) + "about"))      // 11
        assertFalse(Bip39.validateMnemonic("abandon ".repeat(12) + "about"))      // 13
        assertFalse(Bip39.validateMnemonic(""))
    }

    @Test
    fun `validateMnemonic returns false when a word is not in wordlist`() = withRealWordlist {
        assertFalse(Bip39.validateMnemonic("abandon ".repeat(10) + "notaword about"))
    }

    @Test
    fun `validateMnemonic is case-insensitive`() = withRealWordlist {
        assertTrue(Bip39.validateMnemonic(valid12.uppercase()))
    }

    @Test
    fun `a generated phrase always validates`() {
        // Round trip against the synthetic list: generation and validation have to agree
        // about where entropy stops and the checksum starts, whatever the wordlist is.
        repeat(20) {
            assertTrue(Bip39.validateMnemonic(Bip39.generate12WordMnemonic().joinToString(" ")))
        }
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
