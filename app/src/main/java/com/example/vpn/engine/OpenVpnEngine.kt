package com.example.vpn.engine

import android.content.Context
import android.net.TrafficStats
import android.net.VpnService
import android.os.Process
import com.example.core.logging.DiagnosticLogger
import com.example.domain.model.ConnectionState
import com.example.domain.model.EngineLogEntry
import com.example.domain.model.EngineRuntimeInfo
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnError
import com.example.domain.model.VpnErrorType
import com.example.domain.model.VpnProtocol
import com.example.domain.network.NetworkDiagnosticsService
import com.example.domain.parser.ConfigParser
import com.tim.basevpn.vpn.api.VpnClientApi
import com.tim.basevpn.vpn.api.VpnClients
import com.tim.basevpn.vpn.api.VpnConfig
import com.tim.basevpn.vpn.api.VpnState
import com.tim.openvpn.OpenVpnProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Engine B — Dedicated Native OpenVPN3 Engine (`io.github.tim06:vpnprotocols-openvpn:3.0.4`).
 * Completely isolated from sing-box: parses, sanitizes (strips script-security/up/down/plugin),
 * validates `.ovpn` profiles, and connects through bundled native `libovpn3.so` running in its
 * own `:openvpn` Android VpnService process.
 */
class OpenVpnEngine(
    private val context: Context,
    private val networkDiagnostics: NetworkDiagnosticsService,
    private val logger: DiagnosticLogger
) : VPNEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _stats = MutableStateFlow(EngineStatistics())
    private val _lastError = MutableStateFlow<VpnError?>(null)

    private var vpnClient: VpnClientApi? = null
    private var stateObserverJob: Job? = null
    private var statsJob: Job? = null
    private var connectedAtMs: Long = 0L

    override fun initialize() {
        logger.info("OpenVPN", "Native OpenVPN3 engine registered (bundled vpnprotocols-openvpn 3.0.4)")
    }

    private fun getOrCreateClient(): VpnClientApi {
        vpnClient?.let { return it }
        val client = VpnClients.create(
            context.applicationContext,
            scope,
            OpenVpnProtocol.descriptor
        )
        vpnClient = client
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            client.observeState().collect { state ->
                when (state.status) {
                    VpnState.Status.CONNECTED -> {
                        if (connectedAtMs == 0L) connectedAtMs = System.currentTimeMillis()
                        _status.value = ConnectionState.CONNECTED
                        startTrafficStatsLoop()
                    }
                    VpnState.Status.CONNECTING,
                    VpnState.Status.CONNECTING_IPC -> {
                        if (_status.value != ConnectionState.DISCONNECTING) {
                            _status.value = ConnectionState.CONNECTING
                        }
                    }
                    VpnState.Status.DISCONNECTING -> {
                        _status.value = ConnectionState.DISCONNECTING
                    }
                    VpnState.Status.DISCONNECTED -> {
                        statsJob?.cancel()
                        statsJob = null
                        connectedAtMs = 0L
                        if (_status.value != ConnectionState.ERROR) {
                            _status.value = ConnectionState.DISCONNECTED
                        }
                    }
                }
            }
        }
        return client
    }

    override suspend fun connect(configuration: VpnConnectRequest): Result<Unit> = withContext(Dispatchers.IO) {
        _lastError.value = null
        if (configuration.protocol != VpnProtocol.OPENVPN) {
            val err = VpnError(VpnErrorType.UNSUPPORTED_PROTOCOL, "OpenVpnEngine only accepts OpenVPN .ovpn profiles")
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(IllegalArgumentException(err.technicalDetail))
        }

        if (VpnService.prepare(context) != null) {
            val err = VpnError(VpnErrorType.VPN_PERMISSION_DENIED, "Android VPN consent not granted")
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(SecurityException(err.technicalDetail))
        }

        _status.value = ConnectionState.CONNECTING

        // 1. Re-parse & strip dangerous executable script directives before execution
        val parsed = ConfigParser.parseOpenVpnProfile(configuration.rawConfiguration)
        if (parsed.validationStatus == ValidationStatus.INVALID) {
            val err = VpnError(VpnErrorType.INVALID_CONFIGURATION, parsed.validationMessage)
            logger.error("OpenVPN", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(IllegalArgumentException(err.technicalDetail))
        }
        if (parsed.validationStatus == ValidationStatus.WARNING) {
            logger.warn("OpenVPN", parsed.validationMessage)
        }

        // 2. Real TCP reachability verification on OpenVPN endpoint (only when proto tcp; UDP relays skip TCP fail)
        val isUdp = parsed.rawConfigData.contains("proto udp", ignoreCase = true)
        if (!isUdp) {
            val probe = networkDiagnostics.probeServer(
                hostname = parsed.hostname,
                port = parsed.port,
                useTlsHandshake = false,
                timeoutMs = 3500
            )
            if (!probe.reachable) {
                val err = VpnError(
                    VpnErrorType.SERVER_TIMEOUT,
                    "OpenVPN remote ${parsed.hostname}:${parsed.port} did not respond (${probe.error})"
                )
                logger.error("OpenVPN", err.technicalDetail)
                _lastError.value = err
                _status.value = ConnectionState.ERROR
                return@withContext Result.failure(IllegalStateException(err.technicalDetail))
            }
        }

        // 3. Connect via bundled native OpenVPN3 engine (`libovpn3.so`)
        return@withContext try {
            val client = getOrCreateClient()
            val sanitizedConfig = ensureCompatibleOvpnConfig(parsed.rawConfigData)
            logger.engine("OpenVPN", "Starting native OpenVPN3 tunnel to ${parsed.hostname}:${parsed.port}")
            client.connect(VpnConfig(sanitizedConfig))

            val timeoutMs = (configuration.preferences.connectionTimeoutSec.coerceIn(8, 25)) * 1000L
            val reachedState = withTimeoutOrNull(timeoutMs) {
                client.observeState().first { st ->
                    st.status == VpnState.Status.CONNECTED ||
                        (st.status == VpnState.Status.DISCONNECTED && !st.startBlockedReason.isNullOrBlank())
                }
            }

            if (reachedState?.status == VpnState.Status.CONNECTED || _status.value == ConnectionState.CONNECTED) {
                connectedAtMs = System.currentTimeMillis()
                _status.value = ConnectionState.CONNECTED
                startTrafficStatsLoop()
                logger.engine("OpenVPN", "Native OpenVPN3 tunnel connected")
                Result.success(Unit)
            } else {
                runCatching { client.disconnect() }
                val reason = reachedState?.startBlockedReason?.takeIf { it.isNotBlank() }
                    ?: "OpenVPN3 handshake timed out after ${timeoutMs / 1000}s"
                val err = VpnError(VpnErrorType.TUNNEL_ESTABLISHMENT_FAILURE, reason)
                logger.error("OpenVPN", err.technicalDetail)
                _lastError.value = err
                _status.value = ConnectionState.ERROR
                Result.failure(IllegalStateException(reason))
            }
        } catch (t: Throwable) {
            runCatching { vpnClient?.disconnect() }
            val err = VpnError(
                VpnErrorType.TUNNEL_ESTABLISHMENT_FAILURE,
                "OpenVPN3 error: ${t.cause?.message ?: t.message ?: t::class.simpleName}"
            )
            logger.error("OpenVPN", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            Result.failure(t)
        }
    }

    private fun ensureCompatibleOvpnConfig(raw: String): String {
        val sb = StringBuilder(raw.trim())
        if (!raw.contains("redirect-gateway", ignoreCase = true)) {
            sb.append("\nredirect-gateway def1 bypass-dhcp")
        }
        return sb.toString()
    }

    private fun startTrafficStatsLoop() {
        if (statsJob?.isActive == true) return
        val uid = Process.myUid()
        val baseRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        val baseTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        statsJob = scope.launch {
            var prevRx = baseRx
            var prevTx = baseTx
            while (isActive && _status.value == ConnectionState.CONNECTED) {
                val curRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(baseRx)
                val curTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(baseTx)
                val dlBps = (curRx - prevRx).coerceAtLeast(0L)
                val ulBps = (curTx - prevTx).coerceAtLeast(0L)
                prevRx = curRx
                prevTx = curTx
                _stats.value = EngineStatistics(
                    bytesReceived = (curRx - baseRx).coerceAtLeast(0L),
                    bytesSent = (curTx - baseTx).coerceAtLeast(0L),
                    downloadSpeedBps = dlBps,
                    uploadSpeedBps = ulBps,
                    connectedSinceMs = connectedAtMs,
                    durationMs = (System.currentTimeMillis() - connectedAtMs).coerceAtLeast(0L)
                )
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
        runCatching { vpnClient?.disconnect() }
        connectedAtMs = 0L
        _stats.value = EngineStatistics()
        _status.value = ConnectionState.DISCONNECTED
        logger.info("OpenVPN", "OpenVPN3 tunnel disconnected cleanly")
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
        stateObserverJob?.cancel()
        scope.launch { runCatching { vpnClient?.disconnect() } }
        scope.cancel()
    }

    override fun runtimeInfo(): EngineRuntimeInfo = EngineRuntimeInfo(
        engineType = EngineType.OPENVPN,
        name = "OpenVPN3 (Native)",
        isCoreAvailable = true,
        version = "3.0.4 (bundled libovpn3.so)",
        statusNoteFa = "هسته بومی OpenVPN3 آماده اتصال واقعی (جدا از sing-box)",
        statusNoteEn = "Native OpenVPN3 core bundled and ready (strictly isolated from sing-box)",
        license = "AGPL-3.0 / Apache-2.0"
    )
}
