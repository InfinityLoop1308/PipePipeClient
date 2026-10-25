package org.schabi.newpipe.database.feed.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity

@Dao
abstract class FeedGroupDAO {

    @Query("SELECT * FROM feed_group ORDER BY sort_order ASC")
    abstract fun getAll(): Flowable<List<FeedGroupEntity>>

    @Query("SELECT * FROM feed_group WHERE uid = :groupId")
    abstract fun getGroup(groupId: Long): Maybe<FeedGroupEntity>

    @Transaction
    open fun insert(feedGroupEntity: FeedGroupEntity): Long {
        val nextSortOrder = nextSortOrder()
        feedGroupEntity.sortOrder = nextSortOrder
        return insertInternal(feedGroupEntity)
    }

    @Update(onConflict = OnConflictStrategy.IGNORE)
    abstract fun update(feedGroupEntity: FeedGroupEntity): Int

    @Query("DELETE FROM feed_group")
    abstract fun deleteAll(): Int

    @Query("DELETE FROM feed_group WHERE uid = :groupId")
    abstract fun delete(groupId: Long): Int

    @Query("SELECT subscription_id FROM feed_group_subscription_join WHERE group_id = :groupId")
    abstract fun getSubscriptionIdsFor(groupId: Long): Flowable<List<Long>>

    @Query("SELECT * FROM feed_group_subscription_join WHERE group_id = :groupId")
    abstract fun getSubscriptionsForGroup(
        groupId: Long
    ): Flowable<List<FeedGroupSubscriptionEntity>>

    @Query("SELECT * FROM feed_group_subscription_join WHERE group_id = :groupId")
    protected abstract fun getSubscriptionsForGroupNow(
        groupId: Long
    ): List<FeedGroupSubscriptionEntity>

    @Query("DELETE FROM feed_group_subscription_join WHERE group_id = :groupId")
    abstract fun deleteSubscriptionsFromGroup(groupId: Long): Int

    @Query(
        """
        DELETE FROM feed_group_subscription_join
        WHERE group_id = :groupId AND subscription_id IN (:subscriptionIds)
        """
    )
    protected abstract fun deleteSubscriptionsFromGroup(
        groupId: Long,
        subscriptionIds: List<Long>
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertSubscriptionsToGroup(entities: List<FeedGroupSubscriptionEntity>): List<Long>

    @Query(
        """
        UPDATE feed_group_subscription_join
        SET content_selection_override = :contentSelectionOverride
        WHERE group_id = :groupId AND subscription_id = :subscriptionId
        """
    )
    protected abstract fun updateContentSelectionOverride(
        groupId: Long,
        subscriptionId: Long,
        contentSelectionOverride: Int?
    ): Int

    @Transaction
    open fun updateSubscriptionsForGroup(groupId: Long, subscriptionIds: List<Long>) {
        val currentSubscriptionIds = getSubscriptionsForGroupNow(groupId)
            .mapTo(mutableSetOf()) { it.subscriptionId }
        val updatedSubscriptionIds = subscriptionIds.toSet()
        val removedSubscriptionIds = currentSubscriptionIds - updatedSubscriptionIds
        val addedSubscriptionIds = updatedSubscriptionIds - currentSubscriptionIds

        if (removedSubscriptionIds.isNotEmpty()) {
            deleteSubscriptionsFromGroup(groupId, removedSubscriptionIds.toList())
        }
        if (addedSubscriptionIds.isNotEmpty()) {
            insertSubscriptionsToGroup(
                addedSubscriptionIds.map { FeedGroupSubscriptionEntity(groupId, it) }
            )
        }
    }

    @Transaction
    open fun updateContentRulesForGroup(
        groupId: Long,
        subscriptionIds: Set<Long>,
        contentSelectionOverrides: Map<Long, FeedContentSelection>
    ) {
        updateSubscriptionsForGroup(groupId, subscriptionIds.toList())

        subscriptionIds.forEach { subscriptionId ->
            updateContentSelectionOverride(
                groupId,
                subscriptionId,
                contentSelectionOverrides[subscriptionId]?.mask
            )
        }
    }

    @Transaction
    open fun updateOrder(orderMap: Map<Long, Long>) {
        orderMap.forEach { (groupId, sortOrder) -> updateOrder(groupId, sortOrder) }
    }

    @Query("UPDATE feed_group SET sort_order = :sortOrder WHERE uid = :groupId")
    abstract fun updateOrder(groupId: Long, sortOrder: Long): Int

    @Query("SELECT IFNULL(MAX(sort_order) + 1, 0) FROM feed_group")
    protected abstract fun nextSortOrder(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertInternal(feedGroupEntity: FeedGroupEntity): Long
}
