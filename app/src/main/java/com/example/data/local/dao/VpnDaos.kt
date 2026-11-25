package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.ConfigurationEntity
import com.example.data.local.entity.ConnectionHistoryEntity
import com.example.data.local.entity.ServerEntity
import com.example.data.local.entity.ServerWithConfiguration
import com.example.data.local.entity.SourceEntity
import com.example.data.local.entity.SpeedTestEntity
import com.example.domain.model.ServerStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Transaction
    @Query("SELECT * FROM servers ORDER BY isFavorite DESC, CASE WHEN latency > 0 THEN latency ELSE 999999 END ASC")
    fun observeServersWithConfigurations(): Flow<List<ServerWithConfiguration>>

    @Transaction
    @Query("SELECT * FROM servers WHERE id = :serverId LIMIT 1")
    suspend fun getServerWithConfiguration(serverId: Long): ServerWithConfiguration?

    @Query("SELECT * FROM servers WHERE endpointHash = :hash LIMIT 1")
    suspend fun findByEndpointHash(hash: String): ServerEntity?

    @Query("SELECT * FROM servers")
    suspend fun getAllServersOnce(): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertServer(server: ServerEntity): Long

    @Update
    suspend fun updateServer(server: ServerEntity)

    @Query("UPDATE servers SET isFavorite = :favorite WHERE id = :serverId")
    suspend fun setFavorite(serverId: Long, favorite: Boolean)

    @Query("UPDATE servers SET displayName = :name WHERE id = :serverId")
    suspend fun updateDisplayName(serverId: Long, name: String)

    @Query(
        "UPDATE servers SET latency = :latencyMs, status = :status, lastTestedAt = :testedAt, " +
            "reliabilityScore = :reliabilityScore, successCount = :successCount, failureCount = :failureCount WHERE id = :serverId"
    )
    suspend fun updateProbeResult(
        serverId: Long,
        latencyMs: Int,
        status: ServerStatus,
        testedAt: Long,
        reliabilityScore: Float,
        successCount: Int,
        failureCount: Int
    )

    @Query("UPDATE servers SET downloadSpeedMbps = :dl, uploadSpeedMbps = :ul WHERE id = :serverId")
    suspend fun updateThroughput(serverId: Long, dl: Float, ul: Float)

    @Query("DELETE FROM servers WHERE id = :serverId")
    suspend fun deleteServerById(serverId: Long)

    @Query("DELETE FROM servers WHERE sourceId IS NOT NULL AND isFavorite = 0 AND lastTestedAt > 0 AND lastTestedAt < :cutoffMs AND status = :unreachable")
    suspend fun deleteStaleUnreachable(cutoffMs: Long, unreachable: ServerStatus = ServerStatus.UNREACHABLE): Int
}

@Dao
interface ConfigurationDao {
    @Query("SELECT * FROM configurations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConfigurationEntity>>

    @Query("SELECT * FROM configurations WHERE serverId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Long): ConfigurationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ConfigurationEntity): Long

    @Update
    suspend fun update(config: ConfigurationEntity)

    @Query("UPDATE configurations SET userApprovedUntrusted = :approved, updatedAt = :updatedAt WHERE serverId = :serverId")
    suspend fun setUntrustedApproval(serverId: Long, approved: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE configurations SET trustedSource = :trusted, updatedAt = :updatedAt WHERE serverId = :serverId")
    suspend fun setTrustedSource(serverId: Long, trusted: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM configurations WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY enabled DESC, reliabilityScore DESC, id ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE enabled = 1")
    suspend fun getEnabledSources(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SourceEntity?

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ConnectionHistoryDao {
    @Query("SELECT * FROM connection_history ORDER BY connectedAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<ConnectionHistoryEntity>>

    @Query("SELECT COALESCE(SUM(bytesReceived), 0) FROM connection_history")
    fun observeTotalBytesReceived(): Flow<Long>

    @Query("SELECT COALESCE(SUM(bytesSent), 0) FROM connection_history")
    fun observeTotalBytesSent(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ConnectionHistoryEntity): Long

    @Update
    suspend fun update(entry: ConnectionHistoryEntity)

    @Query("DELETE FROM connection_history")
    suspend fun clearAll()
}

@Dao
interface SpeedTestDao {
    @Query("SELECT * FROM speed_tests ORDER BY testedAt DESC LIMIT 50")
    fun observeHistory(): Flow<List<SpeedTestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SpeedTestEntity): Long

    @Query("DELETE FROM speed_tests")
    suspend fun clearAll()
}
