package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalAppStrings
import com.example.data.local.entity.ServerWithConfiguration
import com.example.domain.model.ValidationStatus
import com.example.ui.components.NovaGlassCard
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.TrustBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalNovaColors
import com.example.ui.viewmodel.NovaVpnViewModel
import kotlinx.coroutines.launch

@Composable
fun ConfigManagerScreen(
    viewModel: NovaVpnViewModel,
    onConnectSelected: () -> Unit
) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val servers by viewModel.servers.collectAsStateWithLifecycle()

    var showPasteDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<ServerWithConfiguration?>(null) }

    // Zero-permission Android Storage Access Framework document picker (.ovpn, .conf, .json, .yaml, .txt)
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                viewModel.importRawConfigurations(content, trustedByUser = true)
            }
        }
    }

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
                        text = strings.configsTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = strings.supportedFormatsHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Import Action Controls
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showPasteDialog = true },
                            modifier = Modifier.weight(1f).testTag("import_paste_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.importFromText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f).testTag("import_file_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.importFromFile, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    OutlinedButton(
                        onClick = { showSubscriptionDialog = true },
                        modifier = Modifier.fillMaxWidth().testTag("import_subscription_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.importSubscription)
                    }
                }
            }

            if (servers.isEmpty()) {
                item {
                    NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = nova.accentBlue, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(strings.emptyConfigsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                strings.emptyConfigsBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(servers, key = { it.server.id }) { item ->
                    ConfigurationCard(
                        item = item,
                        onConnect = {
                            viewModel.selectServer(item.server.id)
                            viewModel.requestConnect(item)
                            onConnectSelected()
                        },
                        onEdit = { editingTarget = item },
                        onExport = {
                            scope.launch {
                                val raw = viewModel.getDecryptedExportText(item.server.id) ?: return@launch
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, item.effectiveTitle)
                                    putExtra(Intent.EXTRA_TEXT, raw)
                                }
                                context.startActivity(Intent.createChooser(share, strings.exportConfig))
                            }
                        },
                        onDelete = { viewModel.deleteConfiguration(item.server.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (showPasteDialog) {
        PasteConfigDialog(
            onDismiss = { showPasteDialog = false },
            onImport = { raw, trusted ->
                viewModel.importRawConfigurations(raw, trustedByUser = trusted)
                showPasteDialog = false
            }
        )
    }

    if (showSubscriptionDialog) {
        SubscriptionImportDialog(
            onDismiss = { showSubscriptionDialog = false },
            onImport = { url ->
                viewModel.importSubscriptionUrl(url)
                showSubscriptionDialog = false
            }
        )
    }

    editingTarget?.let { target ->
        EditConfigDialog(
            item = target,
            onDismiss = { editingTarget = null },
            onSave = { name, trusted ->
                viewModel.updateConfigMetadata(target.server.id, name, trusted)
                editingTarget = null
            }
        )
    }
}

@Composable
private fun ConfigurationCard(
    item: ServerWithConfiguration,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val cfg = item.configuration

    val validationColor = when (cfg?.validationStatus) {
        ValidationStatus.VALID -> nova.statusConnected
        ValidationStatus.WARNING -> nova.statusWarning
        ValidationStatus.INVALID, ValidationStatus.UNSUPPORTED -> nova.statusError
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    NovaGlassCard(modifier = Modifier.fillMaxWidth().testTag("config_card_${item.server.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.effectiveTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.server.hostname}:${item.server.port}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = nova.accentViolet.copy(alpha = 0.16f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = nova.accentViolet, modifier = Modifier.size(12.dp))
                    Text(strings.encryptedAtRestBadge, style = MaterialTheme.typography.labelMedium, color = nova.accentViolet)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProtocolBadge(item.server.protocol)
            TrustBadge(
                trusted = cfg?.trustedSource == true,
                approved = cfg?.userApprovedUntrusted == true
            )
            if (cfg != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = validationColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = cfg.validationStatus.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = validationColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }

        val validationMsg = cfg?.validationMessage
        if (!validationMsg.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = validationMsg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onConnect,
                modifier = Modifier.testTag("config_connect_${item.server.id}")
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.connect)
            }
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.testTag("config_edit_${item.server.id}")) {
                    Icon(Icons.Rounded.Edit, contentDescription = strings.editConfig, tint = nova.accentBlue)
                }
                IconButton(onClick = onExport, modifier = Modifier.testTag("config_export_${item.server.id}")) {
                    Icon(Icons.Rounded.Share, contentDescription = strings.exportConfig, tint = nova.accentCyan)
                }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("config_delete_${item.server.id}")) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = strings.deleteConfig, tint = nova.statusError)
                }
            }
        }
    }
}

@Composable
private fun PasteConfigDialog(
    onDismiss: () -> Unit,
    onImport: (String, Boolean) -> Unit
) {
    val strings = LocalAppStrings.current
    var text by remember { mutableStateOf("") }
    var trusted by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.importFromText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(strings.pasteConfigHint) },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("paste_config_input")
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trusted, onCheckedChange = { trusted = it })
                    Text(strings.trustedSourceLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(text, trusted) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("confirm_paste_import_button")
            ) {
                Text(strings.parseAndSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
private fun SubscriptionImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    var url by remember { mutableStateOf("https://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.importSubscription) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(strings.sourceUrlLabel) },
                supportingText = { Text(strings.httpsOnlyHint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("subscription_url_input")
            )
        },
        confirmButton = {
            Button(
                onClick = { onImport(url) },
                enabled = url.startsWith("https://") && url.length > 10
            ) {
                Text(strings.parseAndSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
private fun EditConfigDialog(
    item: ServerWithConfiguration,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf(item.effectiveTitle) }
    var trusted by remember { mutableStateOf(item.configuration?.trustedSource == true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.editConfig) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.displayNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = trusted, onCheckedChange = { trusted = it })
                    Text(strings.trustedSourceLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, trusted) }) { Text(strings.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}
