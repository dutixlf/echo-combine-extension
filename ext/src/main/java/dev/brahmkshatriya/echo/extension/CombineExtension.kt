package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class CombineExtension : ExtensionClient, MusicExtensionsProvider,
    HomeFeedClient, SearchFeedClient, TrackClient,
    AlbumClient, ArtistClient, PlaylistClient, PlaylistEditClient,
    SaveClient, LikeClient, ShareClient, FollowClient, HideClient, RadioClient,
    LibraryFeedClient, LyricsSearchClient {

    companion object {
        const val COMBINE_ID = "echo_combine"
        const val KEY_METADATA_EXT = "metadata_extension"
        const val KEY_STREAM_EXT = "stream_extension"
        const val KEY_FIRST_AVAILABLE = "first_available"
    }

    private lateinit var settings: Settings

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> {
        val extensions = awaitExtensions()
        val extIds = extensions.map { it.id }
        val extNames = extensions.map { it.name }

        val altIds = listOf("none") + extIds
        val altNames = listOf("None") + extNames

        val settingsList = mutableListOf<Setting>(
            SettingList(
                "Metadata Extension",
                KEY_METADATA_EXT,
                "Extension to use for metadata (search, feed, album info, etc.)",
                extNames,
                extIds,
                0
            ),
            SettingSwitch(
                "First Available Mode",
                KEY_FIRST_AVAILABLE,
                "Return only streams from the first extension that responds (faster)",
                false
            ),
            SettingList(
                "Preferred Stream Extension",
                KEY_STREAM_EXT,
                "The primary extension to use for audio streaming",
                extNames,
                extIds,
                0
            )
        )

        var lastKey = KEY_STREAM_EXT
        val suffixes = listOf("2nd", "3rd", "4th", "5th")
        for (i in suffixes.indices) {
            val value = runCatching { settings.getString(lastKey) }.getOrNull()
            if (value != null && value != "none") {
                val currentKey = "${KEY_STREAM_EXT}_${i + 1}"
                settingsList.add(
                    SettingList(
                        "${suffixes[i]} Alternative Stream Extension",
                        currentKey,
                        "Backup extension if the previous ones fail to find the track",
                        altNames,
                        altIds,
                        0
                    )
                )
                lastKey = currentKey
            } else {
                break
            }
        }

        return settingsList
    }

    // --- MusicExtensionsProvider ---

    override val requiredMusicExtensions: List<String> = emptyList()

    private val extensionsFlow = MutableStateFlow<List<MusicExtension>?>(null)

    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        extensionsFlow.value = extensions.filter { it.id != COMBINE_ID }
    }

    private suspend fun awaitExtensions(): List<MusicExtension> =
        extensionsFlow.first { it != null }!!

    private suspend fun getMetadataExtension(): MusicExtension {
        return getSelectedExtension(KEY_METADATA_EXT)
    }

    private suspend fun getStreamExtensions(): List<MusicExtension> {
        val exts = awaitExtensions()
        val result = mutableListOf<MusicExtension>()
        val metaExt = getMetadataExtension()

        fun findExt(id: String?): MusicExtension? {
            if (id == null || id == "none") return null
            return exts.find { it.id == id || it.name == id }
        }

        // Handle migration from previous versions/formats
        val selectedSet = runCatching { settings.getStringSet(KEY_STREAM_EXT) }.getOrNull()
        if (!selectedSet.isNullOrEmpty()) {
            return exts.filter { it.id in selectedSet }
        }

        // Primary
        findExt(runCatching { settings.getString(KEY_STREAM_EXT) }.getOrNull())?.let { result.add(it) }

        // Alternatives
        for (i in 1..5) {
            val id = runCatching { settings.getString("${KEY_STREAM_EXT}_$i") }.getOrNull()
            findExt(id)?.let { result.add(it) } ?: break
        }

        // Add Metadata extension as fallback at the end
        if (result.none { it.id == metaExt.id }) {
            result.add(metaExt)
        }

        if (result.isEmpty() && exts.isNotEmpty()) {
            result.add(exts.first())
        }
        return result.distinctBy { it.id }
    }

    private suspend fun getSelectedExtension(key: String): MusicExtension {
        val exts = awaitExtensions()
        val selectedValue = runCatching { settings.getString(key) }.getOrNull()

        if (selectedValue == null) {
            val setValues = runCatching { settings.getStringSet(key) }.getOrNull()
            if (!setValues.isNullOrEmpty()) {
                val match = exts.find { it.id in setValues }
                if (match != null) return match
            }
            return exts.firstOrNull() ?: throw Exception("No music extensions installed")
        }

        val match = exts.find { it.id == selectedValue || it.name == selectedValue }
        if (match != null) return match

        val index = selectedValue.toIntOrNull()
        if (index != null && index in exts.indices) {
            return exts[index]
        }

        return exts.firstOrNull() ?: throw Exception("No music extensions installed")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T> MusicExtension.client(): T {
        return instance.value().getOrThrow() as T
    }

    private suspend inline fun <reified T> MusicExtension.clientOrNull(): T? {
        return instance.value().getOrNull() as? T
    }

    // --- HomeFeedClient ---

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val ext = getMetadataExtension()
        val client = ext.client<HomeFeedClient>()
        return client.loadHomeFeed()
    }

    // --- LibraryFeedClient ---

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val client = getMetadataExtension().clientOrNull<LibraryFeedClient>()
            ?: throw Exception("The selected metadata extension does not support the Library feed.")
        return client.loadLibraryFeed()
    }

    // --- SearchFeedClient ---

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val ext = getMetadataExtension()
        val client = ext.client<SearchFeedClient>()
        return client.loadSearchFeed(query)
    }

    // --- TrackClient ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val metaExt = getMetadataExtension()
        val metaClient = metaExt.client<TrackClient>()
        val metaTrack = metaClient.loadTrack(track, isDownload)

        val streamExts = getStreamExtensions()
        val isFirstAvailable = settings.getBoolean(KEY_FIRST_AVAILABLE) == true

        val allStreamables = if (isFirstAvailable) {
            selectFirstFoundPrioritized(metaTrack, streamExts, metaExt) ?: metaTrack.streamables
        } else {
            coroutineScope {
                val deferreds = streamExts.map { ext ->
                    if (ext.id == metaExt.id) {
                        async { metaTrack.streamables }
                    } else {
                        async { findTrackInStreamExtension(metaTrack, ext)?.streamables ?: emptyList() }
                    }
                }
                deferreds.awaitAll().flatten()
            }
        }

        return metaTrack.copy(streamables = allStreamables)
    }

    private suspend fun selectFirstFoundPrioritized(
        metaTrack: Track,
        exts: List<MusicExtension>,
        metaExt: MusicExtension
    ): List<Streamable>? = coroutineScope {
        val deferreds = exts.map { ext ->
            if (ext.id == metaExt.id) {
                async { metaTrack.streamables.ifEmpty { null } }
            } else {
                async { findTrackInStreamExtension(metaTrack, ext)?.streamables?.ifEmpty { null } }
            }
        }

        // Wait in priority order, but all are running in parallel
        for (deferred in deferreds) {
            val res = deferred.await()
            if (!res.isNullOrEmpty()) {
                deferreds.forEach { it.cancel() }
                return@coroutineScope res
            }
        }
        null
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        val streamExtId = streamable.extras["stream_ext_id"]
        val exts = awaitExtensions()
        val ext = if (streamExtId != null) {
            exts.find { it.id == streamExtId }
        } else {
            getMetadataExtension()
        }

        val client = ext?.client<TrackClient>() ?: throw Exception("Extension not found")
        return client.loadStreamableMedia(streamable, isDownload)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        val ext = getMetadataExtension()
        val client = ext.client<TrackClient>()
        return client.loadFeed(track)
    }

    private suspend fun findTrackInStreamExtension(metaTrack: Track, streamExt: MusicExtension): Track? {
        val searchClient: SearchFeedClient = try {
            streamExt.client()
        } catch (_: Exception) {
            return null
        }
        val trackClient: TrackClient = try {
            streamExt.client()
        } catch (_: Exception) {
            return null
        }

        val artistName = metaTrack.artists.firstOrNull()?.name ?: ""
        val query = "${metaTrack.title} $artistName".trim()
        if (query.isEmpty()) return null

        val feed = searchClient.loadSearchFeed(query)
        val firstTab = feed.tabs.firstOrNull()
        val pagedData = feed.getPagedData(firstTab)
        val shelves = loadFirstPage(pagedData.pagedData)

        val artistNameLower = artistName.lowercase()

        for (shelf in shelves) {
            val candidate = findTrackInShelf(shelf, artistNameLower)
            if (candidate != null) {
                val loaded = trackClient.loadTrack(candidate, false)
                if (loaded.streamables.isNotEmpty()) {
                    return loaded.copy(
                        streamables = loaded.streamables.map { s ->
                            s.copy(extras = s.extras + mapOf("stream_ext_id" to streamExt.id))
                        }
                    )
                }
            }
        }
        return null
    }

    private fun findTrackInShelf(shelf: Shelf, artistNameLower: String): Track? {
        return when (shelf) {
            is Shelf.Item -> {
                val media = shelf.media
                if (media is Track && matchesArtist(media, artistNameLower)) media else null
            }
            is Shelf.Lists.Tracks -> {
                shelf.list.firstOrNull { matchesArtist(it, artistNameLower) }
            }
            is Shelf.Lists.Items -> {
                shelf.list.mapNotNull { item -> item as? Track }
                    .firstOrNull { matchesArtist(it, artistNameLower) }
            }
            is Shelf.Lists.Categories -> {
                shelf.list.firstNotNullOfOrNull { cat ->
                    cat.feed?.let { /* skip nested feeds for now */ null }
                }
            }
            is Shelf.Category -> null
        }
    }

    private fun matchesArtist(track: Track, artistNameLower: String): Boolean {
        if (artistNameLower.isEmpty()) return true
        return track.artists.any { it.name.lowercase().contains(artistNameLower) }
                || artistNameLower.split(" ").any { part ->
            part.length > 2 && track.artists.any { it.name.lowercase().contains(part) }
        }
    }

    private suspend fun <T : Any> loadFirstPage(pagedData: PagedData<T>): List<T> {
        val page = pagedData.loadPage(null)
        return page.data
    }

    // --- AlbumClient ---

    override suspend fun loadAlbum(album: Album): Album {
        val ext = getMetadataExtension()
        val client = ext.client<AlbumClient>()
        return client.loadAlbum(album)
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val ext = getMetadataExtension()
        val client = ext.client<AlbumClient>()
        return client.loadTracks(album)
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val ext = getMetadataExtension()
        val client = ext.client<AlbumClient>()
        return client.loadFeed(album)
    }

    // --- ArtistClient ---

    override suspend fun loadArtist(artist: Artist): Artist {
        val ext = getMetadataExtension()
        val client = ext.client<ArtistClient>()
        return client.loadArtist(artist)
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val ext = getMetadataExtension()
        val client = ext.client<ArtistClient>()
        return client.loadFeed(artist)
    }

    // --- PlaylistClient ---

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val ext = getMetadataExtension()
        val client = ext.client<PlaylistClient>()
        return client.loadPlaylist(playlist)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val ext = getMetadataExtension()
        val client = ext.client<PlaylistClient>()
        return client.loadTracks(playlist)
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        val ext = getMetadataExtension()
        val client = ext.client<PlaylistClient>()
        return client.loadFeed(playlist)
    }

    // --- PlaylistEditClient ---

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        val client = getMetadataExtension().clientOrNull<PlaylistEditClient>() ?: return emptyList()
        return client.listEditablePlaylists(track)
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        val client = getMetadataExtension().clientOrNull<PlaylistEditClient>()
            ?: throw Exception("The selected metadata extension does not support creating playlists.")
        return client.createPlaylist(title, description)
    }

    override suspend fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>, position: Int, previousTracks: List<Track>) {
        val client = getMetadataExtension().clientOrNull<PlaylistEditClient>()
            ?: throw Exception("The selected metadata extension does not support editing playlists.")
        client.addTracksToPlaylist(playlist, tracks, position, previousTracks)
    }

    override suspend fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) {
        val client = getMetadataExtension().clientOrNull<PlaylistEditClient>()
            ?: throw Exception("The selected metadata extension does not support editing playlists.")
        client.removeTracksFromPlaylist(playlist, tracks, indexes)
    }

    override suspend fun moveTrackInPlaylist(playlist: Playlist, tracks: List<Track>, fromPosition: Int, toPosition: Int) {
        val client = getMetadataExtension().clientOrNull<PlaylistEditClient>()
            ?: throw Exception("The selected metadata extension does not support editing playlists.")
        client.moveTrackInPlaylist(playlist, tracks, fromPosition, toPosition)
    }

    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) {
        val client = getMetadataExtension().clientOrNull<PlaylistEditClient>()
            ?: throw Exception("The selected metadata extension does not support editing playlists.")
        client.editPlaylistMetadata(playlist, title, description)
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        val client = getMetadataExtension().clientOrNull<PlaylistEditClient>()
            ?: throw Exception("The selected metadata extension does not support deleting playlists.")
        client.deletePlaylist(playlist)
    }

    // --- SaveClient ---

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean {
        val client = getMetadataExtension().clientOrNull<SaveClient>() ?: return false
        return client.isItemSaved(item)
    }

    override suspend fun saveToLibrary(item: EchoMediaItem, isSave: Boolean) {
        val client = getMetadataExtension().clientOrNull<SaveClient>()
            ?: throw Exception("The selected metadata extension does not support saving items.")
        client.saveToLibrary(item, isSave)
    }

    // --- LikeClient ---

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        val client = getMetadataExtension().clientOrNull<LikeClient>() ?: return false
        return client.isItemLiked(item)
    }

    override suspend fun likeItem(item: EchoMediaItem, isLike: Boolean) {
        val client = getMetadataExtension().clientOrNull<LikeClient>()
            ?: throw Exception("The selected metadata extension does not support liking items.")
        client.likeItem(item, isLike)
    }

    // --- ShareClient ---

    override suspend fun onShare(item: EchoMediaItem): String {
        val client = getMetadataExtension().clientOrNull<ShareClient>()
            ?: throw Exception("The selected metadata extension does not support sharing.")
        return client.onShare(item)
    }

    // --- FollowClient ---

    override suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val client = getMetadataExtension().clientOrNull<FollowClient>() ?: return false
        return client.isFollowing(item)
    }

    override suspend fun followItem(item: EchoMediaItem, isFollow: Boolean) {
        val client = getMetadataExtension().clientOrNull<FollowClient>()
            ?: throw Exception("The selected metadata extension does not support following.")
        client.followItem(item, isFollow)
    }

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? {
        val client = getMetadataExtension().clientOrNull<FollowClient>() ?: return null
        return client.getFollowersCount(item)
    }

    // --- HideClient ---

    override suspend fun isItemHidden(item: EchoMediaItem): Boolean {
        val client = getMetadataExtension().clientOrNull<HideClient>() ?: return false
        return client.isItemHidden(item)
    }

    override suspend fun hideItem(item: EchoMediaItem, isHide: Boolean) {
        val client = getMetadataExtension().clientOrNull<HideClient>()
            ?: throw Exception("The selected metadata extension does not support hiding items.")
        client.hideItem(item, isHide)
    }

    // --- RadioClient ---

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        val client = getMetadataExtension().clientOrNull<RadioClient>()
            ?: throw Exception("The selected metadata extension does not support radio.")
        return client.radio(item, context)
    }

    override suspend fun loadRadio(radio: Radio): Radio {
        val client = getMetadataExtension().clientOrNull<RadioClient>()
            ?: throw Exception("The selected metadata extension does not support radio.")
        return client.loadRadio(radio)
    }

    override suspend fun loadTracks(radio: Radio): Feed<Track> {
        val client = getMetadataExtension().clientOrNull<RadioClient>()
            ?: throw Exception("The selected metadata extension does not support radio.")
        return client.loadTracks(radio)
    }

    // --- LyricsClient ---

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val client = getMetadataExtension().clientOrNull<LyricsClient>()
            ?: return emptyList<Lyrics>().toFeed()
        return client.searchTrackLyrics(clientId, track)
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        val client = getMetadataExtension().clientOrNull<LyricsClient>()
            ?: return lyrics
        return client.loadLyrics(lyrics)
    }

    // --- LyricsSearchClient ---

    override suspend fun searchLyrics(query: String): Feed<Lyrics> {
        val client = getMetadataExtension().clientOrNull<LyricsSearchClient>()
            ?: return emptyList<Lyrics>().toFeed()
        return client.searchLyrics(query)
    }
}