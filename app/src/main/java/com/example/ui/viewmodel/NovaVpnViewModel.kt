package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NovaVpnApplication
import com.example.core.security.NetworkSecurityValidator
import com.example.data.local.entity.ConnectionHistoryEntity
import com.example.data.local.entity.ServerWithConfiguration
import com.example.data.local.entity.SourceEntity
import com.example.data.local.entity.SpeedTestEntity
import com.example.data.preferences.UserPreferences
import com.example.domain.discovery.DiscoverySummary
import com.example.domain.model.ConnectionState
import com.example.domain.model.EngineLogEntry
import com.example.domain.model.EngineRuntimeInfo
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.SelectionMode
import com.example.domain.model.ServerStatus
import com.example.domain.model.SourceType
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.ThemeMode
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnError
import com.example.domain.model.VpnErrorType
import com.example.domain.model.VpnProtocol
import com.example.domain.network.LiveSpeedProgress
import com.example.domain.parser.ConfigParser
import com.example.domain.selector.SmartServerSelector
import com.example.work.DiscoveryWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request

data class InstalledAppItem(
    val packageName: String,
    val label: String
)

data class DiscoveryUiState(
    val isRefreshing: Boolean = false,
    val currentSourceName: String = "",
    val lastSummary: DiscoverySummary? = null
)

data class SpeedTestUiState(
    val isRunning: Boolean = false,
    val liveProgress: LiveSpeedProgress? = null,
    val lastError: String? = null
)

class NovaVpnViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NovaVpnApplication).container
    private val repository = container.repository
    private val prefsRepo = container.preferencesRepository
    private val engineManager = container.engineManager
    private val discoveryEngine = container.discoveryEngine
    private val networkDiagnostics = container.networkDiagnostics
    private val logger = container.logger

    val preferences: StateFlow<UserPreferences> = prefsRepo.preferencesFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UserPreferences()
    )

    val servers: StateFlow<List<ServerWithConfiguration>> = repository.serversWithConfigurations.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val sources: StateFlow<List<SourceEntity>> = repository.sources.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val connectionHistory: StateFlow<List<ConnectionHistoryEntity>> = repository.connectionHistory.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val speedTestHistory: StateFlow<List<SpeedTestEntity>> = repository.speedTestHistory.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val totalDataBytes: StateFlow<Long> = combine(
        repository.totalBytesReceived,
        repository.totalBytesSent
    ) { rx, tx -> rx + tx }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val connectionState: StateFlow<ConnectionState> = engineManager.connectionState
    val statistics: StateFlow<EngineStatistics> = engineManager.statistics
    val lastError: StateFlow<VpnError?> = engineManager.lastError
    val publicIp: StateFlow<String?> = engineManager.publicIp
    val vpnExitIp: StateFlow<String?> = engineManager.vpnExitIp
    val activeEngineType: StateFlow<EngineType?> = engineManager.activeEngineType
    val reconnectAttempts: StateFlow<Int> = engineManager.reconnectAttempts
    val logs: StateFlow<List<EngineLogEntry>> = logger.logs

    val selectedServer: StateFlow<ServerWithConfiguration?> = combine(
        servers,
        preferences
    ) { list, prefs ->
        val byId = prefs.selectedServerId?.let { id -> list.firstOrNull { it.server.id == id } }
        byId ?: SmartServerSelector.selectBest(
            servers = list,
            mode = prefs.selectionMode,
            preferredProtocol = prefs.preferredProtocol,
            onlyApproved = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _discoveryUi = MutableStateFlow(DiscoveryUiState())
    val discoveryUi: StateFlow<DiscoveryUiState> = _discoveryUi.asStateFlow()

    private val _speedTestUi = MutableStateFlow(SpeedTestUiState())
    val speedTestUi: StateFlow<SpeedTestUiState> = _speedTestUi.asStateFlow()

    private val _pendingUntrustedApproval = MutableStateFlow<ServerWithConfiguration?>(null)
    val pendingUntrustedApproval: StateFlow<ServerWithConfiguration?> = _pendingUntrustedApproval.asStateFlow()

    private val _pendingVpnConsentIntent = MutableStateFlow<Intent?>(null)
    val pendingVpnConsentIntent: StateFlow<Intent?> = _pendingVpnConsentIntent.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _testingServerIds = MutableStateFlow<Set<Long>>(emptySet())
    val testingServerIds: StateFlow<Set<Long>> = _testingServerIds.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppItem>> = _installedApps.asStateFlow()

    private var pendingConnectServerId: Long? = null

    init {
        viewModelScope.launch {
            engineManager.tunnelNotice.collect { notice ->
                if (!notice.isNullOrBlank()) {
                    _statusBanner.value = notice
                }
            }
        }
    }

    fun engineRuntimeInfos(): List<EngineRuntimeInfo> = engineManager.getAllEngineRuntimeInfo()

    fun dismissBanner() {
        _statusBanner.value = null
        engineManager.clearTunnelNotice()
    }

    fun dismissError() {
        engineManager.clearError()
    }

    fun refreshPublicIp() {
        viewModelScope.launch {
            engineManager.refreshPublicIp()
        }
    }

    fun onHeroConnectToggle() {
        when (connectionState.value) {
            ConnectionState.CONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING -> disconnect()
            else -> {
                val target = selectedServer.value
                if (target == null) {
                    _statusBanner.value = if (preferences.value.languageCode == "fa") {
                        "ابتدا سروری را از بخش سرورها یا کشف خودکار انتخاب کنید."
                    } else {
                        "Select or discover a European server first."
                    }
                } else {
                    requestConnect(target)
                }
            }
        }
    }

    fun requestConnect(target: ServerWithConfiguration) {
        val prefs = preferences.value
        if (prefs.requireUntrustedApproval && !target.isTrustedOrApproved) {
            _pendingUntrustedApproval.value = target
            return
        }
        proceedWithVpnPermissionCheck(target.server.id)
    }

    fun approveUntrustedAndConnect() {
        val target = _pendingUntrustedApproval.value ?: return
        _pendingUntrustedApproval.value = null
        viewModelScope.launch {
            repository.approveUntrustedServer(target.server.id)
            proceedWithVpnPermissionCheck(target.server.id)
        }
    }

    fun cancelUntrustedApproval() {
        _pendingUntrustedApproval.value = null
    }

    private fun proceedWithVpnPermissionCheck(serverId: Long) {
        val prepareIntent = runCatching { VpnService.prepare(getApplication()) }.getOrNull()
        if (prepareIntent != null) {
            pendingConnectServerId = serverId
            _pendingVpnConsentIntent.value = prepareIntent
        } else {
            viewModelScope.launch {
                prefsRepo.setSelectedServerId(serverId)
                engineManager.connectToServer(serverId)
            }
        }
    }

    fun onVpnConsentResult(granted: Boolean) {
        _pendingVpnConsentIntent.value = null
        val targetId = pendingConnectServerId
        pendingConnectServerId = null
        if (!granted) {
            logger.warn("VpnConsent", "User denied Android VpnService permission")
            _statusBanner.value = if (preferences.value.languageCode == "fa") {
                "مجوز VPN اندروید تایید نشد."
            } else {
                "Android VPN permission was denied."
            }
            return
        }
        if (targetId != null) {
            viewModelScope.launch {
                prefsRepo.setSelectedServerId(targetId)
                engineManager.connectToServer(targetId)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            engineManager.disconnect()
        }
    }

    fun tryFallbackServer() {
        val fallbackId = lastError.value?.suggestedFallbackServerId ?: return
        val candidate = servers.value.firstOrNull { it.server.id == fallbackId } ?: return
        viewModelScope.launch {
            prefsRepo.setSelectedServerId(candidate.server.id)
            requestConnect(candidate)
        }
    }

    fun selectServer(serverId: Long) {
        viewModelScope.launch {
            prefsRepo.setSelectedServerId(serverId)
            prefsRepo.setSelectionMode(SelectionMode.MANUAL)
        }
    }

    fun applySmartSelection(mode: SelectionMode) {
        viewModelScope.launch {
            prefsRepo.setSelectionMode(mode)
            val chosen = SmartServerSelector.selectBest(
                servers = servers.value,
                mode = mode,
                preferredProtocol = preferences.value.preferredProtocol,
                onlyApproved = false
            )
            if (chosen != null) {
                prefsRepo.setSelectedServerId(chosen.server.id)
                _statusBanner.value = "${chosen.effectiveTitle} (${chosen.server.countryCode})"
            }
        }
    }

    fun toggleFavorite(serverId: Long, current: Boolean) {
        viewModelScope.launch { repository.setFavorite(serverId, !current) }
    }

    fun probeServer(item: ServerWithConfiguration) {
        viewModelScope.launch {
            _testingServerIds.value = _testingServerIds.value + item.server.id
            val result = networkDiagnostics.probeServer(
                hostname = item.server.hostname,
                port = item.server.port,
                useTlsHandshake = item.server.port == 443,
                timeoutMs = preferences.value.connectionTimeoutSec * 1000
            )
            repository.recordProbeResult(item.server, result.latencyMs, result.reachable)
            _testingServerIds.value = _testingServerIds.value - item.server.id
        }
    }

    fun probeVisibleServers() {
        viewModelScope.launch(Dispatchers.IO) {
            val targets = servers.value.take(20)
            val semaphore = Semaphore(12)
            coroutineScope {
                targets.map { item ->
                    async {
                        semaphore.withPermit {
                            _testingServerIds.value = _testingServerIds.value + item.server.id
                            val res = networkDiagnostics.probeServer(
                                hostname = item.server.hostname,
                                port = item.server.port,
                                useTlsHandshake = false,
                                timeoutMs = 2000
                            )
                            repository.recordProbeResult(item.server, res.latencyMs, res.reachable)
                            _testingServerIds.value = _testingServerIds.value - item.server.id
                        }
                    }
                }.awaitAll()
            }
        }
    }

    // Discovery
    fun refreshAllSources() {
        if (_discoveryUi.value.isRefreshing) return
        viewModelScope.launch {
            _discoveryUi.value = DiscoveryUiState(isRefreshing = true)
            val summary = discoveryEngine.refreshAllEnabledSources { name ->
                _discoveryUi.value = _discoveryUi.value.copy(currentSourceName = name)
            }
            _discoveryUi.value = DiscoveryUiState(isRefreshing = false, lastSummary = summary)
            _statusBanner.value = if (preferences.value.languageCode == "fa") {
                "کشف پایان یافت: ${summary.savedValidated} سرور اروپایی (${summary.reachableCount} در دسترس)"
            } else {
                "Discovery complete: ${summary.savedValidated} European servers (${summary.reachableCount} reachable)"
            }
        }
    }

    fun refreshSingleSource(source: SourceEntity) {
        if (_discoveryUi.value.isRefreshing) return
        viewModelScope.launch {
            _discoveryUi.value = DiscoveryUiState(isRefreshing = true, currentSourceName = source.name)
            val summary = discoveryEngine.refreshSingleSource(source)
            _discoveryUi.value = DiscoveryUiState(isRefreshing = false, lastSummary = summary)
        }
    }

    fun addDiscoverySource(name: String, url: String, type: SourceType) {
        viewModelScope.launch {
            val res = repository.addSource(name, url, type)
            _statusBanner.value = if (res.isSuccess) {
                if (preferences.value.languageCode == "fa") "منبع HTTPS به فهرست مجاز اضافه شد." else "HTTPS source added to allowlist."
            } else {
                res.exceptionOrNull()?.message ?: "Invalid source"
            }
        }
    }

    fun setSourceEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setSourceEnabled(id, enabled) }
    }

    fun deleteSource(id: Long) {
        viewModelScope.launch { repository.deleteSource(id) }
    }

    // Config Manager
    fun importRawConfigurations(rawText: String, trustedByUser: Boolean = true) {
        viewModelScope.launch {
            val parsed = ConfigParser.parseBatch(rawText)
            if (parsed.isEmpty()) {
                _statusBanner.value = if (preferences.value.languageCode == "fa") {
                    "هیچ پیکربندی معتبری شناسایی نشد."
                } else {
                    "No supported VPN configuration was recognized."
                }
                return@launch
            }
            var saved = 0
            var firstId: Long? = null
            for (item in parsed) {
                val (cc, cname) = if (item.countryCodeHint.isNotBlank()) {
                    item.countryCodeHint to item.countryNameHint
                } else {
                    networkDiagnostics.resolveServerCountry(item.hostname)
                }
                val id = repository.upsertDiscoveredOrImportedServer(
                    hostname = item.hostname,
                    port = item.port,
                    countryCode = cc.ifBlank { "EU" },
                    countryName = cname.ifBlank { "Europe" },
                    protocol = item.protocol,
                    displayName = item.displayName,
                    rawConfigData = item.rawConfigData,
                    endpointHash = item.endpointHash,
                    sourceId = null,
                    validationStatus = item.validationStatus,
                    validationMessage = item.validationMessage,
                    trustedSource = trustedByUser,
                    latencyMs = -1,
                    status = if (item.protocol.isSupported) ServerStatus.UNKNOWN else ServerStatus.UNSUPPORTED
                )
                if (id > 0) {
                    saved++
                    if (firstId == null) firstId = id
                }
            }
            if (firstId != null && preferences.value.selectedServerId == null) {
                prefsRepo.setSelectedServerId(firstId)
            }
            logger.info("ConfigImport", "Imported $saved configuration(s) (trusted=$trustedByUser)")
            _statusBanner.value = if (preferences.value.languageCode == "fa") {
                "$saved پیکربندی با رمزنگاری Keystore ذخیره شد (تست اتصال انجام نشده)."
            } else {
                "Imported $saved config(s) encrypted via Keystore (connectivity untested)."
            }
        }
    }

    fun importSubscriptionUrl(url: String) {
        viewModelScope.launch {
            val validated = NetworkSecurityValidator.validateHttpsSourceUrl(url)
            if (validated.isFailure) {
                _statusBanner.value = validated.exceptionOrNull()?.message ?: "HTTPS URL required"
                return@launch
            }
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(validated.getOrThrow()).build()
                    container.httpClient.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                        r.body?.string().orEmpty()
                    }
                }
            }
            body.onSuccess { text ->
                importRawConfigurations(text, trustedByUser = false)
            }.onFailure { err ->
                _statusBanner.value = err.message ?: "Subscription fetch failed"
            }
        }
    }

    fun updateConfigMetadata(serverId: Long, displayName: String, trusted: Boolean) {
        viewModelScope.launch {
            repository.updateServerNameAndTrust(serverId, displayName, trusted)
        }
    }

    suspend fun getDecryptedExportText(serverId: Long): String? =
        repository.getDecryptedProfile(serverId)?.rawConfigData

    fun deleteConfiguration(serverId: Long) {
        viewModelScope.launch {
            if (preferences.value.selectedServerId == serverId) {
                prefsRepo.setSelectedServerId(null)
            }
            repository.deleteServer(serverId)
        }
    }

    // Speed Test
    fun startSpeedTest() {
        if (_speedTestUi.value.isRunning) return
        viewModelScope.launch {
            _speedTestUi.value = SpeedTestUiState(isRunning = true)
            val result = networkDiagnostics.runSpeedTest { progress ->
                _speedTestUi.value = _speedTestUi.value.copy(liveProgress = progress)
            }
            result.onSuccess { m ->
                val currentServer = selectedServer.value?.server
                repository.insertSpeedTest(
                    SpeedTestEntity(
                        serverId = currentServer?.id,
                        serverLabel = currentServer?.displayName?.ifBlank { currentServer.hostname } ?: "Direct Network",
                        downloadMbps = m.downloadMbps,
                        uploadMbps = m.uploadMbps,
                        latencyMs = m.latencyMs,
                        jitterMs = m.jitterMs,
                        packetLossPercent = m.packetLossPercent,
                        stabilityScore = m.stabilityScore
                    )
                )
                if (connectionState.value == ConnectionState.CONNECTED && currentServer != null) {
                    repository.updateServerThroughput(currentServer.id, m.downloadMbps, m.uploadMbps)
                }
                _speedTestUi.value = _speedTestUi.value.copy(isRunning = false, lastError = null)
            }.onFailure { err ->
                _speedTestUi.value = SpeedTestUiState(isRunning = false, lastError = err.message)
            }
        }
    }

    // Installed launchable apps for Play-Policy compliant Split Tunneling (uses <queries> LAUNCHER intent)
    fun loadLaunchableApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolved = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }
                .getOrDefault(emptyList())
            val selfPkg = getApplication<Application>().packageName
            val apps = resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == selfPkg) return@mapNotNull null
                val label = info.loadLabel(pm).toString().ifBlank { pkg }
                InstalledAppItem(pkg, label)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            _installedApps.value = apps
        }
    }

    // Settings
    fun setAutoConnect(v: Boolean) = viewModelScope.launch { prefsRepo.setAutoConnect(v) }
    fun setReconnectOnNetworkChange(v: Boolean) = viewModelScope.launch { prefsRepo.setReconnectOnNetworkChange(v) }
    fun setKillSwitchEnabled(v: Boolean) = viewModelScope.launch { prefsRepo.setKillSwitchEnabled(v) }
    fun setSplitTunnelMode(m: SplitTunnelMode) = viewModelScope.launch { prefsRepo.setSplitTunnelMode(m) }
    fun toggleSplitTunnelPackage(pkg: String) = viewModelScope.launch { prefsRepo.toggleSplitTunnelPackage(pkg) }
    fun setCustomDns(primary: String, secondary: String) = viewModelScope.launch {
        if (NetworkSecurityValidator.isValidDnsAddress(primary) && NetworkSecurityValidator.isValidDnsAddress(secondary)) {
            prefsRepo.setCustomDns(primary, secondary)
        } else {
            _statusBanner.value = if (preferences.value.languageCode == "fa") "آدرس DNS نامعتبر است." else "Invalid DNS IP address."
        }
    }
    fun setIpv4Enabled(v: Boolean) = viewModelScope.launch { prefsRepo.setIpv4Enabled(v) }
    fun setIpv6Enabled(v: Boolean) = viewModelScope.launch { prefsRepo.setIpv6Enabled(v) }
    fun setPreferredProtocol(p: VpnProtocol?) = viewModelScope.launch { prefsRepo.setPreferredProtocol(p) }
    fun setConnectionTimeout(s: Int) = viewModelScope.launch { prefsRepo.setConnectionTimeout(s) }
    fun setAutoServerRefresh(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setAutoServerRefresh(enabled)
        DiscoveryWorkScheduler.scheduleOrCancel(getApplication(), enabled, preferences.value.refreshIntervalHours)
    }
    fun setRefreshIntervalHours(hours: Int) = viewModelScope.launch {
        prefsRepo.setRefreshIntervalHours(hours)
        DiscoveryWorkScheduler.scheduleOrCancel(getApplication(), preferences.value.autoServerRefresh, hours)
    }
    fun setThemeMode(m: ThemeMode) = viewModelScope.launch { prefsRepo.setThemeMode(m) }
    fun setLanguageCode(code: String) = viewModelScope.launch { prefsRepo.setLanguageCode(code) }
    fun setRequireUntrustedApproval(v: Boolean) = viewModelScope.launch { prefsRepo.setRequireUntrustedApproval(v) }
    fun clearHistory() = viewModelScope.launch { repository.clearConnectionHistory() }
    fun clearLogs() = logger.clear()
    fun exportRedactedLogs(): String = logger.exportRedactedText()
}
