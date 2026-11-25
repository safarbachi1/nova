package com.example.di

import android.content.Context
import com.example.core.logging.DiagnosticLogger
import com.example.core.security.CryptoVault
import com.example.data.local.NovaVpnDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.NovaVpnRepository
import com.example.domain.discovery.DiscoveryEngine
import com.example.domain.network.NetworkDiagnosticsService
import com.example.vpn.engine.EngineManager
import com.example.vpn.engine.OpenVpnEngine
import com.example.vpn.engine.SingBoxVpnEngine
import com.example.vpn.engine.WireGuardVpnEngine
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val logger = DiagnosticLogger()
    val cryptoVault = CryptoVault()
    val database = NovaVpnDatabase.getInstance(appContext)
    val preferencesRepository = UserPreferencesRepository(appContext)
    val repository = NovaVpnRepository(database, cryptoVault)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val networkDiagnostics = NetworkDiagnosticsService(httpClient, logger)
    val discoveryEngine = DiscoveryEngine(repository, networkDiagnostics, httpClient, logger)

    val wireGuardEngine = WireGuardVpnEngine(appContext, logger)
    val singBoxEngine = SingBoxVpnEngine(appContext, networkDiagnostics, logger)
    val openVpnEngine = OpenVpnEngine(appContext, networkDiagnostics, logger)

    val engineManager = EngineManager(
        context = appContext,
        repository = repository,
        prefsRepo = preferencesRepository,
        networkDiagnostics = networkDiagnostics,
        logger = logger,
        singBoxEngine = singBoxEngine,
        openVpnEngine = openVpnEngine,
        wireGuardEngine = wireGuardEngine
    )
}
