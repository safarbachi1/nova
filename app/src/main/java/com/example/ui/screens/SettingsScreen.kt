package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalAppStrings
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.ThemeMode
import com.example.domain.model.VpnProtocol
import com.example.ui.components.Formatters
import com.example.ui.components.NovaGlassCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalNovaColors
import com.example.ui.viewmodel.NovaVpnViewModel

@Composable
fun SettingsScreen(
    viewModel: NovaVpnViewModel,
    onOpenSpeedTest: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val context = LocalContext.current

    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val totalBytes by viewModel.totalDataBytes.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()

    var dnsPrimary by remember(prefs.customDnsPrimary) { mutableStateOf(prefs.customDnsPrimary) }
    var dnsSecondary by remember(prefs.customDnsSecondary) { mutableStateOf(prefs.customDnsSecondary) }
    var timeoutSlider by remember(prefs.connectionTimeoutSec) { mutableFloatStateOf(prefs.connectionTimeoutSec.toFloat()) }
    var showAppPicker by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = strings.settingsTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 1. Connection & Tunnel Settings
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(strings.sectionConnection)
                    SettingSwitchRow(
                        title = strings.autoConnect,
                        subtitle = strings.autoConnectDesc,
                        checked = prefs.autoConnect,
                        onCheckedChange = { viewModel.setAutoConnect(it) },
                        tag = "setting_auto_connect"
                    )
                    HorizontalDivider(color = nova.glassBorder.copy(alpha = 0.35f))
                    SettingSwitchRow(
                        title = strings.reconnectOnNetworkChange,
                        subtitle = strings.reconnectOnNetworkChangeDesc,
                        checked = prefs.reconnectOnNetworkChange,
                        onCheckedChange = { viewModel.setReconnectOnNetworkChange(it) },
                        tag = "setting_reconnect_network"
                    )
                    HorizontalDivider(color = nova.glassBorder.copy(alpha = 0.35f))
                    SettingSwitchRow(
                        title = strings.killSwitch,
                        subtitle = strings.killSwitchDesc,
                        checked = prefs.killSwitchEnabled,
                        onCheckedChange = { viewModel.setKillSwitchEnabled(it) },
                        tag = "setting_kill_switch"
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("open_system_vpn_settings_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.openSystemVpnSettings)
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(strings.preferredProtocol, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = prefs.preferredProtocol == null,
                                onClick = { viewModel.setPreferredProtocol(null) },
                                label = { Text(strings.modeAuto) }
                            )
                        }
                        val selectableProtocols = listOf(
                            VpnProtocol.WIREGUARD,
                            VpnProtocol.VLESS,
                            VpnProtocol.VMESS,
                            VpnProtocol.TROJAN,
                            VpnProtocol.HYSTERIA2,
                            VpnProtocol.TUIC,
                            VpnProtocol.SHADOWSOCKS,
                            VpnProtocol.OPENVPN
                        )
                        items(selectableProtocols) { proto ->
                            FilterChip(
                                selected = prefs.preferredProtocol == proto,
                                onClick = { viewModel.setPreferredProtocol(proto) },
                                label = { Text(proto.displayName) }
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.connectionTimeout, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${timeoutSlider.toInt()} ${strings.secondsUnit}",
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                            color = nova.accentCyan
                        )
                    }
                    Slider(
                        value = timeoutSlider,
                        onValueChange = { timeoutSlider = it },
                        onValueChangeFinished = { viewModel.setConnectionTimeout(timeoutSlider.toInt()) },
                        valueRange = 5f..45f
                    )
                }
            }

            // 2. Network, DNS, IPv4/IPv6 & Split Tunneling
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(strings.sectionNetwork)
                    SettingSwitchRow(
                        title = strings.enableIpv4,
                        subtitle = "172.19.0.1/30 → 0.0.0.0/0",
                        checked = prefs.ipv4Enabled,
                        onCheckedChange = { viewModel.setIpv4Enabled(it) },
                        tag = "setting_ipv4"
                    )
                    SettingSwitchRow(
                        title = strings.enableIpv6,
                        subtitle = "fdfe:dcba:9876::1/126 → ::/0",
                        checked = prefs.ipv6Enabled,
                        onCheckedChange = { viewModel.setIpv6Enabled(it) },
                        tag = "setting_ipv6"
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dnsPrimary,
                            onValueChange = { dnsPrimary = it },
                            label = { Text(strings.customDnsPrimary) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("dns_primary_input")
                        )
                        OutlinedTextField(
                            value = dnsSecondary,
                            onValueChange = { dnsSecondary = it },
                            label = { Text(strings.customDnsSecondary) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("dns_secondary_input")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.setCustomDns(dnsPrimary, dnsSecondary) },
                        modifier = Modifier.align(Alignment.End).testTag("save_dns_button")
                    ) {
                        Text(strings.save)
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = nova.glassBorder.copy(alpha = 0.35f))
                    Spacer(Modifier.height(10.dp))
                    Text(strings.splitTunneling, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        strings.splitTunnelingDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SplitTunnelMode.entries) { mode ->
                            val label = when (mode) {
                                SplitTunnelMode.DISABLED -> if (strings.isPersian) "غیرفعال" else "Disabled"
                                SplitTunnelMode.BYPASS_SELECTED -> if (strings.isPersian) "دور زدن انتخابی" else "Bypass Selected"
                                SplitTunnelMode.ONLY_SELECTED -> if (strings.isPersian) "فقط برنامه‌های انتخابی" else "Only Selected"
                            }
                            FilterChip(
                                selected = prefs.splitTunnelMode == mode,
                                onClick = { viewModel.setSplitTunnelMode(mode) },
                                label = { Text(label) }
                            )
                        }
                    }
                    if (prefs.splitTunnelMode != SplitTunnelMode.DISABLED) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showAppPicker = true },
                            modifier = Modifier.fillMaxWidth().testTag("per_app_picker_button")
                        ) {
                            Icon(Icons.Rounded.Apps, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("${strings.perAppRouting} (${prefs.splitTunnelPackages.size})")
                        }
                    }
                }
            }

            // 3. Automation & Data Usage Statistics
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(strings.sectionAutomation)
                    SettingSwitchRow(
                        title = strings.autoServerRefresh,
                        subtitle = "${strings.refreshIntervalHours}: ${prefs.refreshIntervalHours} ${strings.hoursUnit}",
                        checked = prefs.autoServerRefresh,
                        onCheckedChange = { viewModel.setAutoServerRefresh(it) },
                        tag = "setting_auto_refresh"
                    )
                    if (prefs.autoServerRefresh) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf(1, 3, 6, 12, 24)) { hrs ->
                                FilterChip(
                                    selected = prefs.refreshIntervalHours == hrs,
                                    onClick = { viewModel.setRefreshIntervalHours(hrs) },
                                    label = { Text("$hrs ${strings.hoursUnit}") }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = nova.glassBorder.copy(alpha = 0.35f))
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(strings.dataUsageStats, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = Formatters.formatBytes(totalBytes),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                            color = nova.statusConnected,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 4. Appearance & Language
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(strings.sectionAppearance)
                    Text(strings.languageLabel, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.languageCode == "fa",
                            onClick = { viewModel.setLanguageCode("fa") },
                            label = { Text("فارسی (RTL)") },
                            modifier = Modifier.testTag("lang_fa_chip")
                        )
                        FilterChip(
                            selected = prefs.languageCode == "en",
                            onClick = { viewModel.setLanguageCode("en") },
                            label = { Text("English (LTR)") },
                            modifier = Modifier.testTag("lang_en_chip")
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(strings.themeLabel, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val themes = listOf(
                            ThemeMode.DARK_NAVY to (if (strings.isPersian) "سرمه‌ای تیره" else "Dark Navy"),
                            ThemeMode.MIDNIGHT_VIOLET to (if (strings.isPersian) "بنفش شب" else "Midnight"),
                            ThemeMode.LIGHT to (if (strings.isPersian) "روشن" else "Light"),
                            ThemeMode.SYSTEM to (if (strings.isPersian) "سیستم" else "System")
                        )
                        items(themes) { (mode, label) ->
                            FilterChip(
                                selected = prefs.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // 5. Privacy, Diagnostics & Secondary Tools
            item {
                NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(strings.sectionSecurity)
                    SettingSwitchRow(
                        title = strings.requireUntrustedApproval,
                        subtitle = strings.requireUntrustedApprovalDesc,
                        checked = prefs.requireUntrustedApproval,
                        onCheckedChange = { viewModel.setRequireUntrustedApproval(it) },
                        tag = "setting_untrusted_approval"
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenSpeedTest,
                            modifier = Modifier.fillMaxWidth().testTag("settings_speedtest_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Speed, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.speedTest)
                        }
                        OutlinedButton(
                            onClick = onOpenHistory,
                            modifier = Modifier.fillMaxWidth().testTag("settings_history_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.History, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.connectionHistory)
                        }
                        OutlinedButton(
                            onClick = onOpenDiagnostics,
                            modifier = Modifier.fillMaxWidth().testTag("settings_diagnostics_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.BugReport, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.diagnostics)
                        }
                        OutlinedButton(
                            onClick = onOpenAppInfo,
                            modifier = Modifier.fillMaxWidth().testTag("settings_app_info_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Info, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.appInfo)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { viewModel.clearHistory() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(strings.clearHistory)
                            }
                            TextButton(
                                onClick = { viewModel.clearLogs() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(strings.clearLogs)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (showAppPicker) {
        LaunchedEffect(Unit) { viewModel.loadLaunchableApps() }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text(strings.perAppRouting) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(installedApps, key = { it.packageName }) { app ->
                        val checked = app.packageName in prefs.splitTunnelPackages
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.toggleSplitTunnelPackage(app.packageName) }
                            )
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAppPicker = false }) { Text(strings.close) }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    val nova = LocalNovaColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = nova.accentCyan,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}
