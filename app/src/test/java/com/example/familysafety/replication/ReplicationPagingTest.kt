package com.example.familysafety.replication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** E7: a page that stopped at the limit is followed by a request for the next one. */
class ReplicationPagingTest {

    private val asked = ReplicationRequest(
        requestId = "r1",
        requesterId = "me",
        dataType = ReplicationDataType.LOCATION_HISTORY,
        targetMemberId = "bob",
        afterTimestamp = 1_000L,
        limit = 500
    )

    private fun next(page: Int = 1, hasMore: Boolean = true, newest: Long? = 2_000L) =
        ReplicationManager.nextPageRequest(asked, page, hasMore, newest, "r2")

    @Test
    fun `a full page asks for the rest from where it ended`() {
        val n = next()!!
        assertEquals("r2", n.requestId)
        assertEquals(2_000L, n.afterTimestamp)
        assertEquals(asked.targetMemberId, n.targetMemberId)
        assertEquals(asked.dataType, n.dataType)
        assertEquals(asked.limit, n.limit)
    }

    @Test
    fun `nothing more, nothing asked`() {
        assertNull(next(hasMore = false))
    }

    @Test
    fun `a page that didn't move forward isn't followed`() {
        assertNull(next(newest = 1_000L))
        assertNull(next(newest = 999L))
        assertNull(next(newest = null))
    }

    @Test
    fun `paging stops after the cap`() {
        assertEquals(2_000L, next(page = ReplicationManager.MAX_PAGES - 1)!!.afterTimestamp)
        assertNull(next(page = ReplicationManager.MAX_PAGES))
    }
}
