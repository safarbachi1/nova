package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.i18n.LocalAppStrings
import com.example.domain.model.ConnectionState
import com.example.domain.model.VpnProtocol
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalNovaColors
import com.example.ui.theme.SpaceGroteskFamily
import java.util.Locale

@Composable
fun NovaAtmosphericBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val nova = LocalNovaColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(nova.canvasTop, nova.canvasBottom)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        nova.accentBlue.copy(alpha = if (nova.isDark) 0.16f else 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.2f, size.height * 0.12f),
                    radius = size.minDimension * 0.85f
                ),
                radius = size.minDimension * 0.85f,
                center = Offset(size.width * 0.2f, size.height * 0.12f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        nova.accentViolet.copy(alpha = if (nova.isDark) 0.14f else 0.07f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.35f),
                    radius = size.minDimension * 0.75f
                ),
                radius = size.minDimension * 0.75f,
                center = Offset(size.width * 0.85f, size.height * 0.35f)
            )
        }
        content()
    }
}

@Composable
fun NovaGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val nova = LocalNovaColors.current
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = nova.glassSurface,
        border = BorderStroke(1.dp, borderColor ?: nova.glassBorder.copy(alpha = 0.7f)),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun OrbitalConnectButton(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nova = LocalNovaColors.current
    val strings = LocalAppStrings.current
    val infinite = rememberInfiniteTransition(label = "orbital")

    val sweepAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) 1400 else 5600,
                easing = LinearEasing
            )
        ),
        label = "sweep"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val coreScale by animateFloatAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> 1.03f
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> 0.97f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 320f),
        label = "coreScale"
    )

    val ringPrimary by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> nova.statusConnected
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> nova.accentCyan
            ConnectionState.DISCONNECTING -> nova.statusWarning
            ConnectionState.ERROR -> nova.statusError
            ConnectionState.DISCONNECTED -> nova.accentBlue
        },
        label = "ringPrimary"
    )
    val ringSecondary by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> nova.accentCyan
            ConnectionState.ERROR -> nova.statusWarning
            else -> nova.accentViolet
        },
        label = "ringSecondary"
    )

    val actionLabel = when (state) {
        ConnectionState.CONNECTED -> strings.disconnect
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> strings.cancel
        ConnectionState.ERROR -> strings.retry
        else -> strings.connect
    }

    Box(
        modifier = modifier
            .size(216.dp)
            .testTag("connect_hero_button")
            .semantics { contentDescription = actionLabel },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerR = (size.minDimension / 2f) * 0.96f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ringPrimary.copy(alpha = 0.28f), Color.Transparent),
                    center = center,
                    radius = outerR
                ),
                radius = outerR,
                center = center
            )

            // Outer orbital track
            val trackRadius = size.minDimension * 0.43f
            drawCircle(
                color = ringPrimary.copy(alpha = 0.20f),
                radius = trackRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // Sweeping glowing arc
            val arcInset = size.minDimension * 0.07f
            val arcSize = size.minDimension - arcInset * 2f
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Color.Transparent, ringPrimary, ringSecondary, Color.Transparent)
                ),
                startAngle = sweepAngle,
                sweepAngle = if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) 170f else 110f,
                useCenter = false,
                topLeft = Offset(arcInset, arcInset),
                size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Tactile core button
        Box(
            modifier = Modifier
                .size(148.dp)
                .scale(coreScale)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            ringPrimary.copy(alpha = if (state == ConnectionState.DISCONNECTED) 0.90f else 1f),
                            ringSecondary
                        )
                    )
                )
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(134.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                nova.canvasTop.copy(alpha = 0.88f),
                                nova.canvasBottom.copy(alpha = 0.96f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (state == ConnectionState.CONNECTED) Icons.Rounded.Shield else Icons.Rounded.PowerSettingsNew,
                        contentDescription = actionLabel,
                        tint = ringPrimary,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ProtocolBadge(
    protocol: VpnProtocol,
    modifier: Modifier = Modifier
) {
    val nova = LocalNovaColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = nova.accentBlue.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, nova.accentBlue.copy(alpha = 0.45f))
    ) {
        Text(
            text = protocol.displayName,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            ),
            color = if (nova.isDark) Color(0xFF9AC2FF) else nova.accentBlue,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun TrustBadge(
    trusted: Boolean,
    approved: Boolean,
    modifier: Modifier = Modifier
) {
    val nova = LocalNovaColors.current
    val strings = LocalAppStrings.current
    val (label, color, icon) = when {
        trusted -> Triple(strings.trustedBadge, nova.statusConnected, Icons.Rounded.Lock)
        approved -> Triple(strings.approvedBadge, nova.accentCyan, Icons.Rounded.Shield)
        else -> Triple(strings.untrustedBadge, nova.statusWarning, Icons.Rounded.WarningAmber)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1
            )
        }
    }
}

@Composable
fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    monoValue: Boolean = true
) {
    val nova = LocalNovaColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = nova.glassSurface,
        border = BorderStroke(1.dp, nova.glassBorder.copy(alpha = 0.65f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = if (monoValue) {
                    MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily)
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

object Formatters {
    private const val LTR = "\u200E"

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        val raw = when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
        return "$LTR$raw$LTR"
    }

    fun formatSpeedBps(bytesPerSec: Long): String {
        val b = bytesPerSec.coerceAtLeast(0L)
        val raw = when {
            b < 1024L -> "$b B/s"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", b / 1024.0)
            else -> String.format(Locale.US, "%.2f MB/s", b / (1024.0 * 1024.0))
        }
        return "$LTR$raw$LTR"
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return "$LTR${String.format(Locale.US, "%02d:%02d:%02d", h, m, s)}$LTR"
    }

    fun formatLatency(ms: Int): String = if (ms > 0) "$LTR$ms ms$LTR" else "—"
}
