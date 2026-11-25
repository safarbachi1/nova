package com.example

import android.app.Application
import android.os.Build
import com.example.di.AppContainer
import com.example.work.DiscoveryWorkScheduler
import com.tim.openvpn.init.initializeOpenVpnLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NovaVpnApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Initialize bundled OpenVPN3 library in both main and :openvpn processes
        runCatching { initializeOpenVpnLibrary() }

        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            ""
        }
        if (processName.endsWith(":openvpn")) {
            return
        }

        container = AppContainer(this)
        appScope.launch {
            container.repository.ensureDefaultSourcesSeeded()
            val prefs = container.preferencesRepository.preferencesFlow.first()
            DiscoveryWorkScheduler.scheduleOrCancel(
                context = this@NovaVpnApplication,
                enabled = prefs.autoServerRefresh,
                intervalHours = prefs.refreshIntervalHours
            )
            container.engineManager.refreshPublicIp()
        }
    }
}
