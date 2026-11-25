package com.example.data.repository

import com.example.core.security.CryptoVault
import com.example.core.security.NetworkSecurityValidator
import com.example.data.local.NovaVpnDatabase
import com.example.data.local.entity.ConfigurationEntity
import com.example.data.local.entity.ConnectionHistoryEntity
import com.example.data.local.entity.ServerEntity
import com.example.data.local.entity.ServerWithConfiguration
import com.example.data.local.entity.SourceEntity
import com.example.data.local.entity.SpeedTestEntity
import com.example.domain.model.ServerStatus
import com.example.domain.model.SourceType
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnProtocol
import kotlinx.coroutines.flow.Flow

data class DecryptedServerProfile(
    val server: ServerEntity,
    val configuration: ConfigurationEntity,
    val rawConfigData: String
)

class NovaVpnRepository(
    private val db: NovaVpnDatabase,
    private val cryptoVault: CryptoVault
) {
    val serversWithConfigurations: Flow<List<ServerWithConfiguration>> =
        db.serverDao().observeServersWithConfigurations()

    val sources: Flow<List<SourceEntity>> = db.sourceDao().observeAll()
    val connectionHistory: Flow<List<ConnectionHistoryEntity>> = db.connectionHistoryDao().observeRecent()
    val speedTestHistory: Flow<List<SpeedTestEntity>> = db.speedTestDao().observeHistory()
    val totalBytesReceived: Flow<Long> = db.connectionHistoryDao().observeTotalBytesReceived()
    val totalBytesSent: Flow<Long> = db.connectionHistoryDao().observeTotalBytesSent()

    /**
     * Ensures the default configurable allowlist of real public HTTPS registries exists.
     * Uses OnConflictStrategy.IGNORE on unique sourceUrl so upgrading users receive new
     * curated sources without duplicating or altering existing toggles.
     * Note: No server records, credentials, or IPs are ever fabricated or pre-seeded.
     */
    suspend fun ensureDefaultSourcesSeeded() {
        val curatedAllowlist = listOf(
            SourceEntity(
                name = "VPNGate Academic Public OpenVPN Directory",
                sourceUrl = "https://www.vpngate.net/api/iphone/",
                sourceType = SourceType.OPENVPN_FEED,
                enabled = true,
                reliabilityScore = 0.88f
            ),
            SourceEntity(
                name = "Public EU V2Ray/Sing-box Registry (barry-far)",
                sourceUrl = "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt",
                sourceType = SourceType.SUBSCRIPTION,
                enabled = true,
                reliabilityScore = 0.75f
            ),
            SourceEntity(
                name = "Public Multi-Protocol Subscription (ebrasha)",
                sourceUrl = "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/all_extracted_configs.txt",
                sourceType = SourceType.MIXED_LINKS,
                enabled = true,
                reliabilityScore = 0.70f
            )
        )
        curatedAllowlist.forEach { db.sourceDao().insert(it) }
    }

    suspend fun upsertDiscoveredOrImportedServer(
        hostname: String,
        port: Int,
        countryCode: String,
        countryName: String,
        protocol: VpnProtocol,
        displayName: String,
        rawConfigData: String,
        endpointHash: String,
        sourceId: Long?,
        validationStatus: ValidationStatus,
        validationMessage: String,
        trustedSource: Boolean,
        latencyMs: Int = -1,
        status: ServerStatus = ServerStatus.UNKNOWN
    ): Long {
        val encryptedPayload = cryptoVault.encrypt(rawConfigData)
        val existing = db.serverDao().findByEndpointHash(endpointHash)
        val now = System.currentTimeMillis()
        if (existing != null) {
            val updatedServer = existing.copy(
                countryCode = countryCode.ifBlank { existing.countryCode },
                countryName = countryName.ifBlank { existing.countryName },
                displayName = displayName.ifBlank { existing.displayName },
                latency = if (latencyMs > 0) latencyMs else existing.latency,
                status = if (status != ServerStatus.UNKNOWN) status else existing.status,
                lastTestedAt = if (latencyMs > 0) now else existing.lastTestedAt
            )
            db.serverDao().updateServer(updatedServer)
            val existingCfg = db.configurationDao().getByServerId(existing.id)
            if (existingCfg != null) {
                db.configurationDao().update(
                    existingCfg.copy(
                        protocol = protocol,
                        configurationData = encryptedPayload,
                        updatedAt = now,
                        validationStatus = validationStatus,
                        validationMessage = validationMessage,
                        trustedSource = existingCfg.trustedSource || trustedSource
                    )
                )
            } else {
                db.configurationDao().insert(
                    ConfigurationEntity(
                        serverId = existing.id,
                        protocol = protocol,
                        configurationData = encryptedPayload,
                        createdAt = now,
                        updatedAt = now,
                        validationStatus = validationStatus,
                        validationMessage = validationMessage,
                        trustedSource = trustedSource,
                        userApprovedUntrusted = trustedSource
                    )
                )
            }
            return existing.id
        } else {
            val serverId = db.serverDao().insertServer(
                ServerEntity(
                    hostname = hostname,
                    port = port,
                    countryCode = countryCode,
                    countryName = countryName,
                    protocol = protocol,
                    latency = latencyMs,
                    reliabilityScore = if (latencyMs > 0) 0.7f else 0.3f,
                    lastTestedAt = if (latencyMs > 0) now else 0L,
                    sourceId = sourceId,
                    status = status,
                    displayName = displayName,
                    endpointHash = endpointHash
                )
            )
            if (serverId > 0L) {
                db.configurationDao().insert(
                    ConfigurationEntity(
                        serverId = serverId,
                        protocol = protocol,
                        configurationData = encryptedPayload,
                        createdAt = now,
                        updatedAt = now,
                        validationStatus = validationStatus,
                        validationMessage = validationMessage,
                        trustedSource = trustedSource,
                        userApprovedUntrusted = trustedSource
                    )
                )
            }
            return serverId
        }
    }

    suspend fun getDecryptedProfile(serverId: Long): DecryptedServerProfile? {
        val pair = db.serverDao().getServerWithConfiguration(serverId) ?: return null
        val cfg = pair.configuration ?: return null
        val decrypted = cryptoVault.decrypt(cfg.configurationData)
        return DecryptedServerProfile(pair.server, cfg, decrypted)
    }

    suspend fun getAllServersOnce(): List<ServerEntity> = db.serverDao().getAllServersOnce()

    suspend fun setFavorite(serverId: Long, favorite: Boolean) = db.serverDao().setFavorite(serverId, favorite)

    suspend fun updateServerNameAndTrust(serverId: Long, newName: String, trusted: Boolean) {
        if (newName.isNotBlank()) {
            db.serverDao().updateDisplayName(serverId, newName.trim())
        }
        db.configurationDao().setTrustedSource(serverId, trusted)
        if (trusted) {
            db.configurationDao().setUntrustedApproval(serverId, true)
        }
    }

    suspend fun approveUntrustedServer(serverId: Long) {
        db.configurationDao().setUntrustedApproval(serverId, true)
    }

    suspend fun deleteServer(serverId: Long) = db.serverDao().deleteServerById(serverId)

    suspend fun recordProbeResult(server: ServerEntity, latencyMs: Int, reachable: Boolean) {
        val newSuccess = if (reachable) server.successCount + 1 else server.successCount
        val newFailure = if (reachable) server.failureCount else server.failureCount + 1
        val total = (newSuccess + newFailure).coerceAtLeast(1)
        val score = (newSuccess.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val status = when {
            !server.protocol.isSupported -> ServerStatus.UNSUPPORTED
            reachable -> ServerStatus.ONLINE
            else -> ServerStatus.UNREACHABLE
        }
        db.serverDao().updateProbeResult(
            serverId = server.id,
            latencyMs = if (reachable) latencyMs else -1,
            status = status,
            testedAt = System.currentTimeMillis(),
            reliabilityScore = score,
            successCount = newSuccess,
            failureCount = newFailure
        )
    }

    suspend fun updateServerThroughput(serverId: Long, downloadMbps: Float, uploadMbps: Float) {
        db.serverDao().updateThroughput(serverId, downloadMbps, uploadMbps)
    }

    suspend fun cleanupStaleServers(maxAgeMs: Long = 7L * 24L * 3600L * 1000L): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return db.serverDao().deleteStaleUnreachable(cutoff)
    }

    // Sources
    suspend fun getEnabledSources(): List<SourceEntity> = db.sourceDao().getEnabledSources()

    suspend fun getSourceById(id: Long): SourceEntity? = db.sourceDao().getById(id)

    suspend fun addSource(name: String, url: String, type: SourceType): Result<Long> {
        val validated = NetworkSecurityValidator.validateHttpsSourceUrl(url)
        if (validated.isFailure) return Result.failure(validated.exceptionOrNull()!!)
        val id = db.sourceDao().insert(
            SourceEntity(
                name = name.trim().ifBlank { "Custom Source" },
                sourceUrl = validated.getOrThrow(),
                sourceType = type,
                enabled = true,
                reliabilityScore = 0.5f
            )
        )
        return if (id > 0) Result.success(id) else Result.failure(IllegalStateException("Source already exists"))
    }

    suspend fun setSourceEnabled(id: Long, enabled: Boolean) = db.sourceDao().setEnabled(id, enabled)

    suspend fun deleteSource(id: Long) = db.sourceDao().deleteById(id)

    suspend fun updateSourceStats(source: SourceEntity, success: Boolean, discovered: Int, errorMsg: String = "") {
        val adjustedScore = if (success) {
            (source.reliabilityScore * 0.7f + 0.3f).coerceIn(0.1f, 1f)
        } else {
            (source.reliabilityScore * 0.7f).coerceIn(0.05f, 1f)
        }
        db.sourceDao().update(
            source.copy(
                lastUpdatedAt = System.currentTimeMillis(),
                reliabilityScore = adjustedScore,
                discoveredCount = if (success) discovered else source.discoveredCount,
                lastError = if (success) "" else errorMsg.take(180)
            )
        )
    }

    // History & Speed tests
    suspend fun insertConnectionHistory(entry: ConnectionHistoryEntity): Long =
        db.connectionHistoryDao().insert(entry)

    suspend fun updateConnectionHistory(entry: ConnectionHistoryEntity) =
        db.connectionHistoryDao().update(entry)

    suspend fun clearConnectionHistory() = db.connectionHistoryDao().clearAll()

    suspend fun insertSpeedTest(entry: SpeedTestEntity): Long = db.speedTestDao().insert(entry)

    suspend fun clearSpeedTests() = db.speedTestDao().clearAll()
}
