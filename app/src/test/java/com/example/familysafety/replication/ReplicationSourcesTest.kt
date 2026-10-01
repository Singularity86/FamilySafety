package com.example.familysafety.replication

import org.junit.Assert.assertEquals
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
    fun `the group chat is requested from everyone`() {
        assertEquals(peers, ReplicationManager.chatSourcesFor("e8dfaa6f-415c-4226-98d2-4859edc4f272", peers))
    }
}
