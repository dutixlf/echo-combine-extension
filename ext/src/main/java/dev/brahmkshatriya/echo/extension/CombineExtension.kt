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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class CombineExtension : ExtensionClient, MusicExtensionsProvider,
    HomeFeedClient, SearchFeedClient, TrackClient,
    AlbumClient, ArtistClient, PlaylistClient, PlaylistEditClient,
    SaveClient, LikeClient, ShareClient, FollowClient, HideClient, RadioClient,
    LibraryFeedClient {

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
                "Return only streams from the highest priority extension that finds the track",
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

        // Migration
        val selectedSet = runCatching { settings.getStringSet(KEY_STREAM_EXT) }.getOrNull()
        if (!selectedSet.isNullOrEmpty()) {
            return exts.filter { it.id in selectedSet }
        }

        // Priority Chain: Preferred -> 2nd -> ...
        findExt(runCatching { settings.getString(KEY_STREAM_EXT) }.getOrNull())?.let { result.add(it) }
        for (i in 1..5) {
            val id = runCatching { settings.getString("${KEY_STREAM_EXT}_$i") }.getOrNull()
            findExt(id)?.let { result.add(it) } ?: break
        }

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

    override suspend fun loadHomeFeed(): Feed<Shelf> = getMetadataExtension().client<HomeFeedClient>().loadHomeFeed()

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val client = getMetadataExtension().clientOrNull<LibraryFeedClient>()
            ?: throw Exception("The selected metadata extension does not support the Library feed.")
        return client.loadLibraryFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = getMetadataExtension().client<SearchFeedClient>().loadSearchFeed(query)

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val metaExt = getMetadataExtension()
        val metaClient = metaExt.client<TrackClient>()
        val metaTrack = metaClient.loadTrack(track, isDownload)

        val streamExts = getStreamExtensions()
        val isFirstAvailable = settings.getBoolean(KEY_FIRST_AVAILABLE) == true

        val allStreamables = if (isFirstAvailable) {
            selectFirstFound(metaTrack, streamExts, metaExt) ?: metaTrack.streamables
        } else {
            coroutineScope {
                val deferreds = streamExts.mapIndexed { index, ext ->
                    // huge priority shift: preferred = 100000000 bonus. do better
                    val priorityBonus = (streamExts.size - index) * 10_000_000
                    if (ext.id == metaExt.id) {
                        async { metaTrack.streamables.map { it.copy(quality = it.quality + priorityBonus) } }
                    } else {
                        async { findTrackInStreamExtension(metaTrack, ext)?.streamables?.map { it.copy(quality = it.quality + priorityBonus) } ?: emptyList() }
                    }
                }
                deferreds.awaitAll().flatten().sortedByDescending { it.quality }.map { it }
            }
        }

        return metaTrack.copy(streamables = allStreamables)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun selectFirstFound(
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

        for (deferred in deferreds) {
            val res = deferred.await()
            if (!res.isNullOrEmpty()) {
                deferreds.forEach { it.cancel() }
                return@coroutineScope res
            }
        }
        null
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val streamExtId = streamable.extras["stream_ext_id"]
        val exts = awaitExtensions()
        val ext = if (streamExtId != null) exts.find { it.id == streamExtId } else getMetadataExtension()
        return ext?.client<TrackClient>()?.loadStreamableMedia(streamable, isDownload) ?: throw Exception("Extension not found")
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = getMetadataExtension().client<TrackClient>().loadFeed(track)

    private suspend fun findTrackInStreamExtension(metaTrack: Track, streamExt: MusicExtension): Track? {
        val searchClient = try { streamExt.client<SearchFeedClient>() } catch (_: Exception) { return null }
        val trackClient = try { streamExt.client<TrackClient>() } catch (_: Exception) { return null }

        val artistName = metaTrack.artists.firstOrNull()?.name ?: ""
        val query = "${metaTrack.title} $artistName".trim()
        if (query.isEmpty()) return null

        val feed = try { searchClient.loadSearchFeed(query) } catch (_: Exception) { return null }
        
        val shelves = loadFirstPage(feed.getPagedData(feed.tabs.firstOrNull()).pagedData)
        val artistNameLower = artistName.lowercase()
        val trackTitleLower = metaTrack.title.lowercase()

        for (shelf in shelves) {
            val candidate = findTrackInShelf(shelf, artistNameLower, trackTitleLower)
            if (candidate != null) {
                val loaded = try { trackClient.loadTrack(candidate, false) } catch (_: Exception) { null }
                if (loaded != null && loaded.streamables.isNotEmpty()) {
                    return loaded.copy(streamables = loaded.streamables.map { s ->
                        s.copy(extras = s.extras + mapOf("stream_ext_id" to streamExt.id))
                    })
                }
            }
        }
        return null
    }

    private fun findTrackInShelf(shelf: Shelf, artistNameLower: String, trackTitleLower: String): Track? {
        return when (shelf) {
            is Shelf.Item -> {
                val media = shelf.media
                if (media is Track && matches(media, artistNameLower, trackTitleLower)) media else null
            }
            is Shelf.Lists.Tracks -> shelf.list.firstOrNull { matches(it, artistNameLower, trackTitleLower) }
            is Shelf.Lists.Items -> shelf.list.mapNotNull { item -> item as? Track }.firstOrNull { matches(it, artistNameLower, trackTitleLower) }
            else -> null
        }
    }

    private fun matches(track: Track, artistNameLower: String, trackTitleLower: String): Boolean {
        val titleMatch = track.title.lowercase().contains(trackTitleLower) || trackTitleLower.contains(track.title.lowercase())
        if (!titleMatch) return false
        if (artistNameLower.isEmpty()) return true
        return track.artists.any { it.name.lowercase().contains(artistNameLower) } ||
               artistNameLower.split(" ").any { part -> part.length > 2 && track.artists.any { it.name.lowercase().contains(part) } }
    }

    private suspend fun <T : Any> loadFirstPage(pagedData: PagedData<T>): List<T> = try { pagedData.loadPage(null).data } catch (_: Exception) { emptyList() }

    override suspend fun loadAlbum(album: Album): Album = getMetadataExtension().client<AlbumClient>().loadAlbum(album)
    override suspend fun loadTracks(album: Album): Feed<Track>? = getMetadataExtension().client<AlbumClient>().loadTracks(album)
    override suspend fun loadFeed(album: Album): Feed<Shelf>? = getMetadataExtension().client<AlbumClient>().loadFeed(album)
    override suspend fun loadArtist(artist: Artist): Artist = getMetadataExtension().client<ArtistClient>().loadArtist(artist)
    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = getMetadataExtension().client<ArtistClient>().loadFeed(artist)
    override suspend fun loadPlaylist(playlist: Playlist): Playlist = getMetadataExtension().client<PlaylistClient>().loadPlaylist(playlist)
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = getMetadataExtension().client<PlaylistClient>().loadTracks(playlist)
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = getMetadataExtension().client<PlaylistClient>().loadFeed(playlist)
    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.listEditablePlaylists(track) ?: emptyList()
    override suspend fun createPlaylist(title: String, description: String?): Playlist = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.createPlaylist(title, description) ?: throw Exception("Not supported")
    override suspend fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>, position: Int, previousTracks: List<Track>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.addTracksToPlaylist(playlist, tracks, position, previousTracks) ?: throw Exception("Not supported")
    override suspend fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.removeTracksFromPlaylist(playlist, tracks, indexes) ?: throw Exception("Not supported")
    override suspend fun moveTrackInPlaylist(playlist: Playlist, tracks: List<Track>, fromPosition: Int, toPosition: Int) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.moveTrackInPlaylist(playlist, tracks, fromPosition, toPosition) ?: throw Exception("Not supported")
    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.editPlaylistMetadata(playlist, title, description) ?: throw Exception("Not supported")
    override suspend fun deletePlaylist(playlist: Playlist) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.deletePlaylist(playlist) ?: throw Exception("Not supported")
    override suspend fun isItemSaved(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<SaveClient>()?.isItemSaved(item) ?: false
    override suspend fun saveToLibrary(item: EchoMediaItem, isSave: Boolean) = getMetadataExtension().clientOrNull<SaveClient>()?.saveToLibrary(item, isSave) ?: throw Exception("Not supported")
    override suspend fun isItemLiked(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<LikeClient>()?.isItemLiked(item) ?: false
    override suspend fun likeItem(item: EchoMediaItem, isLike: Boolean) = getMetadataExtension().clientOrNull<LikeClient>()?.likeItem(item, isLike) ?: throw Exception("Not supported")
    override suspend fun onShare(item: EchoMediaItem): String = getMetadataExtension().clientOrNull<ShareClient>()?.onShare(item) ?: throw Exception("Not supported")
    override suspend fun isFollowing(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<FollowClient>()?.isFollowing(item) ?: false
    override suspend fun followItem(item: EchoMediaItem, isFollow: Boolean) = getMetadataExtension().clientOrNull<FollowClient>()?.followItem(item, isFollow) ?: throw Exception("Not supported")
    override suspend fun getFollowersCount(item: EchoMediaItem): Long? = getMetadataExtension().clientOrNull<FollowClient>()?.getFollowersCount(item)
    override suspend fun isItemHidden(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<HideClient>()?.isItemHidden(item) ?: false
    override suspend fun hideItem(item: EchoMediaItem, isHide: Boolean) = getMetadataExtension().clientOrNull<HideClient>()?.hideItem(item, isHide) ?: throw Exception("Not supported")
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.radio(item, context) ?: throw Exception("Not supported")
    override suspend fun loadRadio(radio: Radio): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.loadRadio(radio) ?: throw Exception("Not supported")
    override suspend fun loadTracks(radio: Radio): Feed<Track> = getMetadataExtension().clientOrNull<RadioClient>()?.loadTracks(radio) ?: throw Exception("Not supported")
}
