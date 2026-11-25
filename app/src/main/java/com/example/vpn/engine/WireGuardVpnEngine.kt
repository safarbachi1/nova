package com.example.vpn.engine

import android.content.Context
import android.net.VpnService
import com.example.core.logging.DiagnosticLogger
import com.example.domain.model.ConnectionState
import com.example.domain.model.EngineLogEntry
import com.example.domain.model.EngineRuntimeInfo
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.VpnError
import com.example.domain.model.VpnErrorType
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.StringReader

/**
 * Genuine WireGuard engine backed by the official Maven Central `com.wireguard.android:tunnel`
 * GoBackend (userspace WireGuard VpnService). Establishes a real Android TUN interface and reads
 * authentic RX/TX byte counters directly from the WireGuard tunnel.
 */
class WireGuardVpnEngine(
    private val context: Context,
    private val logger: DiagnosticLogger
) : VPNEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _stats = MutableStateFlow(EngineStatistics())
    private val _lastError = MutableStateFlow<VpnError?>(null)

    private var backend: GoBackend? = null
    private var statsJob: Job? = null
    private var connectedAtMs: Long = 0L

    private val tunnel = object : Tunnel {
        override fun getName(): String = "nova_wg0"
        override fun onStateChange(newState: Tunnel.State) {
            logger.engine("WireGuard", "Tunnel state changed -> $newState")
            when (newState) {
                Tunnel.State.UP -> _status.value = ConnectionState.CONNECTED
                Tunnel.State.DOWN -> {
                    if (_status.value != ConnectionState.ERROR) {
                        _status.value = ConnectionState.DISCONNECTED
                    }
                }
                Tunnel.State.TOGGLE -> Unit
            }
        }
    }

    override fun initialize() {
        logger.info("WireGuard", "WireGuard GoBackend engine registered (lazy JNI initialization)")
    }

    private fun getOrCreateBackend(): Result<GoBackend> = runCatching {
        backend ?: GoBackend(context.applicationContext).also { backend = it }
    }

    override suspend fun connect(configuration: VpnConnectRequest): Result<Unit> = withContext(Dispatchers.IO) {
        _lastError.value = null
        if (VpnService.prepare(context) != null) {
            val err = VpnError(VpnErrorType.VPN_PERMISSION_DENIED, "Android VpnService consent not granted")
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(SecurityException(err.technicalDetail))
        }

        _status.value = ConnectionState.CONNECTING
        val goBackend = getOrCreateBackend().getOrElse { t ->
            val err = VpnError(
                VpnErrorType.ENGINE_INITIALIZATION_FAILURE,
                "WireGuard native GoBackend unavailable: ${t.message ?: t::class.simpleName}"
            )
            logger.error("WireGuard", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(t)
        }

        val normalizedConf = applyUserPreferencesToWireGuardConf(configuration)
        val parsedConfig = try {
            Config.parse(BufferedReader(StringReader(normalizedConf)))
        } catch (t: Throwable) {
            val err = VpnError(VpnErrorType.INVALID_CONFIGURATION, "WireGuard config parse failed: ${t.message}")
            logger.error("WireGuard", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(t)
        }

        return@withContext try {
            logger.engine("WireGuard", "Bringing up real WireGuard TUN tunnel to ${configuration.server.hostname}:${configuration.server.port}")
            val state = goBackend.setState(tunnel, Tunnel.State.UP, parsedConfig)
            if (state == Tunnel.State.UP) {
                connectedAtMs = System.currentTimeMillis()
                _status.value = ConnectionState.CONNECTED
                startStatisticsLoop(goBackend)
                Result.success(Unit)
            } else {
                val err = VpnError(VpnErrorType.TUNNEL_ESTABLISHMENT_FAILURE, "WireGuard tunnel returned state $state")
                _lastError.value = err
                _status.value = ConnectionState.ERROR
                Result.failure(IllegalStateException(err.technicalDetail))
            }
        } catch (be: BackendException) {
            val type = when (be.reason) {
                BackendException.Reason.VPN_NOT_AUTHORIZED -> VpnErrorType.VPN_PERMISSION_DENIED
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> VpnErrorType.DNS_FAILURE
                else -> VpnErrorType.TUNNEL_ESTABLISHMENT_FAILURE
            }
            val err = VpnError(type, "WireGuard backend error: ${be.reason}")
            logger.error("WireGuard", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            Result.failure(be)
        } catch (t: Throwable) {
            val err = VpnError(VpnErrorType.TUNNEL_ESTABLISHMENT_FAILURE, t.message ?: "WireGuard tunnel error")
            logger.error("WireGuard", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            Result.failure(t)
        }
    }

    private fun applyUserPreferencesToWireGuardConf(req: VpnConnectRequest): String {
        val raw = req.rawConfiguration
        val prefs = req.preferences
        val lines = raw.lines().toMutableList()
        val hasDns = lines.any { it.trim().startsWith("DNS", ignoreCase = true) }
        val interfaceIdx = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (interfaceIdx >= 0) {
            val additions = mutableListOf<String>()
            if (!hasDns && prefs.customDnsPrimary.isNotBlank()) {
                val dnsList = listOf(prefs.customDnsPrimary, prefs.customDnsSecondary).filter { it.isNotBlank() }.joinToString(", ")
                additions += "DNS = $dnsList"
            }
            when (prefs.splitTunnelMode) {
                SplitTunnelMode.BYPASS_SELECTED -> {
                    if (prefs.splitTunnelPackages.isNotEmpty()) {
                        additions += "ExcludedApplications = ${prefs.splitTunnelPackages.joinToString(", ")}"
                    }
                }
                SplitTunnelMode.ONLY_SELECTED -> {
                    if (prefs.splitTunnelPackages.isNotEmpty()) {
                        additions += "IncludedApplications = ${prefs.splitTunnelPackages.joinToString(", ")}"
                    }
                }
                SplitTunnelMode.DISABLED -> Unit
            }
            if (additions.isNotEmpty()) {
                lines.addAll(interfaceIdx + 1, additions)
            }
        }
        return lines.joinToString("\n")
    }

    private fun startStatisticsLoop(goBackend: GoBackend) {
        statsJob?.cancel()
        statsJob = scope.launch {
            var lastRx = 0L
            var lastTx = 0L
            while (isActive && _status.value == ConnectionState.CONNECTED) {
                val stats = runCatching { goBackend.getStatistics(tunnel) }.getOrNull()
                if (stats != null) {
                    val rx = stats.totalRx()
                    val tx = stats.totalTx()
                    val dlBps = (rx - lastRx).coerceAtLeast(0L)
                    val ulBps = (tx - lastTx).coerceAtLeast(0L)
                    lastRx = rx
                    lastTx = tx
                    _stats.value = EngineStatistics(
                        bytesReceived = rx,
                        bytesSent = tx,
                        downloadSpeedBps = dlBps,
                        uploadSpeedBps = ulBps,
                        connectedSinceMs = connectedAtMs,
                        durationMs = (System.currentTimeMillis() - connectedAtMs).coerceAtLeast(0L)
                    )
                }
                delay(1000L)
            }
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        statsJob?.cancel()
        statsJob = null
        if (_status.value == ConnectionState.CONNECTED || _status.value == ConnectionState.CONNECTING) {
            _status.value = ConnectionState.DISCONNECTING
        }
        runCatching {
            backend?.setState(tunnel, Tunnel.State.DOWN, null)
        }.onFailure {
            logger.warn("WireGuard", "Disconnect cleanup notice: ${it.message}")
        }
        _stats.value = EngineStatistics()
        _status.value = ConnectionState.DISCONNECTED
        logger.info("WireGuard", "WireGuard tunnel disconnected cleanly")
    }

    override suspend fun updateConfiguration(configuration: VpnConnectRequest): Result<Unit> {
        disconnect()
        return connect(configuration)
    }

    override fun getStatus(): StateFlow<ConnectionState> = _status.asStateFlow()
    override fun getStatistics(): StateFlow<EngineStatistics> = _stats.asStateFlow()
    override fun getLogs(): StateFlow<List<EngineLogEntry>> = logger.logs
    override fun getLastError(): StateFlow<VpnError?> = _lastError.asStateFlow()

    override fun dispose() {
        statsJob?.cancel()
        runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
        scope.cancel()
    }

    override fun runtimeInfo(): EngineRuntimeInfo = EngineRuntimeInfo(
        engineType = EngineType.WIREGUARD,
        name = "WireGuard GoBackend (Official)",
        isCoreAvailable = true,
        version = "1.0.20230706",
        statusNoteFa = "آماده اتصال واقعی (بسته‌بندی‌شده از Maven Central)",
        statusNoteEn = "Ready for real TUN connections (bundled from Maven Central)",
        license = "Apache-2.0"
    )
}
