package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalAppStrings
import com.example.core.security.NetworkSecurityValidator
import com.example.data.local.entity.SourceEntity
import com.example.domain.model.SourceType
import com.example.ui.components.NovaGlassCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalNovaColors
import com.example.ui.viewmodel.NovaVpnViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiscoveryScreen(viewModel: NovaVpnViewModel) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current

    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val discoveryUi by viewModel.discoveryUi.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(
                        text = strings.discoveryTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = strings.discoverySubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Security Policy Banner
            item {
                NovaGlassCard(
                    borderColor = nova.statusWarning.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            tint = nova.statusWarning,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = strings.europePriorityNote,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = strings.discoverySecurityBanner,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Action Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.refreshAllSources() },
                        enabled = !discoveryUi.isRefreshing,
                        modifier = Modifier.weight(1f).testTag("refresh_all_sources_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (discoveryUi.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(strings.refreshAllSources, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.testTag("add_source_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.addSource)
                    }
                }
            }

            // Active Progress or Last Summary
            if (discoveryUi.isRefreshing) {
                item {
                    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = discoveryUi.currentSourceName.ifBlank { strings.refreshAllSources },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                discoveryUi.lastSummary?.let { summary ->
                    item {
                        NovaGlassCard(
                            borderColor = nova.accentCyan.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (strings.isPersian) {
                                    "نتیجه همگام‌سازی: ${summary.totalParsed} مورد تحلیل شد • ${summary.savedValidated} سرور اروپایی ذخیره شد • ${summary.reachableCount} سرور در دسترس"
                                } else {
                                    "Sync summary: ${summary.totalParsed} parsed • ${summary.savedValidated} European saved • ${summary.reachableCount} reachable"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = nova.accentCyan
                            )
                        }
                    }
                }
            }

            // Sources Allowlist
            if (sources.isEmpty()) {
                item {
                    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(strings.emptySourcesBody, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(sources, key = { it.id }) { source ->
                    SourceAllowlistCard(
                        source = source,
                        isBusy = discoveryUi.isRefreshing,
                        onToggle = { enabled -> viewModel.setSourceEnabled(source.id, enabled) },
                        onRefresh = { viewModel.refreshSingleSource(source) },
                        onDelete = { viewModel.deleteSource(source.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, type ->
                viewModel.addDiscoverySource(name, url, type)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun SourceAllowlistCard(
    source: SourceEntity,
    isBusy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val updatedStr = remember(source.lastUpdatedAt) {
        if (source.lastUpdatedAt <= 0L) strings.neverUpdated
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(source.lastUpdatedAt))
    }

    NovaGlassCard(modifier = Modifier.fillMaxWidth().testTag("source_card_${source.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = nova.accentBlue, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = source.sourceType.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = nova.accentCyan
                    )
                }
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("source_switch_${source.id}")
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = source.sourceUrl,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { source.reliabilityScore.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp)
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "${strings.reliability}: ${(source.reliabilityScore * 100).toInt()}% • ${strings.discoveredCountLabel}: ${source.discoveredCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${strings.lastUpdate}: $updatedStr",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(
                    onClick = onRefresh,
                    enabled = !isBusy,
                    modifier = Modifier.testTag("source_refresh_${source.id}")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = strings.refreshAllSources, tint = nova.accentBlue)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("source_delete_${source.id}")
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = strings.remove, tint = nova.statusError)
                }
            }
        }

        if (source.lastError.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = source.lastError,
                style = MaterialTheme.typography.labelMedium,
                color = nova.statusError
            )
        }
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, SourceType) -> Unit
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var type by remember { mutableStateOf(SourceType.SUBSCRIPTION) }
    val validation = remember(url) { NetworkSecurityValidator.validateHttpsSourceUrl(url) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addSource) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.sourceNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("source_name_input")
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(strings.sourceUrlLabel) },
                    supportingText = {
                        Text(
                            if (validation.isSuccess) strings.httpsOnlyHint
                            else validation.exceptionOrNull()?.message ?: strings.httpsOnlyHint
                        )
                    },
                    isError = url.length > 8 && validation.isFailure,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("source_url_input")
                )
                Text(strings.sourceTypeLabel, style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SourceType.entries) { st ->
                        FilterChip(
                            selected = type == st,
                            onClick = { type = st },
                            label = { Text(st.displayName) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, url, type) },
                enabled = validation.isSuccess,
                modifier = Modifier.testTag("confirm_add_source_button")
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}
