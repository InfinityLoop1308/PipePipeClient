package org.schabi.newpipe.local.feed.service

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedContentSelection
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Runs normal extraction and persistence with controlled YouTube HTTP responses. */
@RunWith(AndroidJUnit4::class)
class NormalRefreshContentClassificationTest {
    @Test
    fun normalYoutubeRefreshKeepsLiveItemsInLiveOnlyGroup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = NewPipeDatabase.getInstance(context)
        val sqlite = database.openHelper.writableDatabase
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val fastKey = context.getString(R.string.feed_use_dedicated_fetch_method_key)
        val tabsKey = context.getString(R.string.feed_fetch_channel_tabs_key)
        val oldFast = preferences.all[fastKey] as Boolean?
        val oldTabs = preferences.getStringSet(tabsKey, null)?.toSet()
        val oldDownloader = NewPipe.getDownloader()
        val localization = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        val channelId = "UC0000000000000000000000"
        val channel = "https://www.youtube.com/channel/$channelId"
        val service = ServiceList.YouTube.serviceId
        var subscription = -1L
        val names = listOf("Review live fixture", "Review videos fixture")
        try {
            preferences.edit().putBoolean(fastKey, false).remove(tabsKey).commit()
            sqlite.execSQL(
                "INSERT INTO subscriptions (service_id, url, name, notification_mode) " +
                    "VALUES (?, ?, 'Review YouTube fixture', 0)", arrayOf<Any>(service, channel)
            )
            sqlite.query("SELECT uid FROM subscriptions WHERE url = ?", arrayOf(channel)).use {
                assertTrue(it.moveToFirst())
                subscription = it.getLong(0)
            }
            val manager = FeedDatabaseManager(context)
            for ((name, selection) in names.zip(
                listOf(FeedContentSelection.LIVE, FeedContentSelection.VIDEOS)
            )) {
                assertTrue(manager.createGroupWithContentRules(name, FeedGroupIcon.ALL,
                    selection, setOf(subscription), emptyMap()).blockingAwait(10, TimeUnit.SECONDS))
            }
            val groupIds = names.map { name ->
                sqlite.query("SELECT uid FROM feed_group WHERE name = ?", arrayOf(name)).use {
                    assertTrue(it.moveToFirst())
                    it.getLong(0)
                }
            }
            val published = Instant.now().minusSeconds(3600).toString()
            fun video(id: Int, live: Boolean) = """{"richItemRenderer":{"content":{
                "videoRenderer":{"videoId":"fixture000$id",
                "title":{"simpleText":"${if (live) "Live fixture" else "Video fixture"}"},
                "thumbnail":{"thumbnails":[{"url":"https://feed-regression.invalid/$id.jpg",
                    "width":480,"height":360}]},
                "lengthText":{"simpleText":"1:00"},
                "viewCountText":{"simpleText":"1 view"},
                "publishedTimeText":{"simpleText":"$published"},
                "badges":[${if (live) """{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW"}}""" else ""}]
            }}}}"""
            val channelJson = """{
                "header":{"c4TabbedHeaderRenderer":{"title":"Fixture channel",
                    "avatar":{"thumbnails":[]},"banner":{"thumbnails":[]},"badges":[]}},
                "metadata":{"channelMetadataRenderer":{"externalId":"$channelId",
                    "title":"Fixture channel","description":"Controlled channel"}},
                "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
                    "title":"Videos","endpoint":{"commandMetadata":{
                        "webCommandMetadata":{"url":"/channel/$channelId/videos"}}},
                    "content":{"richGridRenderer":{"contents":[${video(1, true)},${video(2, false)}]}}
                }}]}},"microformat":{"microformatDataRenderer":{"tags":[]}}
            }"""
            YoutubeParsingHelper.resetClientVersion()
            NewPipe.init(object : Downloader() {
                override fun execute(request: Request): Response {
                    val url = request.url()
                    val body = when {
                        url == "https://www.youtube.com/sw.js" ->
                            """{"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20261105.00.00"}"""
                        url.startsWith("https://www.youtube.com/youtubei/v1/browse?") -> channelJson
                        else -> throw IOException("Unexpected fixture URL: $url")
                    }
                    return Response(200, "OK", emptyMap(), body, body.toByteArray(), url)
                }
                override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall {
                    throw UnsupportedOperationException("Normal channel extraction is synchronous")
                }
            }, localization, country)
            val results = FeedLoadManager(context).startLoading(groupIds[0], true)
                .timeout(30, TimeUnit.SECONDS).blockingGet()
            assertEquals(1, results.size)
            assertTrue("Normal refresh must finish without an extraction error", results[0].isOnNext)
            assertTrue("Controlled extraction must have no parser errors: ${results[0].value!!.errors}",
                results[0].value!!.errors.isEmpty())
            assertEquals(2, results[0].value!!.streams.size)
            assertEquals("Live-only group must retain the extracted live entry",
                listOf("Live fixture"), manager.getStreams(groupIds[0]).blockingGet(emptyList())
                    .map { it.stream.title })
            assertEquals("Videos-only group must exclude the extracted live entry",
                listOf("Video fixture"), manager.getStreams(groupIds[1]).blockingGet(emptyList())
                    .map { it.stream.title })
        } finally {
            NewPipe.init(oldDownloader, localization, country)
            YoutubeParsingHelper.resetClientVersion()
            names.forEach { sqlite.execSQL("DELETE FROM feed_group WHERE name = ?", arrayOf(it)) }
            if (subscription >= 0) sqlite.execSQL("DELETE FROM subscriptions WHERE uid = ?", arrayOf(subscription))
            preferences.edit().apply {
                if (oldFast == null) remove(fastKey) else putBoolean(fastKey, oldFast)
                if (oldTabs == null) remove(tabsKey) else putStringSet(tabsKey, oldTabs)
            }.commit()
        }
    }
}
