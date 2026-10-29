package com.luskyle.clockintest

import org.junit.Assert.*
import org.junit.Test

class NavigationAccessTest {
    @Test fun missingAuthorizationIsNotReportedAsTemporaryDisconnection() {
        assertEquals(NavigationAccess.DISABLED, NavigationAccess.resolve(false, false))
        assertNotNull(NavigationAccess.DISABLED.issue)
    }

    @Test fun staleConnectionDoesNotOverrideRevokedAuthorization() {
        assertEquals(NavigationAccess.DISABLED, NavigationAccess.resolve(false, true))
    }

    @Test fun enabledButDisconnectedServiceDoesNotAskForFirstAuthorization() {
        assertEquals(NavigationAccess.DISCONNECTED, NavigationAccess.resolve(true, false))
        assertTrue(NavigationAccess.DISCONNECTED.issue!!.contains("已授权"))
    }

    @Test fun connectedAndAuthorizedServiceIsReady() {
        assertEquals(NavigationAccess.CONNECTED, NavigationAccess.resolve(true, true))
        assertNull(NavigationAccess.CONNECTED.issue)
    }
}
