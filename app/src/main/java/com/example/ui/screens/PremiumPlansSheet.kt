package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val UpgradePink = Color(0xFFE65A9E)
private val UpgradePinkSoft = Color(0xFFFFE7F1)

enum class SubscriptionPlan(val title: String, val price: String, val billing: String) {
    MONTHLY("Monthly", "$4.99", "per month"),
    YEARLY("Yearly", "$49.99", "per year"),
    LIFETIME("Lifetime", "$89.99", "one-time payment")
}

@Composable
fun UpgradeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.testTag("upgrade_button"),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = UpgradePink, contentColor = Color.White)
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text("Upgrade", fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UsageSummaryCard(
    planName: String = "Free",
    imagesUsed: Int = 0,
    imageLimit: Int? = 3,
    pdfsUsed: Int = 0,
    pdfLimit: Int? = 0,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPremium = planName.equals("Premium", ignoreCase = true) || planName.equals("Lifetime", ignoreCase = true)
    val accent = if (isPremium) MaterialTheme.colorScheme.primary else UpgradePink
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (isPremium) MaterialTheme.colorScheme.primaryContainer else UpgradePinkSoft,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPremium) Icons.Outlined.WorkspacePremium else Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("$planName plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (planName.equals("Lifetime", true)) "Lifetime access" else if (isPremium) "Premium access active" else "Your daily allowance",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isPremium) {
                    Icon(Icons.Outlined.WorkspacePremium, contentDescription = "Premium active", tint = accent, modifier = Modifier.size(23.dp))
                } else {
                    UpgradeButton(onClick = onUpgradeClick)
                }
            }
            UsageMetric("Image conversions", imagesUsed, imageLimit) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = accent)
            }
            UsageMetric("PDF documents", pdfsUsed, pdfLimit) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = accent)
            }
            if (!isPremium) {
                TextButton(onClick = onUpgradeClick, modifier = Modifier.align(Alignment.End).testTag("usage_upgrade_button")) {
                    Text("Unlock unlimited images", color = UpgradePink, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun UsageMetric(label: String, used: Int, limit: Int?, icon: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.size(8.dp))
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (limit == null) "$used / Unlimited" else "$used / $limit today",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        val progress = if (limit == null) 0f else if (limit <= 0) 1f else (used.toFloat() / limit).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = if (limit == 0) MaterialTheme.colorScheme.outlineVariant else UpgradePink,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        if (limit == 0) {
            Text("PDF conversion requires Premium", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumPlansSheet(onDismiss: () -> Unit, onContinue: (SubscriptionPlan) -> Unit, currentPlan: String = "Free") {
    var selectedPlan by remember { mutableStateOf(SubscriptionPlan.MONTHLY) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, dragHandle = null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Choose your plan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("More pages, fewer limits.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_upgrade_sheet")) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanOption(SubscriptionPlan.MONTHLY, "Flexible access, billed monthly", selectedPlan == SubscriptionPlan.MONTHLY) { selectedPlan = SubscriptionPlan.MONTHLY }
                PlanOption(SubscriptionPlan.YEARLY, "Save about 17% compared with monthly", selectedPlan == SubscriptionPlan.YEARLY) { selectedPlan = SubscriptionPlan.YEARLY }
                PlanOption(SubscriptionPlan.LIFETIME, "Pay once. Keep Premium for life.", selectedPlan == SubscriptionPlan.LIFETIME) { selectedPlan = SubscriptionPlan.LIFETIME }
            }
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Every paid plan includes", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    FeatureLine("Unlimited image conversions")
                    FeatureLine("Unlimited PDF conversions")
                    FeatureLine("No daily conversion quotas")
                }
            }
            Button(
                onClick = { onContinue(selectedPlan) },
                modifier = Modifier.fillMaxWidth().height(54.dp).testTag("upgrade_continue_button"),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = UpgradePink, contentColor = Color.White)
            ) {
                Text("Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text(
                "Account creation and payment happen securely on our website.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PlanOption(plan: SubscriptionPlan, description: String, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) UpgradePink else MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("plan_${plan.name.lowercase()}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) UpgradePinkSoft.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(plan.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(plan.price, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(plan.billing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FeatureLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = UpgradePink, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
