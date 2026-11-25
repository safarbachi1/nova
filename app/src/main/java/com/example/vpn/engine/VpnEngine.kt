package com.example.vpn.engine

import com.example.data.local.entity.ServerEntity
import com.example.data.preferences.UserPreferences
import com.example.domain.model.ConnectionState
import com.example.domain.model.EngineLogEntry
import com.example.domain.model.EngineRuntimeInfo
import com.example.domain.model.EngineStatistics
import com.example.domain.model.VpnError
import com.example.domain.model.VpnProtocol
import kotlinx.coroutines.flow.StateFlow

data class VpnConnectRequest(
    val server: ServerEntity,
    val protocol: VpnProtocol,
    val rawConfiguration: String,
    val preferences: UserPreferences
)

/**
 * Common multi-engine VPN interface implemented independently by Sing-box, OpenVPN, and WireGuard engines.
 */
interface VPNEngine {
    fun initialize()
    suspend fun connect(configuration: VpnConnectRequest): Result<Unit>
    suspend fun disconnect()
    fun getStatus(): StateFlow<ConnectionState>
    fun getStatistics(): StateFlow<EngineStatistics>
    fun getLogs(): StateFlow<List<EngineLogEntry>>
    suspend fun updateConfiguration(configuration: VpnConnectRequest): Result<Unit>
    fun dispose()
    fun runtimeInfo(): EngineRuntimeInfo
    fun getLastError(): StateFlow<VpnError?>
}
