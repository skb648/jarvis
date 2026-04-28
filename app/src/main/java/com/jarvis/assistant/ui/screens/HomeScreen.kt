package com.jarvis.assistant.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassmorphicButtonCard
import com.jarvis.assistant.ui.components.GlassmorphicCardSimple
import com.jarvis.assistant.ui.orb.BrainState
import com.jarvis.assistant.ui.orb.HolographicOrb
import com.jarvis.assistant.ui.theme.*
import java.time.LocalTime

/**
 * Quick action definition.
 */
data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val description: String
)

@Composable
fun HomeScreen(
    brainState: BrainState,
    audioAmplitude: Float,
    deviceCount: Int,
    activeDeviceCount: Int,
    onQuickAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val greeting = remember { getGreeting() }
    val orbColor by animateColorAsState(
        targetValue = brainState.color,
        animationSpec = tween(600),
        label = "orb-color"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // ── Greeting ────────────────────────────────────────────────
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = TextPrimary
            )
        )
        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = TextSecondary
            )
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── Holographic Orb ─────────────────────────────────────────
        HolographicOrb(
            brainState = brainState,
            size = 250.dp,
            amplitude = audioAmplitude
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── State Label ─────────────────────────────────────────────
        Text(
            text = brainState.label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = orbColor
            )
        )
        Text(
            text = brainState.description,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextSecondary
            )
        )

        Spacer(modifier = Modifier.height(28.dp))

        // ── Quick Action Row ────────────────────────────────────────
        val quickActions = listOf(
            QuickAction("Voice", Icons.Filled.Mic, "Start voice command"),
            QuickAction("Capture", Icons.Filled.PhotoCamera, "Capture screen"),
            QuickAction("Chat", Icons.AutoMirrored.Filled.Chat, "Open conversation"),
            QuickAction("Devices", Icons.Filled.Devices, "Smart home")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            quickActions.forEach { action ->
                GlassmorphicButtonCard(
                    onClick = { onQuickAction(action.label.lowercase()) },
                    modifier = Modifier.size(72.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.label,
                            tint = JarvisCyan,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Device Status Summary Card ──────────────────────────────
        GlassmorphicCardSimple(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Home",
                    tint = JarvisGreen,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Smart Home",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "$activeDeviceCount active / $deviceCount total devices",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary
                        )
                    )
                }
                Icon(
                    imageVector = if (activeDeviceCount > 0) Icons.Filled.CheckCircle else Icons.Filled.OfflineBolt,
                    contentDescription = "Status",
                    tint = if (activeDeviceCount > 0) SuccessGreen else TextTertiary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── System Status Card ──────────────────────────────────────
        GlassmorphicCardSimple(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = "AI",
                    tint = JarvisPurple,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "JARVIS Core",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "AI engine operational · Rust native",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary
                        )
                    )
                }
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Online",
                    tint = SuccessGreen,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun getGreeting(): String {
    val hour = LocalTime.now().hour
    return when {
        hour < 6   -> "Good night,"
        hour < 12  -> "Good morning,"
        hour < 17  -> "Good afternoon,"
        hour < 21  -> "Good evening,"
        else       -> "Good night,"
    }
}
