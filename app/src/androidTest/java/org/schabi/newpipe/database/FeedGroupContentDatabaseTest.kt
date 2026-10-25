package org.schabi.newpipe.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FeedGroupContentDatabaseTest {

    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun groupFeedUsesMembershipOverrideBeforeGroupDefault() {
        val sqliteDatabase = database.openHelper.writableDatabase
        insertSubscription(1)
        insertSubscription(2)
        sqliteDatabase.execSQL(
            "INSERT INTO feed_group " +
                "(uid, name, icon_id, sort_order, content_selection) " +
                "VALUES (1, 'Group', 0, 0, 1)"
        )
        sqliteDatabase.execSQL(
            "INSERT INTO feed_group_subscription_join " +
                "(group_id, subscription_id, content_selection_override) VALUES " +
                "(1, 1, 4), (1, 2, NULL)"
        )

        insertStream(1, "live-for-override")
        insertStream(2, "video-hidden-by-override")
        insertStream(3, "video-from-default")
        insertStream(4, "live-hidden-by-default")
        sqliteDatabase.execSQL(
            "INSERT INTO feed (stream_id, subscription_id, content_selection) VALUES " +
                "(1, 1, 4), (2, 1, 1), (3, 2, 1), (4, 2, 4)"
        )

        val titles = database.feedDAO().getAllStreamsForGroup(1)
            .blockingGet(emptyList())
            .map { it.stream.title }

        assertEquals(setOf("live-for-override", "video-from-default"), titles.toSet())
    }

    @Test
    fun membershipUpdatePreservesExistingOverrideAndOnlyDiffsMemberships() {
        val sqliteDatabase = database.openHelper.writableDatabase
        insertSubscription(1)
        insertSubscription(2)
        insertSubscription(3)
        sqliteDatabase.execSQL(
            "INSERT INTO feed_group " +
                "(uid, name, icon_id, sort_order, content_selection) " +
                "VALUES (1, 'Group', 0, 0, 7)"
        )
        sqliteDatabase.execSQL(
            "INSERT INTO feed_group_subscription_join " +
                "(group_id, subscription_id, content_selection_override) VALUES " +
                "(1, 1, 4), (1, 2, NULL)"
        )

        database.feedGroupDAO().updateSubscriptionsForGroup(1, listOf(1, 3))

        sqliteDatabase.query(
            "SELECT subscription_id, content_selection_override " +
                "FROM feed_group_subscription_join ORDER BY subscription_id"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getLong(0))
            assertEquals(4, cursor.getInt(1))
            assertTrue(cursor.moveToNext())
            assertEquals(3, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertEquals(false, cursor.moveToNext())
        }
    }

    @Test
    fun queuedGroupCreationUsesSubmittedMembershipAndRules() {
        insertSubscription(1)
        insertSubscription(2)
        val membership = hashSetOf(1L)
        val overrides = hashMapOf(1L to FeedContentSelection.LIVE)
        val save = FeedDatabaseManager(database).createGroupWithContentRules(
            "Submitted group", FeedGroupIcon.ALL, FeedContentSelection.VIDEOS,
            membership, overrides
        )
        membership.clear()
        membership.add(2L)
        overrides.clear()
        save.test().awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors()

        val group = database.feedGroupDAO().getAll().firstOrError().blockingGet().single()
        val saved = database.feedGroupDAO().getSubscriptionsForGroup(group.uid)
            .firstOrError().blockingGet().single()
        assertEquals("Queued creation must preserve submitted membership", 1L, saved.subscriptionId)
        assertEquals(FeedContentSelection.LIVE, saved.contentSelectionOverride)
    }

    private fun insertSubscription(id: Long) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO subscriptions " +
                "(uid, service_id, url, name, notification_mode) " +
                "VALUES ($id, 0, 'channel$id', 'Channel $id', 0)"
        )
    }

    private fun insertStream(id: Long, title: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO streams " +
                "(uid, service_id, url, title, stream_type, duration, uploader, is_paid) " +
                "VALUES ($id, 0, 'stream$id', '$title', 'VIDEO_STREAM', 60, 'Channel', 0)"
        )
    }
}
