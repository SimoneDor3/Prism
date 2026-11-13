package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.podcast.NewEpisodes
import com.prism.music.data.podcast.PodcastDirectory
import com.prism.music.data.podcast.RssEpisode
import com.prism.music.data.podcast.RssShow
import com.prism.music.data.prefs.HomeSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** New episodes and the podcast directory: what's read from Apple's answers and from the feeds, with no network. */
class PodcastDiscoverTest {
    private fun json(s: String) = InnerTube.json.parseToJsonElement(s)

    @Test fun theChartsShowsInItsOrder() {
        val chart = json("""{"feed":{"entry":[{"id":{"attributes":{"im:id":"22"}}},{"id":{"label":"no id"}},{"id":{"attributes":{"im:id":"11"}}}]}}""")
        assertEquals(listOf("22", "11"), PodcastDirectory.chartIds(chart))
        assertEquals("no chart, no shows", emptyList<String>(), PodcastDirectory.chartIds(json("{}")))
    }

    @Test fun aShowWithoutAFeedIsLeftOut() {
        val found = PodcastDirectory.shows(json("""{"results":[
            {"collectionId":11,"collectionName":"Eleven","artistName":"A host","artworkUrl600":"https://img/600.jpg","feedUrl":"https://feeds.example/eleven"},
            {"collectionId":22,"collectionName":"No feed"},
            {"collectionId":33,"collectionName":"Small cover","artworkUrl100":"https://img/100.jpg","feedUrl":"https://feeds.example/33"}
        ]}"""))
        assertEquals(listOf("11", "33"), found.map { it.id })
        assertEquals("https://feeds.example/eleven", found[0].feedUrl)
        assertEquals("A host", found[0].author)
        assertEquals("the big cover, else the small one", listOf("https://img/600.jpg", "https://img/100.jpg"), found.map { it.image })
        assertTrue("its card isn't taken for a YouTube show", found[0].item().id.startsWith("apple:"))
    }

    @Test fun onlyACountryGoesInTheAddress() {
        assertEquals("it", PodcastDirectory.storefront("IT"))
        assertEquals("us", PodcastDirectory.storefront(""))
        assertEquals("us", PodcastDirectory.storefront("419"))
        assertEquals("us", PodcastDirectory.storefront("it/../x"))
    }

    @Test fun genresByNameWithTheKindsInsideThem() {
        val genres = PodcastDirectory.genres(json("""{"26":{"name":"Podcasts","id":"26","subgenres":{
            "1545":{"name":"Sports","id":"1545","subgenres":{"1546":{"name":"Soccer","id":"1546"},"1547":{"name":"Football","id":"1547"}}},
            "1487":{"name":"History","id":"1487"},
            "9":{"name":"","id":"9"}
        }}}"""))
        assertEquals(listOf("History", "Sports"), genres.map { it.name })
        assertEquals(listOf("1547", "1546"), genres[1].sub.map { it.id })
        assertEquals("no list, no genres", emptyList<Any>(), PodcastDirectory.genres(json("{}")))
    }

    @Test fun theFeedsLastTwoWeeksNewestFirst() {
        val day = 24 * 3_600_000L
        val now = 100 * day
        fun episode(id: String, daysAgo: Long?) = RssEpisode(id, "Episode $id", "https://audio.example/$id", publishedAt = daysAgo?.let { now - it * day })
        val shows = listOf(
            RssShow("rss:a", "https://feeds.example/a", "A", episodes = listOf(episode("rss:a:1", 3), episode("rss:a:2", 20))),
            RssShow("rss:b", "https://feeds.example/b", "B", episodes = listOf(episode("rss:b:1", 1), episode("rss:b:2", null))),
        )
        val fresh = NewEpisodes.recentRss(shows, now)
        assertEquals(listOf("rss:b:1", "rss:a:1"), fresh.map { it.id })
        assertTrue(fresh.all { it.isEpisode })
        assertEquals("under its show's name", "B", fresh[0].artists.first().name)
    }

    @Test fun newEpisodesIsOfferedButNotPutOnHome() {
        assertTrue(HomeSections.initial.none { it.key == HomeSections.NEW_EPISODES })
        assertTrue(HomeSections.defaults.any { it.key == HomeSections.NEW_EPISODES })
    }
}
