package com.example.utils

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.service.PortalAccessibilityService

data class PermissionStatus(
    val cameraGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val overlaySupported: Boolean = true,
    val notificationsGranted: Boolean = false,
    val notificationListenerGranted: Boolean = false,
    val accessibilityGranted: Boolean = false
) {
    val allCoreGranted: Boolean
        get() = cameraGranted && (overlayGranted || !overlaySupported) && notificationsGranted

    val allRecommendedGranted: Boolean
        get() = allCoreGranted && notificationListenerGranted && accessibilityGranted
}

object PermissionHelper {

    fun checkAllPermissions(context: Context): PermissionStatus {
        return PermissionStatus(
            cameraGranted = isCameraGranted(context),
            overlayGranted = isOverlayGranted(context),
            overlaySupported = isOverlaySupported(context),
            notificationsGranted = isNotificationGranted(context),
            notificationListenerGranted = isNotificationListenerGranted(context),
            accessibilityGranted = isAccessibilityServiceEnabled(context)
        )
    }

    fun isCameraGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isOverlayGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isOverlaySupported(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return !(am?.isLowRamDevice ?: false)
    }

    fun isNotificationGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isNotificationListenerGranted(context: Context): Boolean {
        return NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedFull = ComponentName(context, PortalAccessibilityService::class.java).flattenToString()
        val expectedShort = ComponentName(context, PortalAccessibilityService::class.java).flattenToShortString()
        return enabledServices.split(':').any {
            it.equals(expectedFull, ignoreCase = true) ||
                it.equals(expectedShort, ignoreCase = true)
        }
    }

    private fun Intent.applyContextTaskFlag(context: Context): Intent = apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openOverlaySettings(context: Context) {
        val packageIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).applyContextTaskFlag(context)

        runCatching {
            context.startActivity(packageIntent)
        }.recoverCatching {
            val genericIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .applyContextTaskFlag(context)
            context.startActivity(genericIntent)
        }.onFailure {
            openAppDetailsSettings(context)
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .applyContextTaskFlag(context)
        runCatching { context.startActivity(intent) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .applyContextTaskFlag(context)
        runCatching { context.startActivity(intent) }
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).applyContextTaskFlag(context)
        runCatching { context.startActivity(intent) }
    }

    fun triggerSuccessVibration(context: Context) {
        runCatching {
            val effect = VibrationEffect.createOneShot(90L, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(effect)
            }
        }
    }
}
