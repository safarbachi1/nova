package com.example.vpn.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Process
import com.example.core.logging.DiagnosticLogger
import com.example.domain.model.ConnectionState
import com.example.domain.model.EngineLogEntry
import com.example.domain.model.EngineRuntimeInfo
import com.example.domain.model.EngineStatistics
import com.example.domain.model.EngineType
import com.example.domain.model.VpnError
import com.example.domain.model.VpnErrorType
import com.example.domain.model.VpnProtocol
import com.example.domain.network.NetworkDiagnosticsService
import com.example.domain.parser.SingBoxConfigGenerator
import com.example.vpn.service.NovaVpnService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
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
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException

/**
 * Engine A — Native sing-box Core Engine (bundled via `net.clever-vpn:libbox-android:2.1.4`).
 * Directly integrates `io.nekohasekai.libbox.Libbox`, `CommandServer`, and `PlatformInterface`
 * for VLESS (including Reality/TLS), VMess, Trojan, Shadowsocks, Hysteria, Hysteria2, TUIC,
 * WireGuard, SOCKS, HTTP, and Sing-box JSON.
 *
 * Strictly rejects OpenVPN profiles (OpenVPN is dispatched exclusively to OpenVpnEngine).
 */
class SingBoxVpnEngine(
    private val context: Context,
    private val networkDiagnostics: NetworkDiagnosticsService,
    private val logger: DiagnosticLogger
) : VPNEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _stats = MutableStateFlow(EngineStatistics())
    private val _lastError = MutableStateFlow<VpnError?>(null)

    @Volatile
    private var libboxInitialized = false
    private var commandServer: CommandServer? = null
    private var defaultNetCallback: ConnectivityManager.NetworkCallback? = null
    private var statsJob: Job? = null
    private var connectedAtMs: Long = 0L

    override fun initialize() {
        logger.info("SingBox", "sing-box native libbox-android engine registered (lazy JNI setup)")
    }

    private fun ensureLibboxSetup(): Result<Unit> = runCatching {
        if (libboxInitialized) return@runCatching
        val baseDir = File(context.filesDir, "singbox").apply { mkdirs() }
        val workDir = File(baseDir, "working").apply { mkdirs() }
        val tempDir = File(context.cacheDir, "singbox_tmp").apply { mkdirs() }
        val options = SetupOptions().apply {
            basePath = baseDir.absolutePath
            workingPath = workDir.absolutePath
            tempPath = tempDir.absolutePath
            fixAndroidStack = true
            logMaxLines = 300
        }
        Libbox.setup(options)
        Libbox.setMemoryLimit(true)
        libboxInitialized = true
        logger.engine("SingBox", "Initialized native sing-box core ${runCatching { Libbox.version() }.getOrDefault("libbox")}")
    }

    override suspend fun connect(configuration: VpnConnectRequest): Result<Unit> = withContext(Dispatchers.IO) {
        _lastError.value = null
        if (configuration.protocol == VpnProtocol.OPENVPN) {
            val err = VpnError(
                VpnErrorType.UNSUPPORTED_PROTOCOL,
                "OpenVPN must be routed through OpenVpnEngine, never sing-box"
            )
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

        // 1. Initialize native libbox JNI lazily
        ensureLibboxSetup().getOrElse { ex ->
            val err = VpnError(
                VpnErrorType.ENGINE_INITIALIZATION_FAILURE,
                "sing-box native initialization failed: ${ex.message ?: ex::class.simpleName}"
            )
            logger.error("SingBox", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(ex)
        }

        // 2. Compile sing-box 1.12+ JSON configuration
        val compiledJson = SingBoxConfigGenerator.generateSingBoxJson(
            protocol = configuration.protocol,
            rawConfig = configuration.rawConfiguration,
            prefs = configuration.preferences
        ).getOrElse { ex ->
            val err = VpnError(VpnErrorType.INVALID_CONFIGURATION, ex.message ?: "Failed to compile sing-box JSON")
            logger.error("SingBox", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(ex)
        }

        // 3. Validate JSON directly with native Libbox.checkConfig before opening TUN
        runCatching { Libbox.checkConfig(compiledJson) }.getOrElse { ex ->
            val err = VpnError(VpnErrorType.INVALID_CONFIGURATION, "sing-box config validation failed: ${ex.message}")
            logger.error("SingBox", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(ex)
        }
        logger.engine("SingBox", "Validated sing-box JSON (${compiledJson.length} chars) for ${configuration.protocol.displayName}")

        // 4. Real DNS + TCP/TLS pre-flight verification
        try {
            InetAddress.getByName(configuration.server.hostname)
        } catch (dnsEx: UnknownHostException) {
            val err = VpnError(VpnErrorType.DNS_FAILURE, "Unable to resolve ${configuration.server.hostname}")
            logger.error("SingBox", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(dnsEx)
        }

        val probe = networkDiagnostics.probeServer(
            hostname = configuration.server.hostname,
            port = configuration.server.port,
            useTlsHandshake = false,
            timeoutMs = (configuration.preferences.connectionTimeoutSec * 1000).coerceAtMost(3500)
        )
        if (!probe.reachable) {
            val errType = if (probe.error.contains("SSL", true) || probe.error.contains("TLS", true)) {
                VpnErrorType.TLS_HANDSHAKE_FAILURE
            } else {
                VpnErrorType.SERVER_TIMEOUT
            }
            val err = VpnError(
                errType,
                "Endpoint ${configuration.server.hostname}:${configuration.server.port} unreachable (${probe.error})"
            )
            logger.error("SingBox", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            return@withContext Result.failure(IllegalStateException(err.technicalDetail))
        }

        // 5. Launch Android VpnService and start real native libbox CommandServer + Service
        return@withContext startRealLibboxTunnel(compiledJson, configuration)
    }

    private suspend fun startRealLibboxTunnel(
        configJson: String,
        req: VpnConnectRequest
    ): Result<Unit> {
        return try {
            // Ensure any stale instance is fully closed before creating a new TUN
            stopInternalResources()

            NovaVpnService.startForegroundService(
                context,
                req.server.displayName.ifBlank { req.server.hostname },
                req.protocol.displayName
            )
            val vpnService = NovaVpnService.awaitActiveInstance(timeoutMs = 3500L)
                ?: throw IllegalStateException("NovaVpnService failed to start in foreground")

            val handler = object : CommandServerHandler {
                override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
                    available = false
                    enabled = false
                }
                override fun serviceReload() {
                    logger.engine("libbox", "Reload requested by CommandServer")
                }
                override fun serviceStop() {
                    scope.launch { disconnect() }
                }
                override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
                override fun writeDebugMessage(message: String?) {
                    if (!message.isNullOrBlank()) logger.engine("libbox", message)
                }
            }

            val platformInterface = createPlatformInterface(vpnService, req)
            val server = Libbox.newCommandServer(handler, platformInterface)
            commandServer = server
            server.startWithTemporaryPort()
            server.startOrReloadService(configJson, OverrideOptions())

            connectedAtMs = System.currentTimeMillis()
            _status.value = ConnectionState.CONNECTED
            startTrafficStatsLoop()
            logger.engine("SingBox", "Real sing-box TUN tunnel established via native libbox")
            Result.success(Unit)
        } catch (t: Throwable) {
            stopInternalResources()
            val detail = t.cause?.message ?: t.message ?: t::class.java.simpleName
            val errType = when {
                detail.contains("tls", true) || detail.contains("handshake", true) -> VpnErrorType.TLS_HANDSHAKE_FAILURE
                detail.contains("auth", true) || detail.contains("password", true) -> VpnErrorType.AUTHENTICATION_FAILURE
                else -> VpnErrorType.TUNNEL_ESTABLISHMENT_FAILURE
            }
            val err = VpnError(errType, "sing-box tunnel error: $detail")
            logger.error("SingBox", err.technicalDetail)
            _lastError.value = err
            _status.value = ConnectionState.ERROR
            Result.failure(t)
        }
    }

    private fun createPlatformInterface(
        vpnService: NovaVpnService,
        req: VpnConnectRequest
    ): PlatformInterface = object : PlatformInterface {
        override fun autoDetectInterfaceControl(fd: Int) {
            val ok = vpnService.protectSocketFd(fd)
            if (!ok) {
                logger.warn("SingBox", "VpnService.protect($fd) returned false")
            }
        }

        override fun openTun(options: TunOptions): Int {
            val pfd = vpnService.establishTunFromLibbox(
                options = options,
                sessionName = "Nova VPN (${req.protocol.displayName})",
                prefs = req.preferences
            ) ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
            // libbox / sing-tun calls syscall.Dup(fd) internally; return pfd.fd (NOT detachFd)
            // so NovaVpnService retains ownership and cleanly closes the original TUN fd on disconnect.
            return pfd.fd
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun useProcFS(): Boolean = false
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun clearDNSCache() = Unit
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun readWIFIState(): WIFIState? = null

        override fun sendNotification(notification: Notification) {
            logger.info("libbox", "${notification.title}: ${notification.body}")
        }

        override fun systemCertificates(): StringIterator = ListStringIterator(emptyList())

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            pushNetworkToListener(cm, cm.activeNetwork, listener)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    pushNetworkToListener(cm, network, listener)
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    pushNetworkToListener(cm, network, listener)
                }
                override fun onLost(network: Network) {
                    pushNetworkToListener(cm, cm.activeNetwork, listener)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            runCatching {
                cm.registerNetworkCallback(request, cb)
                defaultNetCallback = cb
            }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            defaultNetCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            defaultNetCallback = null
        }

        override fun getInterfaces(): NetworkInterfaceIterator {
            val result = mutableListOf<NetworkInterface>()
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val allNetworks = cm?.allNetworks ?: emptyArray()
            for (net in allNetworks) {
                val caps = cm?.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val link = cm.getLinkProperties(net) ?: continue
                val ifaceName = link.interfaceName ?: continue
                val sysIface = runCatching { java.net.NetworkInterface.getByName(ifaceName) }.getOrNull()
                val addresses = link.linkAddresses.mapNotNull { la ->
                    val hostAddr = la.address?.hostAddress?.substringBefore("%") ?: return@mapNotNull null
                    "$hostAddr/${la.prefixLength}"
                }
                val dnsServers = link.dnsServers.mapNotNull { it.hostAddress?.substringBefore("%") }
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                result += NetworkInterface().apply {
                    name = ifaceName
                    index = sysIface?.index ?: 0
                    mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) link.mtu.takeIf { it > 0 } ?: 1500 else 1500
                    this.type = type
                    this.addresses = ListStringIterator(addresses)
                    dnsServer = ListStringIterator(dnsServers)
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    flags = computeInterfaceFlags(sysIface)
                }
            }
            return ListNetworkInterfaceIterator(result)
        }

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int
        ): ConnectionOwner {
            val owner = ConnectionOwner()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val cm = context.getSystemService(ConnectivityManager::class.java)
                    val uid = cm?.getConnectionOwnerUid(
                        ipProtocol,
                        InetSocketAddress(InetAddress.getByName(sourceAddress), sourcePort),
                        InetSocketAddress(InetAddress.getByName(destinationAddress), destinationPort)
                    ) ?: Process.INVALID_UID
                    if (uid != Process.INVALID_UID) {
                        owner.userId = uid
                        owner.androidPackageName = context.packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
                    }
                }
            }
            return owner
        }
    }

    private fun pushNetworkToListener(
        cm: ConnectivityManager,
        network: Network?,
        listener: InterfaceUpdateListener
    ) {
        if (network == null) {
            runCatching { listener.updateDefaultInterface("", -1, false, false) }
            return
        }
        val caps = cm.getNetworkCapabilities(network)
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        NovaVpnService.activeInstance?.bindUnderlyingNetwork(network)
        val link = cm.getLinkProperties(network)
        val ifaceName = link?.interfaceName.orEmpty()
        if (ifaceName.isBlank()) return
        val ifaceIndex = runCatching { java.net.NetworkInterface.getByName(ifaceName)?.index ?: 0 }.getOrDefault(0)
        val isExpensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        val isConstrained = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED) == false
        runCatching {
            listener.updateDefaultInterface(ifaceName, ifaceIndex, isExpensive, isConstrained)
        }
    }

    private fun computeInterfaceFlags(iface: java.net.NetworkInterface?): Int {
        if (iface == null) return 0
        var flags = 0
        runCatching {
            if (iface.isUp) flags = flags or 1
            if (iface.isLoopback) flags = flags or 4
            if (iface.isPointToPoint) flags = flags or 8
            if (iface.supportsMulticast()) flags = flags or 16
        }
        return flags
    }

    private fun startTrafficStatsLoop() {
        statsJob?.cancel()
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

    private fun stopInternalResources() {
        statsJob?.cancel()
        statsJob = null
        val cm = context.getSystemService(ConnectivityManager::class.java)
        defaultNetCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        defaultNetCallback = null
        val srv = commandServer
        commandServer = null
        if (srv != null) {
            runCatching { srv.closeService() }
            runCatching { srv.close() }
        }
        // Explicitly close the original Android TUN ParcelFileDescriptor & stop foreground service
        // so the kernel TUN interface and system VPN key icon disappear immediately.
        NovaVpnService.stopTunnelFromEngine(context)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (_status.value == ConnectionState.CONNECTED || _status.value == ConnectionState.CONNECTING) {
            _status.value = ConnectionState.DISCONNECTING
        }
        stopInternalResources()
        _stats.value = EngineStatistics()
        _status.value = ConnectionState.DISCONNECTED
        logger.info("SingBox", "sing-box tunnel disconnected cleanly")
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
        stopInternalResources()
        scope.cancel()
    }

    override fun runtimeInfo(): EngineRuntimeInfo = EngineRuntimeInfo(
        engineType = EngineType.SING_BOX,
        name = "sing-box (libbox-android)",
        isCoreAvailable = true,
        version = "2.1.4 (bundled native AAR)",
        statusNoteFa = "هسته بومی sing-box آماده اتصال واقعی (VLESS / VMess / Trojan / SS / Hysteria2 / TUIC)",
        statusNoteEn = "Native sing-box core bundled and ready (VLESS / VMess / Trojan / SS / Hysteria2 / TUIC)",
        license = "GPL-3.0-or-later"
    )

    private class ListStringIterator(private val items: List<String>) : StringIterator {
        private var idx = 0
        override fun hasNext(): Boolean = idx < items.size
        override fun len(): Int = items.size
        override fun next(): String = items[idx++]
    }

    private class ListNetworkInterfaceIterator(private val items: List<NetworkInterface>) : NetworkInterfaceIterator {
        private var idx = 0
        override fun hasNext(): Boolean = idx < items.size
        override fun next(): NetworkInterface = items[idx++]
    }
}
