package com.example.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.domain.model.ServerStatus
import com.example.domain.model.SourceType
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnProtocol

@Entity(
    tableName = "servers",
    indices = [
        Index(value = ["endpointHash"], unique = true),
        Index(value = ["countryCode"]),
        Index(value = ["sourceId"])
    ]
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostname: String,
    val port: Int,
    val countryCode: String,
    val countryName: String,
    val protocol: VpnProtocol,
    val latency: Int = -1,
    val reliabilityScore: Float = 0f,
    val lastTestedAt: Long = 0L,
    val sourceId: Long? = null,
    val status: ServerStatus = ServerStatus.UNKNOWN,
    val isFavorite: Boolean = false,
    val displayName: String = "",
    val downloadSpeedMbps: Float = 0f,
    val uploadSpeedMbps: Float = 0f,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val endpointHash: String
)

@Entity(
    tableName = "configurations",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["serverId"])]
)
data class ConfigurationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val protocol: VpnProtocol,
    /** Encrypted at rest via Android Keystore AES-256-GCM (CryptoVault). */
    val configurationData: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val validationStatus: ValidationStatus = ValidationStatus.VALID,
    val validationMessage: String = "",
    val trustedSource: Boolean = false,
    val userApprovedUntrusted: Boolean = false
)

@Entity(
    tableName = "sources",
    indices = [Index(value = ["sourceUrl"], unique = true)]
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceUrl: String,
    val sourceType: SourceType,
    val enabled: Boolean = true,
    val lastUpdatedAt: Long = 0L,
    val reliabilityScore: Float = 0.5f,
    val discoveredCount: Int = 0,
    val lastError: String = ""
)

@Entity(
    tableName = "connection_history",
    indices = [Index(value = ["connectedAt"])]
)
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long?,
    val serverLabel: String,
    val countryCode: String,
    val protocol: VpnProtocol,
    val connectedAt: Long,
    val disconnectedAt: Long = 0L,
    val duration: Long = 0L,
    val bytesReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val connectionResult: String,
    val errorDetail: String = ""
)

@Entity(
    tableName = "speed_tests",
    indices = [Index(value = ["testedAt"])]
)
data class SpeedTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long?,
    val serverLabel: String,
    val testedAt: Long = System.currentTimeMillis(),
    val downloadMbps: Float,
    val uploadMbps: Float,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float,
    val stabilityScore: Float
)

data class ServerWithConfiguration(
    @Embedded val server: ServerEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "serverId"
    )
    val configuration: ConfigurationEntity?
) {
    val isTrustedOrApproved: Boolean
        get() = configuration?.let { it.trustedSource || it.userApprovedUntrusted } ?: false

    val effectiveTitle: String
        get() = server.displayName.ifBlank { "${server.hostname}:${server.port}" }
}
