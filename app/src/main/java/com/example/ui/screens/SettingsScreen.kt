package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.AppThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: AppThemeMode,
    onThemeSelect: (AppThemeMode) -> Unit,
    storageInfo: String,
    onClearAllScans: () -> Unit,
    onBack: () -> Unit,
    onUpgradeClick: () -> Unit = {},
    displayName: String? = null,
    email: String? = null,
    photoUrl: String? = null,
    onAccountClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onPrivacyClick: () -> Unit = {},
    onHelpClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    var showThemePicker by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onAccountClick).testTag("settings_account_card"),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(displayName, 46, photoUrl)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(displayName?.takeIf { it.isNotBlank() } ?: "Your account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(email?.takeIf { it.isNotBlank() } ?: "Manage your account", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Open account", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("PREFERENCES")
                SettingsGroup {
                    SettingsRow(Icons.Outlined.BrightnessAuto, "Appearance", themeLabel(currentTheme)) { showThemePicker = true }
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.History, "History", null, onHistoryClick)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("ABOUT")
                SettingsGroup {
                    SettingsRow(Icons.Outlined.Info, "Version", "1.0") {}
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.Shield, "Privacy Policy", null, onPrivacyClick)
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.HelpOutline, "Help & feedback", null, onHelpClick)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text("Appearance", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    AppThemeMode.values().forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onThemeSelect(mode); showThemePicker = false }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(themeLabel(mode), modifier = Modifier.weight(1f))
                            if (currentTheme == mode) Text("✓", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemePicker = false }) { Text("Done") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), thickness = 1.dp)
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
    }
}

private fun themeLabel(mode: AppThemeMode) = when (mode) {
    AppThemeMode.SYSTEM -> "System"
    AppThemeMode.LIGHT -> "Light"
    AppThemeMode.DARK -> "Dark"
}

@Composable
fun ProfileAvatar(name: String?, size: Int, photoUrl: String? = null) {
    val initials = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.take(2)
        ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }?.joinToString("")?.takeIf { it.isNotBlank() } ?: "ST"
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            if (!photoUrl.isNullOrBlank()) {
                AsyncImage(model = photoUrl, contentDescription = "Profile photo", contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(initials, color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.SemiBold, fontSize = (size * 0.28).sp)
            }
        }
    }
}
