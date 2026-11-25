package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalAppStrings
import com.example.domain.model.EuropeanCountries
import com.example.domain.model.LogLevel
import com.example.ui.components.Formatters
import com.example.ui.components.MetricTile
import com.example.ui.components.NovaGlassCard
import com.example.ui.components.ProtocolBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalNovaColors
import com.example.ui.viewmodel.NovaVpnViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun SecondaryTopBar(title: String, onBack: () -> Unit) {
    val strings = LocalAppStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("secondary_back_button")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
        }
        Spacer(Modifier.width(4.dp))
        Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SpeedTestScreen(viewModel: NovaVpnViewModel, onBack: () -> Unit) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val ui by viewModel.speedTestUi.collectAsStateWithLifecycle()
    val history by viewModel.speedTestHistory.collectAsStateWithLifecycle()

    val latest = history.firstOrNull()
    val dl = ui.liveProgress?.currentDownloadMbps ?: latest?.downloadMbps ?: 0f
    val ul = ui.liveProgress?.currentUploadMbps ?: latest?.uploadMbps ?: 0f
    val latency = ui.liveProgress?.latencyMs ?: latest?.latencyMs ?: 0
    val jitter = ui.liveProgress?.jitterMs ?: latest?.jitterMs ?: 0
    val loss = ui.liveProgress?.packetLossPercent ?: latest?.packetLossPercent ?: 0f
    val stability = latest?.stabilityScore ?: 0f

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SecondaryTopBar(strings.speedTestTitle, onBack) }

            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = strings.speedTestNotice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.startSpeedTest() },
                        enabled = !ui.isRunning,
                        modifier = Modifier.fillMaxWidth().testTag("start_speed_test_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (ui.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.runningSpeedTest)
                        } else {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.startSpeedTest)
                        }
                    }
                    if (ui.isRunning) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { ui.liveProgress?.progressFraction ?: 0.1f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ui.lastError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(err, style = MaterialTheme.typography.labelMedium, color = nova.statusError)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(
                            icon = Icons.Rounded.Download,
                            label = strings.downloadSpeed,
                            value = String.format(Locale.US, "%.2f Mbps", dl),
                            accent = nova.accentCyan,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            icon = Icons.Rounded.Upload,
                            label = strings.uploadSpeed,
                            value = String.format(Locale.US, "%.2f Mbps", ul),
                            accent = nova.accentViolet,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(
                            icon = Icons.Rounded.NetworkPing,
                            label = strings.latency,
                            value = if (latency > 0) "$latency ms" else "—",
                            accent = nova.accentBlue,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            icon = Icons.Rounded.GraphicEq,
                            label = strings.jitter,
                            value = if (latency > 0) "$jitter ms" else "—",
                            accent = nova.statusWarning,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile(
                            icon = Icons.Rounded.ErrorOutline,
                            label = strings.packetLoss,
                            value = String.format(Locale.US, "%.1f%%", loss),
                            accent = if (loss > 5f) nova.statusError else nova.statusConnected,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            icon = Icons.Rounded.Shield,
                            label = strings.stability,
                            value = if (stability > 0f) String.format(Locale.US, "%.0f%%", stability) else "—",
                            accent = nova.statusConnected,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                Text(strings.speedHistory, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            if (history.isEmpty()) {
                item {
                    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(strings.noSpeedHistory, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(history, key = { it.id }) { item ->
                    val ts = remember(item.testedAt) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.testedAt))
                    }
                    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(item.serverLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(ts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = String.format(
                                Locale.US,
                                "DL %.2f Mbps • UL %.2f Mbps • %d ms (jitter %d ms) • Loss %.1f%%",
                                item.downloadMbps, item.uploadMbps, item.latencyMs, item.jitterMs, item.packetLossPercent
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = nova.accentCyan
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionDetailsScreen(viewModel: NovaVpnViewModel, onBack: () -> Unit) {
    val strings = LocalAppStrings.current
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val selected by viewModel.selectedServer.collectAsStateWithLifecycle()
    val stats by viewModel.statistics.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val publicIp by viewModel.publicIp.collectAsStateWithLifecycle()
    val exitIp by viewModel.vpnExitIp.collectAsStateWithLifecycle()
    val activeEngine by viewModel.activeEngineType.collectAsStateWithLifecycle()
    val reconnects by viewModel.reconnectAttempts.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SecondaryTopBar(strings.connectionDetails, onBack) }
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Status", strings.connectionStateLabel(state))
                    DetailRow(strings.activeProtocol, selected?.server?.protocol?.displayName ?: "—")
                    DetailRow("Engine", activeEngine?.name ?: selected?.server?.protocol?.primaryEngine?.name ?: "—")
                    DetailRow("Endpoint", selected?.let { "${it.server.hostname}:${it.server.port}" } ?: "—")
                    DetailRow(strings.publicIp, publicIp ?: strings.notDetected)
                    DetailRow(strings.vpnExitIp, exitIp ?: "—")
                    DetailRow(strings.sessionDuration, Formatters.formatDuration(stats.durationMs))
                    DetailRow(strings.downloadSpeed, "${Formatters.formatSpeedBps(stats.downloadSpeedBps)} (${Formatters.formatBytes(stats.bytesReceived)})")
                    DetailRow(strings.uploadSpeed, "${Formatters.formatSpeedBps(stats.uploadSpeedBps)} (${Formatters.formatBytes(stats.bytesSent)})")
                    DetailRow("DNS", "${prefs.customDnsPrimary} / ${prefs.customDnsSecondary}")
                    DetailRow("IPv4 / IPv6", "${if (prefs.ipv4Enabled) "ON" else "OFF"} / ${if (prefs.ipv6Enabled) "ON" else "OFF"}")
                    DetailRow(strings.splitTunneling, "${prefs.splitTunnelMode.name} (${prefs.splitTunnelPackages.size})")
                    DetailRow("Reconnect Attempts", reconnects.toString())
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
            fontWeight = FontWeight.SemiBold
        )
    }
    HorizontalDivider(color = LocalNovaColors.current.glassBorder.copy(alpha = 0.28f))
}

@Composable
fun ConnectionHistoryScreen(viewModel: NovaVpnViewModel, onBack: () -> Unit) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val history by viewModel.connectionHistory.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
                        }
                        Text(strings.connectionHistory, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = strings.clearHistory, tint = nova.statusError)
                        }
                    }
                }
            }

            if (history.isEmpty()) {
                item {
                    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (strings.isPersian) "هنوز سابقه‌ای ثبت نشده است." else "No connection sessions recorded yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(history, key = { it.id }) { entry ->
                    val ts = remember(entry.connectedAt) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(entry.connectedAt))
                    }
                    val ok = entry.connectionResult != "FAILED"
                    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(EuropeanCountries.flagFor(entry.countryCode), fontSize = 22.sp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(entry.serverLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(ts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            ProtocolBadge(entry.protocol)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${entry.connectionResult} • ${Formatters.formatDuration(entry.duration)} • ↓${Formatters.formatBytes(entry.bytesReceived)} ↑${Formatters.formatBytes(entry.bytesSent)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = if (ok) nova.statusConnected else nova.statusError
                        )
                        if (entry.errorDetail.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(entry.errorDetail, style = MaterialTheme.typography.labelMedium, color = nova.statusError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(viewModel: NovaVpnViewModel, onBack: () -> Unit) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val context = LocalContext.current
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val engines = remember { viewModel.engineRuntimeInfos() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SecondaryTopBar(strings.diagnostics, onBack) }

            item {
                Text(strings.engineStatusTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            items(engines, key = { it.name }) { info ->
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(info.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${info.version} • ${info.license}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (info.isCoreAvailable) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (info.isCoreAvailable) nova.statusConnected else nova.statusWarning
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (strings.isPersian) info.statusNoteFa else info.statusNoteEn,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.liveLogsTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("NovaVPN Logs", viewModel.exportRedactedLogs()))
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.copyLogs)
                        }
                        IconButton(onClick = { viewModel.clearLogs() }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = strings.clearLogs, tint = nova.statusError)
                        }
                    }
                }
            }

            if (logs.isEmpty()) {
                item {
                    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(strings.noLogsYet, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(logs.asReversed()) { entry ->
                    val lvlColor = when (entry.level) {
                        LogLevel.ERROR -> nova.statusError
                        LogLevel.WARN -> nova.statusWarning
                        LogLevel.ENGINE -> nova.accentCyan
                        LogLevel.INFO -> nova.accentBlue
                    }
                    val ts = remember(entry.timestampMs) {
                        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestampMs))
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = nova.glassSurface,
                        border = BorderStroke(1.dp, lvlColor.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 5.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(lvlColor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "$ts [${entry.tag}]",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = lvlColor
                                )
                                Text(
                                    text = entry.message,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurface
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
fun AppInfoScreen(viewModel: NovaVpnViewModel, onBack: () -> Unit) {
    val strings = LocalAppStrings.current
    val engines = remember { viewModel.engineRuntimeInfos() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SecondaryTopBar(strings.appInfo, onBack) }
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("NOVA VPN v1.0.0", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (strings.isPersian) {
                            "کلاینت بومی اندروید با معماری Clean Architecture + MVVM، ذخیره رمزنگاری‌شده با Android Keystore و تفکیک کامل موتورهای VPN."
                        } else {
                            "Native Android multi-engine VPN client built with Clean Architecture + MVVM, Android Keystore AES-256-GCM storage, and strict engine separation."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (strings.isPersian) "موتورها و مجوزهای منبع‌باز" else "Engines & Open-Source Licenses",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    engines.forEach { e ->
                        DetailRow(e.name, "${e.license} (${e.version})")
                    }
                    DetailRow("AndroidX Compose / Room / WorkManager", "Apache-2.0")
                    DetailRow("OkHttp & kotlinx.serialization", "Apache-2.0")
                    DetailRow("Vazirmatn / Space Grotesk / JetBrains Mono", "SIL OFL 1.1")
                }
            }
        }
    }
}
