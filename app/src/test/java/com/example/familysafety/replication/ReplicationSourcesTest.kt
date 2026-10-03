package com.example.familysafety.replication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplicationSourcesTest {

    private val peers = listOf("alice", "bob", "carol")

    @Test
    fun `a member's history is requested from that member alone`() {
        assertEquals(listOf("bob"), ReplicationManager.locationSourcesFor("bob", peers))
    }

    @Test
    fun `our own history is requested from every peer`() {
        assertEquals(peers, ReplicationManager.locationSourcesFor("me", peers))
    }

    @Test
    fun `a direct chat is requested from the other participant`() {
        assertEquals(listOf("carol"), ReplicationManager.chatSourcesFor("carol:me", peers))
        assertEquals(listOf("alice"), ReplicationManager.chatSourcesFor("alice:me", peers))
    }

    @Test
    fun `a private chat with someone who left is requested from nobody`() {
        assertEquals(emptyList<String>(), ReplicationManager.chatSourcesFor("dave:me", peers))
    }

    @Test
    fun `only participants may hold a private conversation`() {
        assertTrue(ReplicationManager.mayHoldConversation("alice:bob", "alice"))
        assertTrue(ReplicationManager.mayHoldConversation("alice:bob", "bob"))
        assertFalse(ReplicationManager.mayHoldConversation("alice:bob", "carol"))
    }

    @Test
    fun `anyone may hold the group chat`() {
        assertTrue(ReplicationManager.mayHoldConversation("e8dfaa6f-415c-4226-98d2-4859edc4f272", "carol"))
        assertNull(ReplicationManager.directParticipants("e8dfaa6f-415c-4226-98d2-4859edc4f272"))
    }

    @Test
    fun `the group chat is requested from everyone`() {
        assertEquals(peers, ReplicationManager.chatSourcesFor("e8dfaa6f-415c-4226-98d2-4859edc4f272", peers))
    }
}
