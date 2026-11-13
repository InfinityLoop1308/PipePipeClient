package org.schabi.newpipe.database.plugin

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.schabi.newpipe.database.plugin.PluginServiceEntity.Companion.PLUGIN_SERVICE_TABLE

/**
 * Which plugin a service id belongs to. Service ids of plugins are hashes of the plugin id
 * and cannot be reversed, so this is kept to tell users which plugin their data needs. It
 * lives in the database so that backups carry it. Rows are kept when a plugin is removed.
 */
@Entity(tableName = PLUGIN_SERVICE_TABLE)
data class PluginServiceEntity(
    @PrimaryKey
    @ColumnInfo(name = SERVICE_ID)
    var serviceId: Int,

    @ColumnInfo(name = PLUGIN_ID)
    var pluginId: String,

    @ColumnInfo(name = NAME)
    var name: String,

    @ColumnInfo(name = REPOSITORY_URL)
    var repositoryUrl: String,
) {
    companion object {
        const val PLUGIN_SERVICE_TABLE = "plugin_services"
        const val SERVICE_ID = "service_id"
        const val PLUGIN_ID = "plugin_id"
        const val NAME = "name"
        const val REPOSITORY_URL = "repository_url"
    }
}
