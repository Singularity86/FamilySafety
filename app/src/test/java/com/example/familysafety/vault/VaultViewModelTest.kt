package com.example.familysafety.vault

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The vault screen leaves when there is no session and nothing is opening. Opening must read
 * as busy from the moment it is asked for, or the screen's first frame leaves before the key
 * is derived (it opened once in three tries).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening is busy before openWithCode returns, and until the session is set`() = runTest(dispatcher) {
        val derived = CompletableDeferred<VaultSession?>()
        val repository = mockk<VaultRepository>(relaxed = true)
        coEvery { repository.open(any()) } coAnswers { derived.await() }
        val viewModel = VaultViewModel(repository)

        viewModel.openWithCode("correct horse battery")
        assertTrue(viewModel.busy.value)
        assertNull(viewModel.session.value)

        val session = VaultSession(key = ByteArray(32), items = emptyList())
        derived.complete(session)
        runCurrent() // not advanceUntilIdle: that would run the 3-minute idle close too

        assertEquals(session, viewModel.session.value)
        assertFalse(viewModel.busy.value)
        viewModel.close()
    }

    @Test
    fun `a failed open ends not busy and without a session, so the screen leaves`() = runTest(dispatcher) {
        val repository = mockk<VaultRepository>(relaxed = true)
        coEvery { repository.open(any()) } throws IllegalStateException("derivation failed")
        val viewModel = VaultViewModel(repository)

        viewModel.openWithCode("anything")
        advanceUntilIdle()

        assertNull(viewModel.session.value)
        assertFalse(viewModel.busy.value)
    }
}
