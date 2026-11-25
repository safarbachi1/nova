package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.local.dao.ConfigurationDao
import com.example.data.local.dao.ConnectionHistoryDao
import com.example.data.local.dao.ServerDao
import com.example.data.local.dao.SourceDao
import com.example.data.local.dao.SpeedTestDao
import com.example.data.local.entity.ConfigurationEntity
import com.example.data.local.entity.ConnectionHistoryEntity
import com.example.data.local.entity.ServerEntity
import com.example.data.local.entity.SourceEntity
import com.example.data.local.entity.SpeedTestEntity
import com.example.domain.model.ServerStatus
import com.example.domain.model.SourceType
import com.example.domain.model.ValidationStatus
import com.example.domain.model.VpnProtocol

class VpnTypeConverters {
    @TypeConverter fun fromProtocol(value: VpnProtocol): String = value.name
    @TypeConverter fun toProtocol(value: String): VpnProtocol =
        runCatching { VpnProtocol.valueOf(value) }.getOrDefault(VpnProtocol.UNKNOWN)

    @TypeConverter fun fromServerStatus(value: ServerStatus): String = value.name
    @TypeConverter fun toServerStatus(value: String): ServerStatus =
        runCatching { ServerStatus.valueOf(value) }.getOrDefault(ServerStatus.UNKNOWN)

    @TypeConverter fun fromValidationStatus(value: ValidationStatus): String = value.name
    @TypeConverter fun toValidationStatus(value: String): ValidationStatus =
        runCatching { ValidationStatus.valueOf(value) }.getOrDefault(ValidationStatus.INVALID)

    @TypeConverter fun fromSourceType(value: SourceType): String = value.name
    @TypeConverter fun toSourceType(value: String): SourceType =
        runCatching { SourceType.valueOf(value) }.getOrDefault(SourceType.MIXED_LINKS)
}

@Database(
    entities = [
        ServerEntity::class,
        ConfigurationEntity::class,
        SourceEntity::class,
        ConnectionHistoryEntity::class,
        SpeedTestEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(VpnTypeConverters::class)
abstract class NovaVpnDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun configurationDao(): ConfigurationDao
    abstract fun sourceDao(): SourceDao
    abstract fun connectionHistoryDao(): ConnectionHistoryDao
    abstract fun speedTestDao(): SpeedTestDao

    companion object {
        @Volatile private var instance: NovaVpnDatabase? = null

        fun getInstance(context: Context): NovaVpnDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovaVpnDatabase::class.java,
                    "nova_vpn.db"
                ).fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
