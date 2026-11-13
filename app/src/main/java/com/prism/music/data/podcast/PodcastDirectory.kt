package com.prism.music.data.podcast

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import com.prism.music.data.model.PodcastItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A show in Apple's podcast directory: its name, and where its own feed is. */
data class DirectoryShow(val id: String, val title: String, val author: String?, val image: String?, val feedUrl: String) {
    /** As a card. Its id is Apple's, not YouTube's: it opens nothing by itself, the feed does. */
    fun item() = PodcastItem(ID_PREFIX + id, title, author.orEmpty(), image)

    companion object {
        const val ID_PREFIX = "apple:"
    }
}

/** One of the directory's kinds of show ("History"), named in its country's language, with the kinds inside it. */
data class DirectoryGenre(val id: String, val name: String, val sub: List<DirectoryGenre> = emptyList())

/**
 * Finding shows to add (Podcasts → Top shows, and Podcasts' search): Apple's public podcast directory, which lists each
 * show's own RSS feed. It's asked for a country's charts and genres or for the words typed, and nothing else: no account, no key and
 * nothing of YouTube's ([RssGuard]'s client holds none of it). A show found here is added by its feed, as a pasted one is.
 */
class PodcastDirectory(private val client: OkHttpClient) {
    /** The charts and genre lists read, for this run: the page asks each time it opens, and for every genre picked. */
    private val charts = ConcurrentHashMap<String, List<DirectoryShow>>()
    private val genreLists = ConcurrentHashMap<String, List<DirectoryGenre>>()

    /** [country]'s most followed shows, of one [genre] or of them all, in the chart's order. */
    suspend fun top(country: String, genre: String? = null): List<DirectoryShow> = withContext(Dispatchers.IO) {
        val cc = storefront(country)
        val of = genre?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "/genre=$it" }.orEmpty()
        charts[cc + of]?.let { return@withContext it }
        val ids = chartIds(get("https://itunes.apple.com/$cc/rss/toppodcasts/limit=$TOP$of/json"))
        if (ids.isEmpty()) return@withContext emptyList()
        // The chart has no feeds; looking its shows up does.
        val byId = shows(get("https://itunes.apple.com/lookup?entity=podcast&id=" + ids.joinToString(","))).associateBy { it.id }
        ids.mapNotNull { byId[it] }.also { charts[cc + of] = it }
    }

    /** The genres [country]'s charts are kept by, by name. */
    suspend fun genres(country: String): List<DirectoryGenre> = withContext(Dispatchers.IO) {
        val cc = storefront(country)
        genreLists[cc] ?: genres(get("https://itunes.apple.com/WebObjects/MZStoreServices.woa/ws/genres?id=$PODCASTS&cc=$cc"))
            .also { if (it.isNotEmpty()) genreLists[cc] = it }
    }

    suspend fun search(query: String, country: String): List<DirectoryShow> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast").addQueryParameter("entity", "podcast").addQueryParameter("limit", "20")
            .addQueryParameter("country", storefront(country)).addQueryParameter("term", query)
            .build()
        shows(get(url.toString()))
    }

    private fun get(url: String): JsonElement =
        client.newCall(Request.Builder().url(url).header("User-Agent", RssGuard.userAgent).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Apple's podcast directory answered ${r.code}")
            InnerTube.json.parseToJsonElement(r.body.string())
        }

    companion object {
        private const val TOP = 25
        /** Apple's id for podcasts as a whole: the genres are what's inside it. */
        private const val PODCASTS = "26"

        internal fun genres(json: JsonElement): List<DirectoryGenre> = inside(json.obj(PODCASTS))

        private fun inside(genre: JsonObject?): List<DirectoryGenre> = genre.obj("subgenres")?.values.orEmpty().mapNotNull { g ->
            DirectoryGenre(g.str("id") ?: return@mapNotNull null, g.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null, inside(g as? JsonObject))
        }.sortedBy { it.name }

        /** A two-letter country as Apple's addresses take it; anything else is the US chart. */
        internal fun storefront(country: String) = country.trim().lowercase().takeIf { it.matches(Regex("[a-z]{2}")) } ?: "us"

        internal fun chartIds(json: JsonElement): List<String> =
            json.arr("feed", "entry").orEmpty().mapNotNull { it.str("id", "attributes", "im:id") }

        /** A lookup's or a search's shows. One without a feed (Apple hosts a few itself) can't be added, so it's left out. */
        internal fun shows(json: JsonElement): List<DirectoryShow> = json.arr("results").orEmpty().mapNotNull { r ->
            DirectoryShow(
                id = r.str("collectionId") ?: return@mapNotNull null,
                title = r.str("collectionName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                author = r.str("artistName"),
                image = r.str("artworkUrl600") ?: r.str("artworkUrl100"),
                feedUrl = r.str("feedUrl")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
            )
        }
    }
}
