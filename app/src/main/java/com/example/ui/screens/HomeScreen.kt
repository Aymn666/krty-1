package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.service.AutoFillStatus
import com.example.ui.MainUiState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavyCard
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MidnightBg

@Composable
fun HomeScreen(
    uiState: MainUiState,
    onRequestCameraPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAppDetailsSettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenInAppScanner: () -> Unit,
    onOpenPortalWithScanner: () -> Unit,
    onStartOverlayScanner: () -> Unit,
    onToggleFloatingBubble: () -> Unit,
    onCopyCode: (String) -> Unit,
    onOpenCaptivePortal: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAccessibilityGuide by remember { mutableStateOf(false) }
    var showOverlayGuide by remember { mutableStateOf(false) }
    var showOverlayBlockedDialog by remember { mutableStateOf(false) }

    if (showOverlayBlockedDialog) {
        OverlayBlockedDialog(
            overlaySupported = uiState.permissions.overlaySupported,
            onOpenAppDetails = {
                showOverlayBlockedDialog = false
                onOpenAppDetailsSettings()
            },
            onRetryOverlaySettings = {
                showOverlayBlockedDialog = false
                onOpenOverlaySettings()
            },
            onUseDirectPortalScanner = {
                showOverlayBlockedDialog = false
                onOpenPortalWithScanner()
            },
            onDismiss = { showOverlayBlockedDialog = false }
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hero Banner Card
            item {
                HeroBannerCard(
                    allReady = uiState.permissions.allRecommendedGranted,
                    detectedNetwork = uiState.detectedNetwork,
                    isBubbleRunning = uiState.isBubbleRunning
                )
            }

            // Primary Quick Actions
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!uiState.permissions.cameraGranted) {
                                    onRequestCameraPermission()
                                } else {
                                    onOpenInAppScanner()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("scan_card_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = MidnightBg
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("مسح كرت الآن", fontWeight = FontWeight.Bold)
                        }

                        FilledTonalButton(
                            onClick = {
                                if (!uiState.isBubbleRunning &&
                                    !uiState.permissions.overlayGranted &&
                                    (uiState.hasAttemptedOverlayGrant || !uiState.permissions.overlaySupported)
                                ) {
                                    showOverlayBlockedDialog = true
                                } else {
                                    onToggleFloatingBubble()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("toggle_bubble_button"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.BubbleChart, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isBubbleRunning) "إخفاء العائمة" else "إظهار العائمة",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = onOpenPortalWithScanner,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("launch_portal_scanner_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Default.OpenInBrowser,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("فتح بوابة الشبكة والمسح مباشرة", fontWeight = FontWeight.SemiBold)
                    }

                    if (uiState.permissions.cameraGranted && uiState.permissions.overlayGranted) {
                        OutlinedButton(
                            onClick = onStartOverlayScanner,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("launch_overlay_scanner_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = null,
                                tint = CyanSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فتح نافذة الكاميرا العائمة فوق التطبيقات")
                        }
                    }
                }
            }

            // Active Dispatch / Fallback Card
            item {
                val status = uiState.autoFillStatus
                val code = uiState.pendingCode
                AnimatedVisibility(visible = status !is AutoFillStatus.Idle || code != null) {
                    DispatchStatusCard(
                        status = status,
                        pendingCode = code,
                        onCopy = { c -> onCopyCode(c) },
                        onOpenPortal = onOpenCaptivePortal,
                        onEnableAccessibility = onOpenAccessibilitySettings
                    )
                }
            }

            // Permissions Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "حالة الصلاحيات والخدمات",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (uiState.permissions.allRecommendedGranted) {
                            EmeraldPrimary.copy(alpha = 0.14f)
                        } else {
                            AmberWarning.copy(alpha = 0.14f)
                        }
                    ) {
                        Text(
                            text = if (uiState.permissions.allRecommendedGranted) "مكتمل" else "يتطلب تفعيل",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (uiState.permissions.allRecommendedGranted) EmeraldPrimary else AmberWarning,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 5 Permission Cards
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionItemCard(
                        title = "الكاميرا (Camera)",
                        description = "لمسح أرقام كروت مايكروتك محلياً دون إنترنت",
                        icon = Icons.Default.CameraAlt,
                        isGranted = uiState.permissions.cameraGranted,
                        buttonTag = "perm_camera_button",
                        onActivate = onRequestCameraPermission
                    )

                    PermissionItemCard(
                        title = "الظهور فوق التطبيقات (Overlay)",
                        description = if (!uiState.permissions.overlaySupported) {
                            "غير مدعوم على هذا النظام — استخدم زر المسح المباشر بالبوابة"
                        } else {
                            "لعرض الأيقونة العائمة ونافذة المسح فوق صفحة البوابة"
                        },
                        icon = Icons.Default.Layers,
                        isGranted = uiState.permissions.overlayGranted,
                        buttonTag = "perm_overlay_button",
                        onActivate = {
                            if (!uiState.permissions.overlaySupported) {
                                showOverlayBlockedDialog = true
                            } else {
                                onOpenOverlaySettings()
                            }
                        },
                        secondaryActionLabel = if (!uiState.permissions.overlayGranted) {
                            "حل مشكلة إطفاء الزر"
                        } else {
                            null
                        },
                        onSecondaryAction = { showOverlayGuide = !showOverlayGuide }
                    )

                    AnimatedVisibility(
                        visible = !uiState.permissions.overlayGranted &&
                            (showOverlayGuide || uiState.hasAttemptedOverlayGrant || !uiState.permissions.overlaySupported)
                    ) {
                        OverlayGuideCard(
                            overlaySupported = uiState.permissions.overlaySupported,
                            onOpenAppDetails = onOpenAppDetailsSettings,
                            onOpenOverlaySettings = onOpenOverlaySettings,
                            onOpenPortalWithScanner = onOpenPortalWithScanner
                        )
                    }

                    PermissionItemCard(
                        title = "إمكانية الوصول (Accessibility)",
                        description = "لملء رقم الكرت والضغط على زر تسجيل الدخول تلقائياً",
                        icon = Icons.Default.AccessibilityNew,
                        isGranted = uiState.permissions.accessibilityGranted,
                        buttonTag = "perm_accessibility_button",
                        onActivate = onOpenAccessibilitySettings,
                        secondaryActionLabel = "خطوات التفعيل",
                        onSecondaryAction = { showAccessibilityGuide = !showAccessibilityGuide }
                    )

                    AnimatedVisibility(visible = showAccessibilityGuide || !uiState.permissions.accessibilityGranted) {
                        AccessibilityGuideCard(
                            onOpenSettings = onOpenAccessibilitySettings,
                            onOpenAppDetails = onOpenAppDetailsSettings,
                            onOpenPortalFallback = onOpenCaptivePortal
                        )
                    }

                    PermissionItemCard(
                        title = "راصد إشعارات الشبكة",
                        description = "لاكتشاف إشعار «تسجيل الدخول إلى شبكة Wi-Fi» تلقائياً",
                        icon = Icons.Default.NotificationsActive,
                        isGranted = uiState.permissions.notificationListenerGranted,
                        buttonTag = "perm_notif_listener_button",
                        onActivate = onOpenNotificationListenerSettings
                    )

                    PermissionItemCard(
                        title = "الإشعارات (Notifications)",
                        description = "لتشغيل خدمة الأيقونة العائمة في المقدمة بسلاسة",
                        icon = Icons.Default.NotificationsNone,
                        isGranted = uiState.permissions.notificationsGranted,
                        buttonTag = "perm_post_notif_button",
                        onActivate = onRequestNotificationPermission
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBannerCard(
    allReady: Boolean,
    detectedNetwork: String,
    isBubbleRunning: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, EmeraldPrimary.copy(alpha = 0.28f), RoundedCornerShape(22.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_scanner_hero),
            contentDescription = "ماسح كروت الواي فاي الذكي",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MidnightBg.copy(alpha = 0.92f),
                            MidnightBg.copy(alpha = 0.65f),
                            Color.Transparent
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = (if (allReady) EmeraldPrimary else AmberWarning).copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (allReady) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = if (allReady) EmeraldPrimary else AmberWarning,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (allReady) "جاهز للعمل التلقائي" else "أكمل تفعيل الصلاحيات",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (allReady) EmeraldPrimary else AmberWarning
                        )
                    }
                }

                if (isBubbleRunning) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = CyanSecondary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "العائمة نشطة",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "كرتي — ماسح كروت الواي فاي",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = if (detectedNetwork.isNotBlank()) {
                        "آخر شبكة مرصودة: $detectedNetwork"
                    } else {
                        "قراءة فورية بدون إنترنت + ملء تلقائي لبوابات مايكروتك"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun DispatchStatusCard(
    status: AutoFillStatus,
    pendingCode: String?,
    onCopy: (String) -> Unit,
    onOpenPortal: () -> Unit,
    onEnableAccessibility: () -> Unit
) {
    val activeCode = when (status) {
        is AutoFillStatus.Pending -> status.code
        is AutoFillStatus.FilledAndSubmitted -> status.code
        is AutoFillStatus.FilledOnly -> status.code
        is AutoFillStatus.PortalNotFound -> status.code
        is AutoFillStatus.AccessibilityDisabled -> status.code
        AutoFillStatus.Idle -> pendingCode
    } ?: return

    val isSuccess = status is AutoFillStatus.FilledAndSubmitted
    val accent = if (isSuccess) EmeraldPrimary else AmberWarning

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = ElevatedNavyCard),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isSuccess) Icons.Default.Wifi else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = accent
                )
                Text(
                    text = when (status) {
                        is AutoFillStatus.FilledAndSubmitted -> "تمت تعبئة الكود وتسجيل الدخول تلقائياً"
                        is AutoFillStatus.FilledOnly -> "تم إدخال الكود في الحقل — اضغط دخول من البوابة"
                        is AutoFillStatus.PortalNotFound -> "الكود منسوخ — افتح صفحة تسجيل الدخول لإدخاله"
                        is AutoFillStatus.AccessibilityDisabled -> "إمكانية الوصول معطلة — تم نسخ الكود للحافظة"
                        else -> "كود جاهز للإدخال التلقائي"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = activeCode,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 2.sp,
                    color = Color.White
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onCopy(activeCode) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("status_copy_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        contentColor = MidnightBg
                    )
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("نسخ الكود", fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = onOpenPortal,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("status_open_portal_button")
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("فتح البوابة")
                }
            }

            if (status is AutoFillStatus.AccessibilityDisabled) {
                OutlinedButton(
                    onClick = onEnableAccessibility,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("تفعيل التعبئة التلقائية من الإعدادات")
                }
            }
        }
    }
}

@Composable
private fun PermissionItemCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    buttonTag: String,
    onActivate: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DeepNavySurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGranted) EmeraldPrimary.copy(alpha = 0.16f)
                        else AmberWarning.copy(alpha = 0.14f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isGranted) EmeraldPrimary else AmberWarning
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(secondaryActionLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (isGranted) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = EmeraldPrimary.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "مفعل",
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "مفعل",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldPrimary
                        )
                    }
                }
            } else {
                Button(
                    onClick = onActivate,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag(buttonTag),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanSecondary,
                        contentColor = MidnightBg
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Text("تفعيل", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OverlayGuideCard(
    overlaySupported: Boolean,
    onOpenAppDetails: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenPortalWithScanner: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AmberWarning.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = ElevatedNavyCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (overlaySupported) {
                    "لماذا يرجع زر «الظهور فوق التطبيقات» للإطفاء؟"
                } else {
                    "الظهور فوق التطبيقات غير مدعوم على هذا الجهاز"
                },
                style = MaterialTheme.typography.titleMedium,
                color = AmberWarning
            )
            Text(
                text = if (overlaySupported) {
                    "في أندرويد 13–15 وبيئة المعاينة، يمنع النظام تفعيل زر الظهور للتطبيقات المثبتة خارج المتجر فيعود للإطفاء تلقائياً:\n" +
                        "1. اضغط «معلومات التطبيق» بالأسفل.\n" +
                        "2. اضغط الثلاث نقاط (⋮) في أعلى الزاوية واختر «السماح بالإعدادات المقيدة».\n" +
                        "3. ارجع لصفحة «الظهور فوق التطبيقات» وفعّل المفتاح.\n" +
                        "بديل فوري: يعمل التطبيق بالكامل بدون هذه الصلاحية عبر زر «فتح البوابة والمسح»."
                } else {
                    "نظام هذا الجهاز (ذاكرة محدودة) يعطّل نوافذ الظهور فوق التطبيقات تلقائياً. يمكنك استخدام زر «فتح البوابة والمسح» أو إشعار كرتي التلقائي للعمل بدونها."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenAppDetails,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("guide_open_app_details_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberWarning,
                        contentColor = MidnightBg
                    )
                ) {
                    Text("معلومات التطبيق", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onOpenPortalWithScanner,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("guide_open_portal_scanner_button")
                ) {
                    Text("البوابة والمسح")
                }
            }
            if (overlaySupported) {
                TextButton(
                    onClick = onOpenOverlaySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("guide_retry_overlay_button")
                ) {
                    Text("إعادة المحاولة في إعدادات الظهور", color = CyanSecondary)
                }
            }
        }
    }
}

@Composable
private fun OverlayBlockedDialog(
    overlaySupported: Boolean,
    onOpenAppDetails: () -> Unit,
    onRetryOverlaySettings: () -> Unit,
    onUseDirectPortalScanner: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepNavySurface,
        titleContentColor = Color.White,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(
                text = if (overlaySupported) "تفعيل الإعدادات المقيدة للظهور" else "الظهور العائم غير مدعوم",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = if (overlaySupported) {
                    "إذا كان مفتاح «الظهور فوق التطبيقات» ينطفئ تلقائياً في الإعدادات، افتح «معلومات التطبيق» ← الثلاث نقاط (⋮) ← «السماح بالإعدادات المقيدة»، أو افتح البوابة والمسح مباشرة دون الحاجة للأيقونة العائمة."
                } else {
                    "هذا الجهاز يمنع نوافذ الظهور فوق التطبيقات. يمكنك فتح البوابة والمسح مباشرة بكامل المزايا."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onUseDirectPortalScanner,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = MidnightBg
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("dialog_open_portal_scanner_button")
            ) {
                Text("فتح البوابة والمسح الآن", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onOpenAppDetails,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("dialog_open_app_details_button")
                ) {
                    Text("معلومات التطبيق", color = AmberWarning)
                }
                if (overlaySupported) {
                    TextButton(
                        onClick = onRetryOverlaySettings,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("dialog_retry_overlay_button")
                    ) {
                        Text("الإعدادات", color = CyanSecondary)
                    }
                }
            }
        }
    )
}

@Composable
private fun AccessibilityGuideCard(
    onOpenSettings: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenPortalFallback: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyanSecondary.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = ElevatedNavyCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "دليل تفعيل التعبئة التلقائية (Accessibility)",
                style = MaterialTheme.typography.titleMedium,
                color = CyanSecondary
            )
            Text(
                text = "1. اضغط «فتح إعدادات إمكانية الوصول» بالأسفل.\n" +
                    "2. اختر «التطبيقات المثبتة» أو «الخدمات المثبتة».\n" +
                    "3. اختر «كرتي — التعبئة التلقائية» وفعّل المفتاح.\n" +
                    "ملاحظة أندرويد 13+: إذا ظهر خيار محظور، افتح معلومات التطبيق ← الثلاث نقاط ← السماح بالإعدادات المقيدة.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("guide_open_accessibility_button")
                ) {
                    Text("فتح الإعدادات")
                }
                OutlinedButton(
                    onClick = onOpenAppDetails,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("guide_accessibility_app_details_button")
                ) {
                    Text("معلومات التطبيق")
                }
            }
            OutlinedButton(
                onClick = onOpenPortalFallback,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("فتح بوابة الدخول")
            }
        }
    }
}
