package com.example.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.KartiSettings
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavyCard
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.ReemKufiFontFamily
import com.example.widget.KartiScanWidgetProvider

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: KartiSettings,
    onAutoFlashChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onAutoFillChange: (Boolean) -> Unit,
    onAutoClickChange: (Boolean) -> Unit,
    onSaveHistoryChange: (Boolean) -> Unit,
    onFloatingBubbleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeveloperDialog by remember { mutableStateOf(false) }

    if (showDeveloperDialog) {
        DeveloperInfoDialog(
            onDismiss = { showDeveloperDialog = false }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "إعدادات المسح والتعبئة",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DeepNavySurface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SettingSwitchRow(
                            title = "تشغيل الفلاش تلقائياً",
                            subtitle = "تفعيل إضاءة الكاميرا عند فتح نافذة المسح",
                            icon = Icons.Default.FlashOn,
                            checked = settings.autoFlashEnabled,
                            testTag = "switch_auto_flash",
                            onCheckedChange = onAutoFlashChange
                        )
                        SettingSwitchRow(
                            title = "الاهتزاز عند قراءة الكود",
                            subtitle = "تنبيه لمسي فوري عند التحقق من رقم الكرت",
                            icon = Icons.Default.Vibration,
                            checked = settings.vibrationEnabled,
                            testTag = "switch_vibration",
                            onCheckedChange = onVibrationChange
                        )
                        SettingSwitchRow(
                            title = "التعبئة التلقائية للحقل",
                            subtitle = "إدخال الكود تلقائياً داخل صفحة تسجيل الدخول",
                            icon = Icons.Default.AccessibilityNew,
                            checked = settings.autoFillEnabled,
                            testTag = "switch_auto_fill",
                            onCheckedChange = onAutoFillChange
                        )
                        SettingSwitchRow(
                            title = "الضغط التلقائي على زر الدخول",
                            subtitle = "النقر على «تسجيل الدخول / دخول الإنترنت» بعد التعبئة",
                            icon = Icons.Default.TouchApp,
                            checked = settings.autoClickEnabled,
                            testTag = "switch_auto_click",
                            onCheckedChange = onAutoClickChange
                        )
                        SettingSwitchRow(
                            title = "حفظ سجل الكروت (Room DB)",
                            subtitle = "حفظ أرقام الكروت الممسوحة محلياً على جهازك فقط",
                            icon = Icons.Default.History,
                            checked = settings.saveHistoryEnabled,
                            testTag = "switch_save_history",
                            onCheckedChange = onSaveHistoryChange
                        )
                        SettingSwitchRow(
                            title = "الأيقونة العائمة عند إشعار Wi-Fi",
                            subtitle = "إظهار الفقاعة تلقائياً عند ظهور إشعار تسجيل الدخول",
                            icon = Icons.Default.BubbleChart,
                            checked = settings.floatingBubbleEnabled,
                            testTag = "switch_floating_bubble",
                            onCheckedChange = onFloatingBubbleChange
                        )
                    }
                }
            }

            // Home Screen Widget Shortcut Pinning Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DeepNavySurface),
                    shape = RoundedCornerShape(18.dp)
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(EmeraldPrimary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Widgets,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "اختصار المسح (ويدجت الشاشة الرئيسية)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "أضف أداة المسح السريع على شاشة هاتفك لفتح الكاميرا بضغطة واحدة",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { requestPinScanWidget(context) },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("settings_pin_widget_button")
                        ) {
                            Text("إضافة", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Developer Info Button
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .border(
                            1.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    EmeraldPrimary.copy(alpha = 0.55f),
                                    CyanSecondary.copy(alpha = 0.55f)
                                )
                            ),
                            RoundedCornerShape(18.dp)
                        )
                        .clickable { showDeveloperDialog = true }
                        .testTag("settings_developer_button"),
                    color = ElevatedNavyCard,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            EmeraldPrimary.copy(alpha = 0.24f),
                                            CyanSecondary.copy(alpha = 0.24f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "معلومات المطور",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "قميشي فخم • تواصل للمشاركة في التطوير",
                                fontFamily = ReemKufiFontFamily,
                                fontSize = 14.sp,
                                color = CyanSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "فتح معلومات المطور",
                            tint = EmeraldPrimary
                        )
                    }
                }
            }

            item {
                Text(
                    text = "الشبكات المدعومة والمواصفات التقنية",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ElevatedNavyCard),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "بوابات MikroTik المتوافقة",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanSecondary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("STEP.NET", "ATAQ.NET", "SPIDER.NET", "MikroTik Hotspot").forEach { portal ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = CyanSecondary.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = portal,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CyanSecondary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "لماذا يعمل المسح بدون إنترنت؟",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = EmeraldPrimary
                                )
                                Text(
                                    text = "يستخدم التطبيق محرك ML Kit Text Recognition v2 (Latin) المدمج محلياً على الجهاز (On-Device)، لذا لا يحتاج اتصال إنترنت لقراءة الأرقام والحروف، مع معالجة ذكية للصورة وتصويت فوري لتصحيح أخطاء القراءة.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "قيد أندرويد 10+: يمنع النظام تشغيل الكاميرا في الخلفية الصامتة؛ لذلك يتم فتح الكاميرا فقط داخل النافذة العائمة التفاعلية عند ضغطك على الأيقونة.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeveloperInfoDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 480.dp)
                .border(
                    1.2.dp,
                    Brush.linearGradient(
                        listOf(
                            EmeraldPrimary.copy(alpha = 0.7f),
                            CyanSecondary.copy(alpha = 0.65f),
                            AmberWarning.copy(alpha = 0.55f)
                        )
                    ),
                    RoundedCornerShape(26.dp)
                ),
            shape = RoundedCornerShape(26.dp),
            color = DeepNavySurface,
            tonalElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Distinctive Kufi Calligraphy Signature Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF0A2438),
                                    Color(0xFF102E44),
                                    Color(0xFF0A2A2E)
                                )
                            )
                        )
                        .border(
                            1.dp,
                            EmeraldPrimary.copy(alpha = 0.35f),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(EmeraldPrimary, CyanSecondary)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MidnightBg,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Text(
                            text = "تم تطوير التطبيق بواسطة",
                            fontFamily = ReemKufiFontFamily,
                            fontSize = 15.sp,
                            color = CyanSecondary,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "\"قميشي فخم\"",
                            fontFamily = ReemKufiFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            color = EmeraldPrimary,
                            textAlign = TextAlign.Center
                        )

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = AmberWarning.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "للمشاركة في التطوير يرجاء التواصل معنا",
                                fontFamily = ReemKufiFontFamily,
                                fontSize = 13.sp,
                                color = AmberWarning,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Interactive Contact Cards
                DeveloperContactRow(
                    label = "هاتف",
                    value = "773869779",
                    icon = Icons.Default.Phone,
                    accentColor = EmeraldPrimary,
                    testTag = "dev_contact_phone",
                    onPrimaryAction = {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:773869779")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure { copyDeveloperInfo(context, "773869779") }
                    },
                    onCopy = { copyDeveloperInfo(context, "773869779") }
                )

                DeveloperContactRow(
                    label = "أيميل",
                    value = "memoony123@gmail.com",
                    icon = Icons.Default.Email,
                    accentColor = CyanSecondary,
                    testTag = "dev_contact_email",
                    onPrimaryAction = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:memoony123@gmail.com")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure { copyDeveloperInfo(context, "memoony123@gmail.com") }
                    },
                    onCopy = { copyDeveloperInfo(context, "memoony123@gmail.com") }
                )

                DeveloperContactRow(
                    label = "تيليجرام",
                    value = "@komishi_fakhm",
                    icon = Icons.AutoMirrored.Filled.Send,
                    accentColor = AmberWarning,
                    testTag = "dev_contact_telegram",
                    onPrimaryAction = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://t.me/komishi_fakhm")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure { copyDeveloperInfo(context, "@komishi_fakhm") }
                    },
                    onCopy = { copyDeveloperInfo(context, "@komishi_fakhm") }
                )

                Spacer(modifier = Modifier.height(2.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("developer_dialog_close"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        contentColor = MidnightBg
                    )
                ) {
                    Text(
                        text = "إغلاق",
                        fontFamily = ReemKufiFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DeveloperContactRow(
    label: String,
    value: String,
    icon: ImageVector,
    accentColor: Color,
    testTag: String,
    onPrimaryAction: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
            .clickable(onClick = onPrimaryAction)
            .testTag(testTag),
        color = ElevatedNavyCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontFamily = ReemKufiFontFamily,
                    fontSize = 12.sp,
                    color = accentColor
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = value,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "نسخ $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun copyDeveloperInfo(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Developer Contact", value))
    Toast.makeText(context, "تم النسخ: $value", Toast.LENGTH_SHORT).show()
}

private fun requestPinScanWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, KartiScanWidgetProvider::class.java)
    if (manager.isRequestPinAppWidgetSupported) {
        val requested = manager.requestPinAppWidget(provider, null, null)
        if (!requested) {
            Toast.makeText(
                context,
                "أضف ويدجت «كرتي» من قائمة الودجات على الشاشة الرئيسية",
                Toast.LENGTH_LONG
            ).show()
        }
    } else {
        Toast.makeText(
            context,
            "أضف ويدجت «كرتي» بالضغط مطولاً على الشاشة الرئيسية ← الودجات",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MidnightBg,
                checkedTrackColor = EmeraldPrimary
            )
        )
    }
}
