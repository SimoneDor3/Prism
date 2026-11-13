package com.prism.music.data.podcast

import com.prism.music.AppContainer
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * New episodes of the listener's shows (Home's section, and Podcasts'): what the shows added by RSS published in the last
 * [RECENT_MS], and YouTube Music's own "New episodes" list for the shows the account follows. Without the ones played.
 */
class NewEpisodes(private val c: AppContainer) {
    private val found = MutableStateFlow<List<Song>>(emptyList())
    private val lock = Mutex()
    private var loadedAt = 0L

    val episodes: Flow<List<Song>> = combine(found, c.episodes.played) { list, played -> list.filter { it.id !in played } }

    /** Asks the feeds and YouTube again, when what's here is older than [STALE_MS]. A feed that can't answer keeps what it had. */
    suspend fun refresh() = lock.withLock {
        val now = System.currentTimeMillis()
        if (now - loadedAt < STALE_MS) return@withLock
        found.value = withContext(Dispatchers.IO) {
            coroutineScope {
                val youtube = async {
                    if (!c.settings.current.isLoggedIn) emptyList()
                    else runCatching { c.ytm.playlist(YouTubeMusic.NEW_EPISODES).songs.filter { it.isEpisode } }.getOrDefault(emptyList())
                }
                val store = c.rssPodcasts
                // ponytail: one feed after another; ask them together if someone with many shows finds this slow.
                store.shows.value.filter { now - it.refreshedAt > STALE_MS }.forEach { show ->
                    runCatching { store.refresh(show.id) { ep -> c.episodes.positionOf(ep) > 0 || c.player.currentSong.value?.id == ep } }
                }
                // YouTube's rows carry no date to sort the two together by, so the feeds' (which do) go first.
                recentRss(store.shows.value, now) + youtube.await()
            }
        }
        loadedAt = now
    }

    companion object {
        private const val STALE_MS = 30 * 60_000L
        private const val RECENT_MS = 14 * 24 * 3_600_000L

        /** The feeds' episodes of the last [RECENT_MS], newest first. One with no date isn't known to be new. */
        internal fun recentRss(shows: List<RssShow>, now: Long): List<Song> = shows
            .flatMap { show -> show.episodes.filter { (it.publishedAt ?: 0L) > now - RECENT_MS }.map { it to show } }
            .sortedByDescending { it.first.publishedAt }
            .map { (episode, show) -> episode.song(show) }
    }
}
