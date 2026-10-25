package org.schabi.newpipe.local.feed

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.MainActivity.DEBUG
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupContentRules
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.feed.model.FeedLastUpdatedEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.feed.service.FeedContentClassifier
import org.schabi.newpipe.local.feed.service.FeedStreamItem
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class FeedDatabaseManager internal constructor(private val database: AppDatabase) {
    constructor(context: Context) : this(NewPipeDatabase.getInstance(context))

    private val feedTable = database.feedDAO()
    private val feedGroupTable = database.feedGroupDAO()
    private val streamTable = database.streamDAO()

    companion object {
        /**
         * Only items that are newer than this will be saved.
         */
        val FEED_OLDEST_ALLOWED_DATE: OffsetDateTime = LocalDate.now().minusWeeks(13)
            .atStartOfDay().atOffset(ZoneOffset.UTC)
    }

    fun groups() = feedGroupTable.getAll()

    fun database() = database

    fun getStreams(
        groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        getPlayedStreams: Boolean = true
    ): Maybe<List<StreamWithState>> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> {
                if (getPlayedStreams) feedTable.getAllStreams()
                else feedTable.getLiveOrNotPlayedStreams()
            }
            else -> {
                if (getPlayedStreams) feedTable.getAllStreamsForGroup(groupId)
                else feedTable.getLiveOrNotPlayedStreamsForGroup(groupId)
            }
        }
    }

    fun outdatedSubscriptions(outdatedThreshold: OffsetDateTime) = feedTable.getAllOutdated(outdatedThreshold)

    fun outdatedSubscriptionsWithNotificationMode(
        outdatedThreshold: OffsetDateTime,
        @NotificationMode notificationMode: Int
    ) = feedTable.getOutdatedWithNotificationMode(outdatedThreshold, notificationMode)

    fun notLoadedCount(groupId: Long = FeedGroupEntity.GROUP_ALL_ID): Flowable<Long> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.notLoadedCount()
            else -> feedTable.notLoadedCountForGroup(groupId)
        }
    }

    fun outdatedSubscriptionsForGroup(
        groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        outdatedThreshold: OffsetDateTime
    ) = feedTable.getAllOutdatedForGroup(groupId, outdatedThreshold)

    fun markAsOutdated(subscriptionId: Long) = feedTable
        .setLastUpdatedForSubscription(FeedLastUpdatedEntity(subscriptionId, null))

    fun doesStreamExist(stream: StreamInfoItem): Boolean {
        return streamTable.exists(stream.serviceId, stream.url)
    }

    fun upsertAll(
        subscriptionId: Long,
        items: List<StreamInfoItem>,
        oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE
    ) {
        upsertAllWithContent(
            subscriptionId,
            items.map { FeedStreamItem(it, FeedContentClassifier.fromStream(it)) },
            oldestAllowedDate
        )
    }

    fun upsertAllWithContent(
        subscriptionId: Long,
        items: List<FeedStreamItem>,
        oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE
    ) {
        val itemsToInsert = ArrayList<FeedStreamItem>()
        loop@ for (feedStreamItem in FeedContentClassifier.mergeDuplicates(items)) {
            val streamItem = feedStreamItem.stream
            val uploadDate = streamItem.uploadDate

            itemsToInsert += when {
                uploadDate == null && streamItem.streamType == StreamType.LIVE_STREAM -> {
                    feedStreamItem
                }
                uploadDate != null && uploadDate.offsetDateTime() >= oldestAllowedDate -> {
                    feedStreamItem
                }
                else -> continue@loop
            }
        }

        feedTable.unlinkOldLivestreams(subscriptionId)

        if (itemsToInsert.isNotEmpty()) {
            // if item.uploaderName is null, write it as "Unknown"
            for ((item) in itemsToInsert) {
                if (item.uploaderName == null) {
                    item.uploaderName = "Unknown"
                }
            }
            val streamEntities = itemsToInsert.map { StreamEntity(it.stream) }
            val streamIds = streamTable.upsertAll(streamEntities)
            val feedEntities = streamIds.mapIndexed { index, streamId ->
                FeedEntity(
                    streamId,
                    subscriptionId,
                    itemsToInsert[index].contentSelection
                )
            }
            feedTable.upsertAll(feedEntities)
        }

        feedTable.setLastUpdatedForSubscription(
            FeedLastUpdatedEntity(subscriptionId, OffsetDateTime.now(ZoneOffset.UTC))
        )
    }

    fun removeOrphansOrOlderStreams(oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE) {
        feedTable.unlinkStreamsOlderThan(oldestAllowedDate)
        streamTable.deleteOrphans()
    }

    fun clear() {
        feedTable.deleteAll()
        val deletedOrphans = streamTable.deleteOrphans()
        if (DEBUG) {
            Log.d(
                this::class.java.simpleName,
                "clear() → streamTable.deleteOrphans() → $deletedOrphans"
            )
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // Feed Groups
    // /////////////////////////////////////////////////////////////////////////

    fun subscriptionIdsForGroup(groupId: Long): Flowable<List<Long>> {
        return feedGroupTable.getSubscriptionIdsFor(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun subscriptionsForGroup(groupId: Long): Flowable<List<FeedGroupSubscriptionEntity>> {
        return feedGroupTable.getSubscriptionsForGroup(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateSubscriptionsForGroup(groupId: Long, subscriptionIds: List<Long>): Completable {
        return Completable
            .fromCallable { feedGroupTable.updateSubscriptionsForGroup(groupId, subscriptionIds) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun createGroup(name: String, icon: FeedGroupIcon): Maybe<Long> {
        return Maybe.fromCallable { feedGroupTable.insert(FeedGroupEntity(0, name, icon)) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun createGroupWithContentRules(
        name: String,
        icon: FeedGroupIcon,
        contentSelection: FeedContentSelection,
        subscriptionIds: Set<Long>,
        contentSelectionOverrides: Map<Long, FeedContentSelection>
    ): Completable {
        val submittedSubscriptions = subscriptionIds.toSet()
        val submittedOverrides = FeedGroupContentRules.normalizeOverrides(
            contentSelection, submittedSubscriptions, contentSelectionOverrides
        )
        return Completable.fromAction {
            database.runInTransaction {
                val groupId = feedGroupTable.insert(
                    FeedGroupEntity(0, name, icon, contentSelection = contentSelection)
                )
                feedGroupTable.updateContentRulesForGroup(
                    groupId,
                    submittedSubscriptions,
                    submittedOverrides
                )
            }
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
    }

    fun getGroup(groupId: Long): Maybe<FeedGroupEntity> {
        return feedGroupTable.getGroup(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroup(feedGroupEntity: FeedGroupEntity): Completable {
        return Completable.fromCallable { feedGroupTable.update(feedGroupEntity) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroupWithContentRules(
        feedGroupEntity: FeedGroupEntity,
        subscriptionIds: Set<Long>,
        contentSelectionOverrides: Map<Long, FeedContentSelection>
    ): Completable {
        val submittedSubscriptions = subscriptionIds.toSet()
        val submittedOverrides = FeedGroupContentRules.normalizeOverrides(
            feedGroupEntity.contentSelection, submittedSubscriptions, contentSelectionOverrides
        )
        return Completable.fromAction {
            database.runInTransaction {
                feedGroupTable.updateContentRulesForGroup(
                    feedGroupEntity.uid,
                    submittedSubscriptions,
                    submittedOverrides
                )
                feedGroupTable.update(feedGroupEntity)
            }
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
    }

    fun deleteGroup(groupId: Long): Completable {
        return Completable.fromCallable { feedGroupTable.delete(groupId) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroupsOrder(groupIdList: List<Long>): Completable {
        var index = 0L
        val orderMap = groupIdList.associateBy({ it }, { index++ })

        return Completable.fromCallable { feedGroupTable.updateOrder(orderMap) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun oldestSubscriptionUpdate(groupId: Long): Flowable<List<OffsetDateTime>> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.oldestSubscriptionUpdateFromAll()
            else -> feedTable.oldestSubscriptionUpdate(groupId)
        }
    }
}
