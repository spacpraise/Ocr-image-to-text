package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    displayName: String?,
    email: String?,
    onBack: () -> Unit,
    onUpgradeClick: () -> Unit,
    onSignInMethodClick: () -> Unit,
    onChangePasswordClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onDeleteAccountClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    val name = displayName?.takeIf { it.isNotBlank() } ?: "Your account"
    val address = email?.takeIf { it.isNotBlank() } ?: "No email connected"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, titleContentColor = MaterialTheme.colorScheme.onBackground)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            ProfileAvatar(name, 112)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(address, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CURRENT PLAN", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Free", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Button(onClick = onUpgradeClick, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
                        Text("Explore Pro", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AccountGroup {
                    AccountRow(Icons.Outlined.Email, "Sign-in method", "Email", onSignInMethodClick)
                    AccountDivider()
                    AccountRow(Icons.Outlined.Key, "Change password", null, onChangePasswordClick)
                }
                AccountGroup {
                    AccountRow(Icons.Outlined.Logout, "Sign out", null, onSignOutClick)
                    AccountDivider()
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { confirmDelete = true }.padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(46.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text("Delete account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete account?") },
            text = { Text("Account deletion is not connected in the app yet.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteAccountClick() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AccountGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(content = content)
    }
}

@Composable
private fun AccountDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), thickness = 1.dp)
}

@Composable
private fun AccountRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, trailing: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(46.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(23.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
    }
}
