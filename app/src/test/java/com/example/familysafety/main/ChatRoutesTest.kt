package com.example.familysafety.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRoutesTest {

    @Test
    fun `notification routes for a thread are recognised`() {
        assertTrue(ChatRoutes.isThread(ChatRoutes.GROUP_CHAT))
        assertTrue(ChatRoutes.isThread(ChatRoutes.chatDetail("52318daf")))
    }

    @Test
    fun `the conversation list and other routes are not threads`() {
        assertFalse(ChatRoutes.isThread(ChatRoutes.CONVERSATION_LIST))
        assertFalse(ChatRoutes.isThread(ChatRoutes.chatDetail("")))
        assertFalse(ChatRoutes.isThread("members"))
    }

    @Test
    fun `detail route matches the nav pattern`() {
        assertEquals("chat/conversation/abc", ChatRoutes.chatDetail("abc"))
        assertEquals("chat/conversation/{memberId}", ChatRoutes.CHAT_DETAIL)
    }
}
