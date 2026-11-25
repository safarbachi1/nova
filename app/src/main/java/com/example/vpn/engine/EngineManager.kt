package com.example.vpn.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.core.logging.DiagnosticLogger
import com.example.data.local.entity.ConnectionHistoryEntity
import com.example.data.local.entity.ServerEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.NovaVpnRepository
import com.example.domain.model.ConnectionState
import com.example.domain.model.EngineRuntimeInfo
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.SelectionMode
import com.example.domain.model.VpnError
import com.example.domain.model.VpnErrorType
import com.example.domain.model.VpnProtocol
import com.example.domain.network.NetworkDiagnosticsService
import com.example.domain.selector.SmartServerSelector
import com.example.vpn.service.NovaVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates multi-engine dispatch across WireGuard, sing-box, and OpenVPN with strict mutual
 * exclusion so two VPN engines can NEVER simultaneously claim Android's VpnService interface,
 * while allowing immediate non-blocking cancellation/disconnect at any point.
 */
class EngineManager(
    private val context: Context,
    private val repository: NovaVpnRepository,
    private val prefsRepo: UserPreferencesRepository,
    private val networkDiagnostics: NetworkDiagnosticsService,
    private val logger: DiagnosticLogger,
    private val singBoxEngine: SingBoxVpnEngine,
    private val openVpnEngine: OpenVpnEngine,
    private val wireGuardEngine: WireGuardVpnEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statistics = MutableStateFlow(EngineStatistics())
    val statistics: StateFlow<EngineStatistics> = _statistics.asStateFlow()

    private val _lastError = MutableStateFlow<VpnError?>(null)
    val lastError: StateFlow<VpnError?> = _lastError.asStateFlow()

    private val _activeServer = MutableStateFlow<ServerEntity?>(null)
    val activeServer: StateFlow<ServerEntity?> = _activeServer.asStateFlow()

    private val _activeEngineType = MutableStateFlow<EngineType?>(null)
    val activeEngineType: StateFlow<EngineType?> = _activeEngineType.asStateFlow()

    private val _publicIp = MutableStateFlow<String?>(null)
    val publicIp: StateFlow<String?> = _publicIp.asStateFlow()

    private val _vpnExitIp = MutableStateFlow<String?>(null)
    val vpnExitIp: StateFlow<String?> = _vpnExitIp.asStateFlow()

    private val _reconnectAttempts = MutableStateFlow(0)
    val reconnectAttempts: StateFlow<Int> = _reconnectAttempts.asStateFlow()

    private val _tunnelNotice = MutableStateFlow<String?>(null)
    val tunnelNotice: StateFlow<String?> = _tunnelNotice.asStateFlow()

    @Volatile
    private var activeEngine: VPNEngine? = null
    @Volatile
    private var connectJob: Job? = null
    private var exitIpJob: Job? = null
    private var activeHistoryId: Long? = null
    private var activeSessionStartMs: Long = 0L
    private var engineObserversJob: Job? = null

    init {
        wireGuardEngine.initialize()
        singBoxEngine.initialize()
        openVpnEngine.initialize()
    }

    fun clearTunnelNotice() {
        _tunnelNotice.value = null
    }

    fun getAllEngineRuntimeInfo(): List<EngineRuntimeInfo> = listOf(
        wireGuardEngine.runtimeInfo(),
        singBoxEngine.runtimeInfo(),
        openVpnEngine.runtimeInfo()
    )

    fun selectEngineForProtocol(protocol: VpnProtocol): VPNEngine? = when (protocol.primaryEngine) {
        EngineType.WIREGUARD -> wireGuardEngine
        EngineType.SING_BOX -> singBoxEngine
        EngineType.OPENVPN -> openVpnEngine
        EngineType.UNSUPPORTED -> null
    }

    suspend fun refreshPublicIp() {
        networkDiagnostics.evictConnections()
        val info = networkDiagnostics.fetchPublicIpInfo()
        if (_connectionState.value == ConnectionState.CONNECTED) {
            _vpnExitIp.value = info?.ip
        } else {
            _publicIp.value = info?.ip
        }
    }

    suspend fun connectToServer(serverId: Long): Result<Unit> {
        // Cancel any previous in-flight connect attempt immediately so switching servers is instant
        connectJob?.cancel()
        exitIpJob?.cancel()

        val deferred = scope.async {
            connectInternal(serverId)
        }
        connectJob = deferred
        return try {
            deferred.await()
        } catch (ce: CancellationException) {
            Result.failure(ce)
        }
    }

    private suspend fun connectInternal(serverId: Long): Result<Unit> {
        _lastError.value = null
        _tunnelNotice.value = null
        if (!hasActiveNetwork()) {
            val err = VpnError(VpnErrorType.NETWORK_UNAVAILABLE, "Device has no active internet transport")
            _lastError.value = err
            _connectionState.value = ConnectionState.ERROR
            logger.error("EngineManager", err.technicalDetail)
            return Result.failure(IllegalStateException(err.technicalDetail))
        }

        val profile = repository.getDecryptedProfile(serverId) ?: run {
            val err = VpnError(VpnErrorType.INVALID_CONFIGURATION, "Server configuration #$serverId not found")
            _lastError.value = err
            _connectionState.value = ConnectionState.ERROR
            return Result.failure(IllegalArgumentException(err.technicalDetail))
        }

        val prefs = prefsRepo.preferencesFlow.first()

        // Enforce explicit user approval policy for untrusted public sources
        if (prefs.requireUntrustedApproval &&
            !profile.configuration.trustedSource &&
            !profile.configuration.userApprovedUntrusted
        ) {
            val err = VpnError(VpnErrorType.UNTRUSTED_SERVER_BLOCKED, "Untrusted public config requires explicit user approval")
            _lastError.value = err
            _connectionState.value = ConnectionState.ERROR
            logger.warn("EngineManager", "Blocked connection to untrusted server ${profile.server.hostname}")
            return Result.failure(SecurityException(err.technicalDetail))
        }

        val engine = selectEngineForProtocol(profile.server.protocol) ?: run {
            val err = VpnError(VpnErrorType.UNSUPPORTED_PROTOCOL, "No engine supports ${profile.server.protocol.displayName}")
            _lastError.value = err
            _connectionState.value = ConnectionState.ERROR
            return Result.failure(UnsupportedOperationException(err.technicalDetail))
        }

        // Stop any currently active engine cleanly before starting the next engine
        stateMutex.withLock {
            stopActiveEngineInternal(recordDisconnectHistory = true)
            _activeServer.value = profile.server
            _activeEngineType.value = profile.server.protocol.primaryEngine
            _connectionState.value = ConnectionState.CONNECTING
            _vpnExitIp.value = null
            activeSessionStartMs = System.currentTimeMillis()
            activeEngine = engine
            observeEngineFlows(engine)
        }

        logger.info(
            "EngineManager",
            "Dispatching ${profile.server.protocol.displayName} (${profile.server.hostname}:${profile.server.port}) -> ${engine.runtimeInfo().name}"
        )

        val request = VpnConnectRequest(
            server = profile.server,
            protocol = profile.server.protocol,
            rawConfiguration = profile.rawConfigData,
            preferences = prefs
        )
        val result = engine.connect(request)

        if (result.isSuccess && engine.getStatus().value == ConnectionState.CONNECTED) {
            _connectionState.value = ConnectionState.CONNECTED
            _reconnectAttempts.value = 0
            activeHistoryId = repository.insertConnectionHistory(
                ConnectionHistoryEntity(
                    serverId = profile.server.id,
                    serverLabel = profile.server.displayName.ifBlank { profile.server.hostname },
                    countryCode = profile.server.countryCode,
                    protocol = profile.server.protocol,
                    connectedAt = activeSessionStartMs,
                    connectionResult = "CONNECTED"
                )
            )
            // Evict all pre-VPN pooled sockets and verify the REAL VPN exit IP through the tunnel
            verifyRealExitIpAfterConnect(prefs.languageCode)
            return Result.success(Unit)
        } else {
            val fallbackId = findFallbackServerId(profile.server.id)
            val engineErr = engine.getLastError().value
                ?: VpnError(VpnErrorType.TUNNEL_ESTABLISHMENT_FAILURE, result.exceptionOrNull()?.message.orEmpty())
            val enrichedErr = engineErr.copy(suggestedFallbackServerId = fallbackId)
            _lastError.value = enrichedErr
            _connectionState.value = ConnectionState.ERROR
            repository.insertConnectionHistory(
                ConnectionHistoryEntity(
                    serverId = profile.server.id,
                    serverLabel = profile.server.displayName.ifBlank { profile.server.hostname },
                    countryCode = profile.server.countryCode,
                    protocol = profile.server.protocol,
                    connectedAt = activeSessionStartMs,
                    disconnectedAt = System.currentTimeMillis(),
                    connectionResult = "FAILED",
                    errorDetail = enrichedErr.type.name
                )
            )
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException(enrichedErr.technicalDetail))
        }
    }

    private fun verifyRealExitIpAfterConnect(lang: String) {
        exitIpJob?.cancel()
        exitIpJob = scope.launch {
            networkDiagnostics.evictConnections()
            // Give Android ConnectivityService time to make tun0 the default route
            delay(850L)
            if (_connectionState.value != ConnectionState.CONNECTED) return@launch
            networkDiagnostics.evictConnections()

            var exitInfo = networkDiagnostics.fetchPublicIpInfo()
            if (exitInfo == null || exitInfo.ip == _publicIp.value) {
                delay(1400L)
                if (_connectionState.value != ConnectionState.CONNECTED) return@launch
                networkDiagnostics.evictConnections()
                exitInfo = networkDiagnostics.fetchPublicIpInfo()
            }

            if (_connectionState.value != ConnectionState.CONNECTED) return@launch

            if (exitInfo != null && exitInfo.ip.isNotBlank()) {
                _vpnExitIp.value = exitInfo.ip
                if (_publicIp.value != null && exitInfo.ip == _publicIp.value) {
                    logger.warn("EngineManager", "Exit IP (${exitInfo.ip}) matches direct IP; proxy may be bypassing or dead")
                    _tunnelNotice.value = if (lang == "fa") {
                        "تونل فعال است اما IP تغییر نکرد؛ سرور دیگری را امتحان کنید."
                    } else {
                        "Tunnel is up, but exit IP did not change. Try another server."
                    }
                } else {
                    logger.info("EngineManager", "Verified VPN Exit IP: ${exitInfo.ip} (${exitInfo.countryCode})")
                }
            } else {
                logger.warn("EngineManager", "Exit IP probe timed out through tunnel; remote public proxy may not forward traffic")
                _tunnelNotice.value = if (lang == "fa") {
                    "سرور متصل شد اما ترافیک اینترنت را عبور نمی‌دهد (سرور عمومی منقضی شده). سرور دیگری انتخاب کنید."
                } else {
                    "Connected to tunnel, but this public proxy did not forward traffic. Switch to another server."
                }
            }
        }
    }

    /**
     * Immediate, non-blocking disconnect: cancels any in-flight connect attempt, tears down
     * the active engine and Android TUN interface immediately, and clears the VPN key icon.
     */
    suspend fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        exitIpJob?.cancel()
        exitIpJob = null

        _connectionState.value = ConnectionState.DISCONNECTING
        stateMutex.withLock {
            stopActiveEngineInternal(recordDisconnectHistory = true)
            // Always guarantee Android's NovaVpnService TUN handle is closed even if engine was mid-connect
            NovaVpnService.stopTunnelFromEngine(context)
            networkDiagnostics.evictConnections()
            _lastError.value = null
            _tunnelNotice.value = null
            _vpnExitIp.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    suspend fun onSystemVpnRevoked() {
        disconnect()
    }

    suspend fun onUnderlyingNetworkChanged(isAvailable: Boolean) {
        val currentServer = _activeServer.value ?: return
        val state = _connectionState.value
        if (state != ConnectionState.CONNECTED && state != ConnectionState.RECONNECTING) return
        val prefs = prefsRepo.preferencesFlow.first()
        if (!prefs.reconnectOnNetworkChange) return

        if (!isAvailable) {
            logger.warn("EngineManager", "Underlying network lost; awaiting reconnection")
            _connectionState.value = ConnectionState.RECONNECTING
        } else if (state == ConnectionState.RECONNECTING) {
            _reconnectAttempts.value = _reconnectAttempts.value + 1
            logger.info("EngineManager", "Network restored; attempting automatic reconnect #${_reconnectAttempts.value}")
            connectToServer(currentServer.id)
        }
    }

    fun clearError() {
        _lastError.value = null
        _tunnelNotice.value = null
        if (_connectionState.value == ConnectionState.ERROR) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private suspend fun stopActiveEngineInternal(recordDisconnectHistory: Boolean) {
        engineObserversJob?.cancel()
        engineObserversJob = null
        val statsSnapshot = _statistics.value
        val prevServer = _activeServer.value
        val historyId = activeHistoryId

        val engineToStop = activeEngine
        activeEngine = null
        activeHistoryId = null

        engineToStop?.let { engine ->
            runCatching { engine.disconnect() }
        }

        if (recordDisconnectHistory && historyId != null && prevServer != null) {
            val now = System.currentTimeMillis()
            repository.updateConnectionHistory(
                ConnectionHistoryEntity(
                    id = historyId,
                    serverId = prevServer.id,
                    serverLabel = prevServer.displayName.ifBlank { prevServer.hostname },
                    countryCode = prevServer.countryCode,
                    protocol = prevServer.protocol,
                    connectedAt = activeSessionStartMs,
                    disconnectedAt = now,
                    duration = (now - activeSessionStartMs).coerceAtLeast(0L),
                    bytesReceived = statsSnapshot.bytesReceived,
                    bytesSent = statsSnapshot.bytesSent,
                    connectionResult = "COMPLETED"
                )
            )
        }
        _statistics.value = EngineStatistics()
    }

    private fun observeEngineFlows(engine: VPNEngine) {
        engineObserversJob?.cancel()
        engineObserversJob = scope.launch {
            launch {
                engine.getStatistics().collect { _statistics.value = it }
            }
            launch {
                engine.getStatus().collect { status ->
                    if (activeEngine === engine) {
                        _connectionState.value = status
                    }
                }
            }
        }
    }

    private suspend fun findFallbackServerId(failedServerId: Long): Long? {
        val all = repository.serversWithConfigurations.first()
        val prefs = prefsRepo.preferencesFlow.first()
        return SmartServerSelector.selectBest(
            servers = all,
            mode = SelectionMode.AUTO,
            preferredProtocol = prefs.preferredProtocol,
            onlyApproved = prefs.requireUntrustedApproval,
            excludeServerId = failedServerId
        )?.server?.id
    }

    private fun hasActiveNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
