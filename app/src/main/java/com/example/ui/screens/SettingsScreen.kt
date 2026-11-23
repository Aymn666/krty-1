package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.KartiSettings
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavyCard
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.MidnightBg

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
                                    text = "يستخدم التطبيق محرك ML Kit Text Recognition v2 (Latin) المدمج محلياً على الجهاز (On-Device)، لذا لا يحتاج اتصال إنترنت لقراءة الأرقام والحروف، مع تصويت ذكي عبر عدة إطارات (Frames) لتصحيح أخطاء القراءة.",
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
