package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalAppStrings
import com.example.domain.model.ConnectionState
import com.example.domain.model.EuropeanCountries
import com.example.domain.model.SelectionMode
import com.example.ui.components.Formatters
import com.example.ui.components.MetricTile
import com.example.ui.components.NovaGlassCard
import com.example.ui.components.OrbitalConnectButton
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.TrustBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalNovaColors
import com.example.ui.theme.SpaceGroteskFamily
import com.example.ui.viewmodel.NovaVpnViewModel

@Composable
fun HomeDashboardScreen(
    viewModel: NovaVpnViewModel,
    onOpenServers: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    onOpenConnectionDetails: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val stats by viewModel.statistics.collectAsStateWithLifecycle()
    val selected by viewModel.selectedServer.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val publicIp by viewModel.publicIp.collectAsStateWithLifecycle()
    val vpnExitIp by viewModel.vpnExitIp.collectAsStateWithLifecycle()
    val history by viewModel.connectionHistory.collectAsStateWithLifecycle()

    val statusColor = when (connectionState) {
        ConnectionState.CONNECTED -> nova.statusConnected
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> nova.accentCyan
        ConnectionState.DISCONNECTING -> nova.statusWarning
        ConnectionState.ERROR -> nova.statusError
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Brand Top Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(listOf(nova.accentBlue, nova.accentViolet))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VpnKey,
                                contentDescription = strings.appName,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "NOVA VPN",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = strings.tagline,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = onOpenSpeedTest,
                            modifier = Modifier.testTag("home_speedtest_icon")
                        ) {
                            Icon(Icons.Rounded.Speed, contentDescription = strings.speedTest, tint = nova.accentCyan)
                        }
                        IconButton(
                            onClick = onOpenDiagnostics,
                            modifier = Modifier.testTag("home_diagnostics_icon")
                        ) {
                            Icon(Icons.Rounded.BugReport, contentDescription = strings.diagnostics, tint = nova.accentViolet)
                        }
                    }
                }
            }

            // 2. Hero Connect Orbital Zone + State Pill
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
                        modifier = Modifier.testTag("connection_status_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = strings.connectionStateLabel(connectionState),
                                style = MaterialTheme.typography.labelLarge,
                                color = statusColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    OrbitalConnectButton(
                        state = connectionState,
                        onClick = { viewModel.onHeroConnectToggle() }
                    )
                }
            }

            // 3. Explicit Error Recovery Banner (when in ERROR state)
            item {
                AnimatedVisibility(visible = lastError != null) {
                    lastError?.let { err ->
                        NovaGlassCard(
                            borderColor = nova.statusError.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth().testTag("home_error_card")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = nova.statusError,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = strings.errorMessage(err.type),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = nova.statusError,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (err.technicalDetail.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = err.technicalDetail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (err.suggestedFallbackServerId != null) {
                                    Button(
                                        onClick = { viewModel.tryFallbackServer() },
                                        colors = ButtonDefaults.buttonColors(containerColor = nova.accentBlue),
                                        modifier = Modifier.testTag("try_fallback_server_button")
                                    ) {
                                        Text(strings.tryNextServer)
                                    }
                                }
                                OutlinedButton(onClick = { viewModel.dismissError() }) {
                                    Text(strings.close)
                                }
                            }
                        }
                    }
                }
            }

            // 4. Selected Server & Quick / Auto Selection Card
            item {
                NovaGlassCard(
                    modifier = Modifier.fillMaxWidth().testTag("selected_server_card"),
                    onClick = onOpenServers
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.selectedServer,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = { viewModel.applySmartSelection(SelectionMode.AUTO) },
                                label = { Text(strings.autoSelect, maxLines = 1) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.testTag("home_auto_select_chip")
                            )
                            AssistChip(
                                onClick = onOpenServers,
                                label = { Text(strings.quickSelect, maxLines = 1) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                modifier = Modifier.testTag("home_quick_select_chip")
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val current = selected
                    if (current == null) {
                        Text(
                            text = strings.noServerSelected,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = strings.tapToChooseServer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = EuropeanCountries.flagFor(current.server.countryCode),
                                fontSize = 32.sp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = EuropeanCountries.localizedName(
                                        current.server.countryCode,
                                        current.server.countryName,
                                        strings.isPersian
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = current.effectiveTitle,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ProtocolBadge(current.server.protocol)
                                    TrustBadge(
                                        trusted = current.configuration?.trustedSource == true,
                                        approved = current.configuration?.userApprovedUntrusted == true
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = Formatters.formatLatency(current.server.latency),
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    color = if (current.server.latency in 1..160) nova.statusConnected else nova.accentCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = strings.latency,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 5. Public IP & VPN Exit IP Card
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.publicIp,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = publicIp ?: strings.notDetected,
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.vpnExitIp,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (connectionState == ConnectionState.CONNECTED) {
                                        vpnExitIp ?: strings.notDetected
                                    } else "—",
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    color = if (connectionState == ConnectionState.CONNECTED) nova.statusConnected else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.refreshPublicIp() },
                            modifier = Modifier.testTag("refresh_ip_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = strings.refreshIp, tint = nova.accentBlue)
                        }
                    }
                }
            }

            // 6. Live Traffic & Session Telemetry 2x2 Grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            icon = Icons.Rounded.Download,
                            label = strings.downloadSpeed,
                            value = Formatters.formatSpeedBps(stats.downloadSpeedBps),
                            accent = nova.accentCyan,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            icon = Icons.Rounded.Upload,
                            label = strings.uploadSpeed,
                            value = Formatters.formatSpeedBps(stats.uploadSpeedBps),
                            accent = nova.accentViolet,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            icon = Icons.Rounded.Timer,
                            label = strings.sessionDuration,
                            value = Formatters.formatDuration(stats.durationMs),
                            accent = nova.accentBlue,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            icon = Icons.Rounded.DataUsage,
                            label = strings.totalDataUsage,
                            value = Formatters.formatBytes(stats.bytesReceived + stats.bytesSent),
                            accent = nova.statusConnected,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 7. Secondary Pages Quick Navigation Row
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        QuickShortcutChip(
                            icon = Icons.Rounded.Speed,
                            label = strings.speedTest,
                            tag = "shortcut_speed_test",
                            onClick = onOpenSpeedTest
                        )
                    }
                    item {
                        QuickShortcutChip(
                            icon = Icons.Rounded.Info,
                            label = strings.connectionDetails,
                            tag = "shortcut_connection_details",
                            onClick = onOpenConnectionDetails
                        )
                    }
                    item {
                        QuickShortcutChip(
                            icon = Icons.Rounded.Favorite,
                            label = strings.favorites,
                            tag = "shortcut_favorites",
                            onClick = onOpenFavorites
                        )
                    }
                    item {
                        QuickShortcutChip(
                            icon = Icons.Rounded.History,
                            label = strings.connectionHistory,
                            tag = "shortcut_history",
                            onClick = onOpenHistory
                        )
                    }
                }
            }

            // 8. Recent Connection History Snapshot
            if (history.isNotEmpty()) {
                item {
                    NovaGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenHistory
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.connectionHistory,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(Icons.Rounded.History, contentDescription = null, tint = nova.accentBlue)
                        }
                        Spacer(Modifier.height(8.dp))
                        history.take(3).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Text(EuropeanCountries.flagFor(entry.countryCode), fontSize = 18.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = entry.serverLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                ProtocolBadge(entry.protocol)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = Formatters.formatDuration(entry.duration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun QuickShortcutChip(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.testTag(tag)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
