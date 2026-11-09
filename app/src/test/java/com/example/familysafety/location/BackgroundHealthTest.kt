package com.example.familysafety.location

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackgroundHealthTest {

    @Test
    fun `the reasons that explain a drop-off each get their own words`() {
        assertEquals("Android closed it to free memory", BackgroundHealth.describe(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(
            "Android closed it for using too much battery",
            BackgroundHealth.describe(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)
        )
        assertEquals("The phone's system closed it", BackgroundHealth.describe(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("Stopped from the phone's settings", BackgroundHealth.describe(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("It crashed", BackgroundHealth.describe(ApplicationExitInfo.REASON_CRASH))
        assertEquals("It crashed", BackgroundHealth.describe(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("Updated", BackgroundHealth.describe(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun `every reason Android defines is described`() {
        val defined = listOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED
        )
        defined.forEach { assertNotEquals("reason $it", "Unknown reason", BackgroundHealth.describe(it)) }
    }

    @Test
    fun `a code this build does not know is unknown, not a guess`() {
        assertEquals("Unknown reason", BackgroundHealth.describe(ApplicationExitInfo.REASON_UNKNOWN))
        assertEquals("Unknown reason", BackgroundHealth.describe(999))
    }
}
