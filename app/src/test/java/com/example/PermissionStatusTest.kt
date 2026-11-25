package com.example

import com.example.utils.PermissionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStatusTest {

    @Test
    fun requiresOverlayWhenSupported() {
        val status = PermissionStatus(
            cameraGranted = true,
            overlayGranted = false,
            overlaySupported = true,
            notificationsGranted = true,
            notificationListenerGranted = true,
            accessibilityGranted = true
        )
        assertFalse(status.allCoreGranted)
        assertFalse(status.allRecommendedGranted)
    }

    @Test
    fun treatsAppAsReadyWhenOverlayUnsupportedOnLowRamDevice() {
        val status = PermissionStatus(
            cameraGranted = true,
            overlayGranted = false,
            overlaySupported = false,
            notificationsGranted = true,
            notificationListenerGranted = true,
            accessibilityGranted = true
        )
        assertTrue(status.allCoreGranted)
        assertTrue(status.allRecommendedGranted)
    }
}
