package org.schabi.newpipe.database.plugin

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
abstract class PluginServiceDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsert(entity: PluginServiceEntity)

    @Query("SELECT * FROM plugin_services")
    abstract fun getAll(): List<PluginServiceEntity>

    @Query("SELECT service_id AS serviceId, COUNT(*) AS count FROM subscriptions GROUP BY service_id")
    abstract fun subscriptionCounts(): List<ServiceCount>

    @Query("SELECT service_id AS serviceId, COUNT(*) AS count FROM remote_playlists GROUP BY service_id")
    abstract fun remotePlaylistCounts(): List<ServiceCount>

    data class ServiceCount(val serviceId: Int, val count: Int)
}
