package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.i18n.LocalAppStrings
import com.example.data.local.entity.ServerWithConfiguration
import com.example.domain.model.EuropeanCountries
import com.example.domain.model.SelectionMode
import com.example.domain.model.ServerSortMode
import com.example.domain.model.ServerStatus
import com.example.domain.model.VpnProtocol
import com.example.ui.components.Formatters
import com.example.ui.components.NovaGlassCard
import com.example.ui.components.ProtocolBadge
import com.example.ui.components.TrustBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalNovaColors
import com.example.ui.viewmodel.NovaVpnViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ServerExplorerScreen(
    viewModel: NovaVpnViewModel,
    favoritesOnlyInitial: Boolean = false,
    onNavigateToHome: () -> Unit,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToConfigs: () -> Unit
) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current

    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val testingIds by viewModel.testingServerIds.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf<String?>(null) }
    var selectedProtocol by remember { mutableStateOf<VpnProtocol?>(null) }
    var favoritesOnly by remember { mutableStateOf(favoritesOnlyInitial) }
    var sortMode by remember { mutableStateOf(ServerSortMode.PRIORITY_COUNTRY) }

    val filteredServers = remember(servers, searchQuery, selectedCountry, selectedProtocol, favoritesOnly, sortMode) {
        val q = searchQuery.trim().lowercase()
        servers.filter { item ->
            val s = item.server
            val matchesCountry = selectedCountry == null || s.countryCode.equals(selectedCountry, true)
            val matchesProtocol = selectedProtocol == null || s.protocol == selectedProtocol
            val matchesFav = !favoritesOnly || s.isFavorite
            val matchesSearch = q.isEmpty() ||
                s.hostname.lowercase().contains(q) ||
                s.displayName.lowercase().contains(q) ||
                s.countryName.lowercase().contains(q) ||
                s.countryCode.lowercase().contains(q) ||
                s.protocol.displayName.lowercase().contains(q)
            matchesCountry && matchesProtocol && matchesFav && matchesSearch
        }.let { list ->
            when (sortMode) {
                ServerSortMode.LATENCY -> list.sortedBy { if (it.server.latency > 0) it.server.latency else Int.MAX_VALUE }
                ServerSortMode.RELIABILITY -> list.sortedByDescending { it.server.reliabilityScore }
                ServerSortMode.SPEED -> list.sortedByDescending { it.server.downloadSpeedMbps }
                ServerSortMode.PRIORITY_COUNTRY -> list.sortedWith(
                    compareBy<ServerWithConfiguration> { !it.server.isFavorite }
                        .thenBy { EuropeanCountries.priorityOf(it.server.countryCode) }
                        .thenBy { if (it.server.latency > 0) it.server.latency else Int.MAX_VALUE }
                )
            }
        }
    }

    val featuredProtocols = remember {
        listOf(
            VpnProtocol.VLESS,
            VpnProtocol.VMESS,
            VpnProtocol.TROJAN,
            VpnProtocol.SHADOWSOCKS,
            VpnProtocol.HYSTERIA2,
            VpnProtocol.TUIC,
            VpnProtocol.WIREGUARD,
            VpnProtocol.OPENVPN
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (favoritesOnly) strings.favorites else strings.serversTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (servers.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { viewModel.probeVisibleServers() },
                            modifier = Modifier.testTag("test_all_servers_button")
                        ) {
                            Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.testAllOnline)
                        }
                    }
                }
            }

            // Search Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().testTag("server_search_input"),
                    placeholder = { Text(strings.searchServersHint) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // Smart Selection Modes
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.smartSelection,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SelectionMode.entries) { mode ->
                            FilterChip(
                                selected = prefs.selectionMode == mode,
                                onClick = { viewModel.applySmartSelection(mode) },
                                label = { Text(strings.selectionModeLabel(mode)) },
                                leadingIcon = if (mode == SelectionMode.AUTO) {
                                    { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null,
                                modifier = Modifier.testTag("selection_mode_${mode.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            // European Country Filter Row (Sweden & Switzerland pinned first)
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedCountry == null,
                            onClick = { selectedCountry = null },
                            label = { Text("\uD83C\uDDEA\uD83C\uDDFA ${strings.allCountries}") }
                        )
                    }
                    items(EuropeanCountries.all.take(15)) { country ->
                        FilterChip(
                            selected = selectedCountry == country.code,
                            onClick = {
                                selectedCountry = if (selectedCountry == country.code) null else country.code
                            },
                            label = {
                                Text("${country.flag} ${if (strings.isPersian) country.nameFa else country.nameEn}")
                            },
                            modifier = Modifier.testTag("country_filter_${country.code.lowercase()}")
                        )
                    }
                }
            }

            // Protocol & Favorites & Sort Filter Row
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = favoritesOnly,
                                onClick = { favoritesOnly = !favoritesOnly },
                                label = { Text(strings.favoritesOnly) },
                                leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedProtocol == null,
                                onClick = { selectedProtocol = null },
                                label = { Text(strings.allProtocols) }
                            )
                        }
                        items(featuredProtocols) { proto ->
                            FilterChip(
                                selected = selectedProtocol == proto,
                                onClick = { selectedProtocol = if (selectedProtocol == proto) null else proto },
                                label = { Text(proto.displayName) }
                            )
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val sorts = listOf(
                            ServerSortMode.PRIORITY_COUNTRY to strings.sortByPriority,
                            ServerSortMode.LATENCY to strings.sortByLatency,
                            ServerSortMode.RELIABILITY to strings.sortByReliability,
                            ServerSortMode.SPEED to strings.sortBySpeed
                        )
                        items(sorts) { (mode, title) ->
                            FilterChip(
                                selected = sortMode == mode,
                                onClick = { sortMode = mode },
                                label = { Text(title) }
                            )
                        }
                    }
                }
            }

            // Empty State or Server List
            if (filteredServers.isEmpty()) {
                item {
                    NovaGlassCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Rounded.Explore,
                                contentDescription = null,
                                tint = nova.accentBlue,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = strings.emptyServersTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = strings.emptyServersBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onNavigateToDiscovery,
                                    modifier = Modifier.testTag("empty_go_discovery_button")
                                ) {
                                    Text(strings.goToDiscovery)
                                }
                                OutlinedButton(
                                    onClick = onNavigateToConfigs,
                                    modifier = Modifier.testTag("empty_go_import_button")
                                ) {
                                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.goToImport)
                                }
                            }
                        }
                    }
                }
            } else {
                items(filteredServers, key = { it.server.id }) { item ->
                    ServerItemCard(
                        item = item,
                        isSelected = selectedServer?.server?.id == item.server.id,
                        isTesting = item.server.id in testingIds,
                        onSelect = {
                            viewModel.selectServer(item.server.id)
                            onNavigateToHome()
                        },
                        onToggleFavorite = { viewModel.toggleFavorite(item.server.id, item.server.isFavorite) },
                        onProbe = { viewModel.probeServer(item) }
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun ServerItemCard(
    item: ServerWithConfiguration,
    isSelected: Boolean,
    isTesting: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onProbe: () -> Unit
) {
    val strings = LocalAppStrings.current
    val nova = LocalNovaColors.current
    val s = item.server

    val statusDotColor = when (s.status) {
        ServerStatus.ONLINE -> nova.statusConnected
        ServerStatus.UNREACHABLE -> nova.statusError
        ServerStatus.UNSUPPORTED -> nova.statusWarning
        ServerStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (s.status) {
        ServerStatus.ONLINE -> strings.onlineStatus
        ServerStatus.UNREACHABLE -> strings.unreachableStatus
        ServerStatus.UNSUPPORTED -> strings.unsupportedStatus
        ServerStatus.UNKNOWN -> strings.unknownStatus
    }
    val lastTestedText = remember(s.lastTestedAt) {
        if (s.lastTestedAt <= 0L) strings.neverTested
        else SimpleDateFormat("HH:mm", Locale.US).format(Date(s.lastTestedAt))
    }

    NovaGlassCard(
        modifier = Modifier.fillMaxWidth().testTag("server_card_${s.id}"),
        borderColor = if (isSelected) nova.accentBlue else null,
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = EuropeanCountries.flagFor(s.countryCode),
                fontSize = 30.sp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = EuropeanCountries.localizedName(s.countryCode, s.countryName, strings.isPersian),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = nova.accentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "${s.hostname}:${s.port}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.testTag("favorite_btn_${s.id}")
            ) {
                Icon(
                    imageVector = if (s.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = strings.favorites,
                    tint = if (s.isFavorite) nova.statusError else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProtocolBadge(s.protocol)
                TrustBadge(
                    trusted = item.configuration?.trustedSource == true,
                    approved = item.configuration?.userApprovedUntrusted == true
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusDotColor
                )
                Text(
                    text = Formatters.formatLatency(s.latency),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${strings.lastTested}: $lastTestedText • ${strings.reliability}: ${(s.reliabilityScore * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = nova.accentBlue.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, nova.accentBlue.copy(alpha = 0.35f)),
                onClick = onProbe,
                modifier = Modifier.testTag("probe_btn_${s.id}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp), tint = nova.accentCyan)
                    }
                    Text(strings.testLatency, style = MaterialTheme.typography.labelMedium, color = nova.accentCyan)
                }
            }
        }
    }
}
