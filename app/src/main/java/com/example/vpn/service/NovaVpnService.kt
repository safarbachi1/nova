package com.example.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NovaVpnApplication
import com.example.R
import com.example.data.preferences.UserPreferences
import com.example.domain.model.SplitTunnelMode
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Real Android VpnService hosting the TUN interface, foreground notification, socket protection,
 * DNS/IPv4/IPv6/split-tunnel routing, and ConnectivityManager network-change callbacks.
 *
 * IMPORTANT TUN FD LIFECYCLE:
 * `libbox` / `sing-tun` calls `syscall.Dup(tunFd)` internally inside Go. Therefore this service
 * MUST retain the original `ParcelFileDescriptor` (`tunInterface`) and expose `pfd.fd` (NOT
 * `detachFd()`), then explicitly close `tunInterface` when disconnecting so Android immediately
 * tears down the kernel TUN device and clears the system VPN key icon.
 */
class NovaVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "nova_vpn_tunnel_channel"
        const val NOTIFICATION_ID = 1042
        const val ACTION_START = "com.example.vpn.ACTION_START"
        const val ACTION_USER_DISCONNECT = "com.example.vpn.ACTION_USER_DISCONNECT"
        const val EXTRA_SERVER_LABEL = "extra_server_label"
        const val EXTRA_PROTOCOL = "extra_protocol"

        private val lock = Any()

        @Volatile
        var activeInstance: NovaVpnService? = null
            private set

        @Volatile
        private var readyDeferred: CompletableDeferred<NovaVpnService> = CompletableDeferred()

        fun startForegroundService(context: Context, serverLabel: String, protocol: String) {
            synchronized(lock) {
                val existing = activeInstance
                if (existing != null) {
                    if (!readyDeferred.isCompleted) readyDeferred.complete(existing)
                } else if (readyDeferred.isCompleted) {
                    readyDeferred = CompletableDeferred()
                }
            }
            val intent = Intent(context, NovaVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_LABEL, serverLabel)
                putExtra(EXTRA_PROTOCOL, protocol)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        suspend fun awaitActiveInstance(timeoutMs: Long = 3500L): NovaVpnService? {
            activeInstance?.let { return it }
            val deferred = synchronized(lock) {
                activeInstance?.let { return it }
                if (readyDeferred.isCompleted) {
                    readyDeferred = CompletableDeferred()
                }
                readyDeferred
            }
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: activeInstance
        }

        /**
         * Stops the running TUN interface and foreground service directly from the VPN engine
         * WITHOUT recursively invoking `engineManager.disconnect()`.
         */
        fun stopTunnelFromEngine(context: Context) {
            val instance = activeInstance
            if (instance != null) {
                instance.teardownTunAndStop()
            } else {
                runCatching { context.stopService(Intent(context, NovaVpnService::class.java)) }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        synchronized(lock) {
            activeInstance = this
            if (!readyDeferred.isCompleted) {
                readyDeferred.complete(this)
            }
        }
        registerNetworkChangeCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_USER_DISCONNECT -> {
                // User tapped "Disconnect" on the persistent VPN notification
                serviceScope.launch {
                    (application as? NovaVpnApplication)?.container?.engineManager?.disconnect()
                }
                teardownTunAndStop()
                return START_NOT_STICKY
            }
            else -> {
                val label = intent?.getStringExtra(EXTRA_SERVER_LABEL).orEmpty().ifBlank { "Nova VPN" }
                val proto = intent?.getStringExtra(EXTRA_PROTOCOL).orEmpty().ifBlank { "VPN" }
                val notification = buildNotification(
                    title = getString(R.string.vpn_notification_connected, label, proto),
                    detail = proto
                )
                promoteToForeground(notification)
                synchronized(lock) {
                    activeInstance = this
                    if (!readyDeferred.isCompleted) {
                        readyDeferred.complete(this)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Builds and establishes an Android TUN interface directly from sing-box TunOptions.
     * Retains the ParcelFileDescriptor so [closeTun] can deterministically release the kernel
     * TUN handle alongside libbox's duplicated fd on disconnect.
     */
    fun establishTunFromLibbox(
        options: TunOptions,
        sessionName: String,
        prefs: UserPreferences
    ): ParcelFileDescriptor? {
        closeTun()
        val builder = Builder()
            .setSession(sessionName.ifBlank { "Nova VPN" })
            .setMtu(options.mtu.takeIf { it in 1280..1500 } ?: 1500)

        var addedAddress = false
        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val prefix = inet4.next()
            builder.addAddress(prefix.address(), prefix.prefix())
            addedAddress = true
        }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val prefix = inet6.next()
            runCatching {
                builder.addAddress(prefix.address(), prefix.prefix())
                addedAddress = true
            }
        }
        if (!addedAddress) {
            builder.addAddress("172.19.0.1", 30)
        }

        if (options.autoRoute) {
            var addedDns = false
            runCatching {
                val dnsAddr = options.dnsServerAddress?.value
                if (!dnsAddr.isNullOrBlank()) {
                    builder.addDnsServer(dnsAddr)
                    addedDns = true
                }
            }
            if (!addedDns && prefs.customDnsPrimary.isNotBlank()) {
                runCatching { builder.addDnsServer(prefs.customDnsPrimary) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var addedRoute = false
                val r4 = options.inet4RouteAddress
                while (r4.hasNext()) {
                    val p = r4.next()
                    builder.addRoute(p.address(), p.prefix())
                    addedRoute = true
                }
                val r6 = options.inet6RouteAddress
                while (r6.hasNext()) {
                    val p = r6.next()
                    runCatching {
                        builder.addRoute(p.address(), p.prefix())
                        addedRoute = true
                    }
                }
                val ex4 = options.inet4RouteExcludeAddress
                while (ex4.hasNext()) {
                    val p = ex4.next()
                    runCatching {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(p.address()), p.prefix()))
                    }
                }
                val ex6 = options.inet6RouteExcludeAddress
                while (ex6.hasNext()) {
                    val p = ex6.next()
                    runCatching {
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(p.address()), p.prefix()))
                    }
                }
                if (!addedRoute) {
                    builder.addRoute("0.0.0.0", 0)
                    if (prefs.ipv6Enabled) runCatching { builder.addRoute("::", 0) }
                }
            } else {
                val range4 = options.inet4RouteRange
                var addedRoute = false
                while (range4.hasNext()) {
                    val p = range4.next()
                    builder.addRoute(p.address(), p.prefix())
                    addedRoute = true
                }
                val range6 = options.inet6RouteRange
                while (range6.hasNext()) {
                    val p = range6.next()
                    runCatching {
                        builder.addRoute(p.address(), p.prefix())
                        addedRoute = true
                    }
                }
                if (!addedRoute) {
                    builder.addRoute("0.0.0.0", 0)
                    if (prefs.ipv6Enabled) runCatching { builder.addRoute("::", 0) }
                }
            }
        }

        val selectedPackages = prefs.splitTunnelPackages.filter { it.isNotBlank() }
        when {
            prefs.splitTunnelMode == SplitTunnelMode.ONLY_SELECTED && selectedPackages.isNotEmpty() -> {
                selectedPackages.forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                }
            }
            prefs.splitTunnelMode == SplitTunnelMode.BYPASS_SELECTED -> {
                selectedPackages.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
            else -> Unit
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val pfd = builder.establish() ?: return null
        tunInterface = pfd
        // Bind VPN to active physical underlying network immediately
        val cm = getSystemService(ConnectivityManager::class.java)
        val activeNet = cm?.activeNetwork
        if (activeNet != null) {
            val caps = cm.getNetworkCapabilities(activeNet)
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                runCatching { setUnderlyingNetworks(arrayOf(activeNet)) }
            }
        }
        return pfd
    }

    fun bindUnderlyingNetwork(network: Network?) {
        runCatching {
            if (network != null) {
                setUnderlyingNetworks(arrayOf(network))
            } else {
                setUnderlyingNetworks(null)
            }
        }
    }

    fun protectSocketFd(fd: Int): Boolean = protect(fd)

    fun updateNotification(statusText: String, detailText: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(statusText, detailText))
    }

    @Synchronized
    fun closeTun() {
        val pfd = tunInterface
        tunInterface = null
        if (pfd != null) {
            runCatching { pfd.close() }
        }
    }

    fun teardownTunAndStop() {
        closeTun()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
        }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    override fun onRevoke() {
        (application as? NovaVpnApplication)?.container?.logger?.warn(
            "VpnService",
            "System revoked VPN permission; closing tunnel cleanly"
        )
        closeTun()
        serviceScope.launch {
            (application as? NovaVpnApplication)?.container?.engineManager?.onSystemVpnRevoked()
        }
        teardownTunAndStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        unregisterNetworkChangeCallback()
        closeTun()
        synchronized(lock) {
            if (activeInstance === this) {
                activeInstance = null
                readyDeferred = CompletableDeferred()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, detail: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NovaVpnService::class.java).apply { action = ACTION_USER_DISCONNECT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.vpn_notification_disconnect), stopIntent)
            .build()
    }

    private fun registerNetworkChangeCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                bindUnderlyingNetwork(network)
                serviceScope.launch {
                    (application as? NovaVpnApplication)?.container?.engineManager?.onUnderlyingNetworkChanged(true)
                }
            }

            override fun onLost(network: Network) {
                serviceScope.launch {
                    (application as? NovaVpnApplication)?.container?.engineManager?.onUnderlyingNetworkChanged(false)
                }
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(req, callback)
            networkCallback = callback
        }
    }

    private fun unregisterNetworkChangeCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
    }
}
