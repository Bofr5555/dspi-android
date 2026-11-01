package se.bofr.dspi.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test
    fun asksOnceThenOnlyWhileAndroidWouldStillShowTheDialog() {
        assertTrue(shouldAskNotificationPermission(granted = false, askedBefore = false, rationale = false)) // first launch
        assertTrue(shouldAskNotificationPermission(granted = false, askedBefore = true, rationale = true)) // denied once
        assertFalse(shouldAskNotificationPermission(granted = false, askedBefore = true, rationale = false)) // permanent denial
        assertFalse(shouldAskNotificationPermission(granted = true, askedBefore = false, rationale = false))
    }
}
