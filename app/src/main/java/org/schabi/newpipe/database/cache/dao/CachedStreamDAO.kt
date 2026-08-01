package org.schabi.newpipe.database.cache.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import org.schabi.newpipe.database.cache.model.CachedStreamEntity
import org.schabi.newpipe.database.cache.model.CachedStreamEntity.Companion.CACHED_STREAM_TABLE
import org.schabi.newpipe.database.cache.model.CachedStreamEntity.Companion.SERVICE_ID
import org.schabi.newpipe.database.cache.model.CachedStreamEntity.Companion.URL

@Dao
interface CachedStreamDAO {
    @Query("SELECT * FROM $CACHED_STREAM_TABLE ORDER BY cached_at DESC")
    fun getAll(): Flowable<List<CachedStreamEntity>>

    @Query("SELECT * FROM $CACHED_STREAM_TABLE WHERE $SERVICE_ID = :serviceId AND $URL = :url LIMIT 1")
    fun findStream(serviceId: Int, url: String): Maybe<CachedStreamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: CachedStreamEntity): Long

    @Delete
    fun delete(entity: CachedStreamEntity): Int

    @Query("DELETE FROM $CACHED_STREAM_TABLE WHERE $SERVICE_ID = :serviceId AND $URL = :url")
    fun deleteByUrl(serviceId: Int, url: String): Int
}
