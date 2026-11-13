package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.Song
import com.prism.music.data.podcast.DirectoryGenre
import com.prism.music.data.podcast.DirectoryShow
import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.tr
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.ItemCard
import com.prism.music.ui.components.MoodTile
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.components.SectionHeader
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/** Whose directory the shows under a heading come from. A name, so the same in every language. */
internal const val PODCAST_DIRECTORY = "Apple Podcasts"

/** The listener's shows' new episodes, asked for in the app's scope so leaving the page doesn't cut it short. */
@Composable
internal fun rememberNewEpisodes(): List<Song> {
    val c = LocalContainer.current
    LaunchedEffect(Unit) { c.scope.launch { runCatching { c.newEpisodes.refresh() } } }
    val episodes by c.newEpisodes.episodes.collectAsState(emptyList())
    return episodes
}

/** New episodes on Home (added from Customize Home): in the section's style, More for Podcasts. With none, no section. */
@Composable
internal fun NewEpisodesSection(cfg: HomeSectionConfig, online: Boolean) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val episodes = rememberNewEpisodes()
    val shown = remember(episodes, online) { (if (online) episodes else episodes.filter { c.availableOffline(it.id) }).take(20) }
    val title = cfg.titled()
    SongSection(cfg, title, null, shown, play = { list, i -> playEpisode(c, list[i], title) }, onMore = { nav.go(Routes.PODCASTS) })
}

/** The country whose chart and results the directory is asked for: the one picked in Settings, else the phone's. */
@Composable
internal fun directoryCountry(): String = LocalAppSettings.current.contentCountry.ifBlank { java.util.Locale.getDefault().country }

/** A row of the directory's shows. A long press does nothing: they aren't YouTube's, so YouTube's menu has no say on them. */
@Composable
internal fun DirectoryShows(title: String, shows: List<DirectoryShow>, onPick: (DirectoryShow) -> Unit) {
    SectionHeader(title, strapline = PODCAST_DIRECTORY)
    DirectoryRow(shows, onPick)
}

@Composable
private fun DirectoryRow(shows: List<DirectoryShow>, onPick: (DirectoryShow) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(shows, key = { it.id }) { show -> ItemCard(show.item(), onLongClick = {}) { onPick(show) } }
    }
}

/** The directory's genres for the country, the kinds inside them not yet laid out. Empty until read, and if it can't be. */
@Composable
internal fun rememberDirectoryGenres(): List<DirectoryGenre> {
    val c = LocalContainer.current
    val country = directoryCountry()
    val genres by produceState(emptyList<DirectoryGenre>(), country) {
        value = runCatching { c.podcastDirectory.genres(country) }.getOrDefault(emptyList())
    }
    return genres
}

/**
 * Shows to find on the Podcasts page: the country's chart, then the directory's genres as tiles, each opening its own
 * page ([PodcastGenreScreen]). When the directory can't be reached there's no section at all.
 */
@Composable
internal fun PodcastDiscovery(onPick: (DirectoryShow) -> Unit) {
    GenreChart(null, onPick)
    val genres = rememberDirectoryGenres()
    if (genres.isNotEmpty()) {
        SectionHeader(tr(R.string.browse), strapline = PODCAST_DIRECTORY)
        GenreTiles(genres)
    }
}

/** The most followed shows of [genre], or of them all. Nothing until they're read. */
@Composable
private fun GenreChart(genre: String?, onPick: (DirectoryShow) -> Unit) {
    val c = LocalContainer.current
    val country = directoryCountry()
    val shows by produceState(emptyList<DirectoryShow>(), country, genre) {
        value = runCatching { c.podcastDirectory.top(country, genre) }.getOrDefault(emptyList())
    }
    if (shows.isNotEmpty()) DirectoryShows(tr(R.string.podcast_top_shows), shows, onPick)
}

/** Genres two to a row, as Moods & genres' tiles are. The directory gives them no color, so each keeps one of these by its id. */
@Composable
internal fun GenreTiles(genres: List<DirectoryGenre>) {
    val nav = LocalNavigator.current
    genres.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { g ->
                val color = TILE_COLORS[g.id.hashCode().mod(TILE_COLORS.size)]
                MoodTile(MoodItem(g.id, g.name, null, color), Modifier.weight(1f).height(84.dp)) { nav.go(Routes.podcastGenre(g.id)) }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Shows across a genre page's grid. */
private const val GRID_ACROSS = 3

private val TILE_COLORS =listOf(0xFF8E24AAL, 0xFF3949ABL, 0xFF00897BL, 0xFFD81B60L, 0xFF6D4C41L, 0xFF1E88E5L, 0xFFE4572EL, 0xFF43A047L)

/**
 * One genre of the directory: its most followed shows, and the kinds inside it as tiles of their own (Leisure has Video
 * games), each opening the same page for itself. A show picked is added by its feed, as on the Podcasts page.
 */
@Composable
fun PodcastGenreScreen(id: String, bottomPadding: Dp) {
    val nav = LocalNavigator.current
    val genres = rememberDirectoryGenres()
    val genre = remember(genres, id) { genres.flatMap { listOf(it) + it.sub }.firstOrNull { it.id == id } }
    var adding by remember { mutableStateOf<String?>(null) }
    val c = LocalContainer.current
    val country = directoryCountry()
    val shows by produceState(emptyList<DirectoryShow>(), country, id) {
        value = runCatching { c.podcastDirectory.top(country, id) }.getOrDefault(emptyList())
    }
    SubPage(genre?.name ?: tr(R.string.podcasts), bottomPadding, subtitle = PODCAST_DIRECTORY) {
        // A genre with kinds inside it: its chart in a row, as on the Podcasts page, then the kinds to go into.
        genre?.sub?.takeIf { it.isNotEmpty() }?.let { kinds ->
            if (shows.isNotEmpty()) item(key = "top") { Column { DirectoryShows(tr(R.string.podcast_top_shows), shows) { adding = it.feedUrl } } }
            item(key = "kinds") { Column { SectionHeader(tr(R.string.browse)); GenreTiles(kinds) } }
            return@SubPage
        }
        // With nothing further in, the chart is the page: a grid, in its order across each row.
        items(shows.chunked(GRID_ACROSS), key = { it.first().id }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { show -> ItemCard(show.item(), Modifier.weight(1f), onLongClick = {}) { adding = show.feedUrl } }
                repeat(GRID_ACROSS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    adding?.let { preset -> AddRssShowDialog(preset, onDismiss = { adding = null }) { show ->
        adding = null
        nav.go(Routes.podcast(show.id))
    } }
}
