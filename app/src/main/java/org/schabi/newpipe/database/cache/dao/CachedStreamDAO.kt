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

    /**
     * Only entries whose download actually finished. Offline playback must use this rather than
     * [findStream]: a row exists from the moment caching starts (so the Cached videos screen can
     * show it downloading), and its file is incomplete until then.
     */
    @Query(
        "SELECT * FROM $CACHED_STREAM_TABLE " +
            "WHERE $SERVICE_ID = :serviceId AND $URL = :url AND is_complete = 1 LIMIT 1"
    )
    fun findCompleteStream(serviceId: Int, url: String): Maybe<CachedStreamEntity>

    // Deliberately Rx-returning (Maybe/Flowable), not a plain blocking return type: Room only
    // allows blocking a caller thread on the result (e.g. via blockingGet()/blockingFirst()) when
    // the query itself runs on Room's own query executor, which is how Rx-returning DAO methods
    // work. A plain blocking DAO method throws IllegalStateException when called from the main
    // thread, which is where list-item binding (see CacheManager.isCachedBlocking) needs this.
    @Query("SELECT * FROM $CACHED_STREAM_TABLE WHERE is_complete = 1")
    fun getAllComplete(): Flowable<List<CachedStreamEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: CachedStreamEntity): Long

    @Delete
    fun delete(entity: CachedStreamEntity): Int

    @Query("DELETE FROM $CACHED_STREAM_TABLE WHERE $SERVICE_ID = :serviceId AND $URL = :url")
    fun deleteByUrl(serviceId: Int, url: String): Int
}
