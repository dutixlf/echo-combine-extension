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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

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
        private const val STREAM_EXT_ID = "stream_ext_id"
        private const val ORIGINAL_STREAM = "combine_original_stream"
        private const val MATCH_TRACK = "combine_match_track"

        private val AUDIO_EXTENSION = Regex("\\.(?:m4a|mp3|flac|ogg|opus|wav|aac)$", RegexOption.IGNORE_CASE)
        private val FEATURE_BRACKETS = Regex("[\\[(]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+[^\\])]*[\\])]", RegexOption.IGNORE_CASE)
        private val FEATURE_SUFFIX = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*?(?=\\s[-–—]\\s|[\\[(]|$)", RegexOption.IGNORE_CASE)
    }

    private lateinit var settings: Settings

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    override suspend fun getSettingItems(): List<Setting> {
        val extensions = awaitExtensions()
        val extIds = extensions.map { it.id }
        val extNames = extensions.map { it.name }

        val metadataId = if (extensions.isEmpty()) null else getMetadataExtension().id
        val streamExtensions = extensions.filter { it.id != metadataId }
        val altIds = listOf("none") + streamExtensions.map { it.id }
        val altNames = listOf("None") + streamExtensions.map { it.name }

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
                altNames,
                altIds,
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
        extensionsFlow.value = extensions.filter { it.id != COMBINE_ID && it.isEnabled }
    }

    private suspend fun awaitExtensions(): List<MusicExtension> =
        extensionsFlow.first { it != null }!!

    private suspend fun getMetadataExtension(): MusicExtension {
        return getSelectedExtension(KEY_METADATA_EXT)
    }

    private suspend fun getStreamExtensions(): List<MusicExtension> {
        val metaExt = getMetadataExtension()
        val exts = awaitExtensions().filter { it.id != metaExt.id }
        val result = mutableListOf<MusicExtension>()

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
        for (i in 0..5) {
            val key = if (i == 0) KEY_STREAM_EXT else "${KEY_STREAM_EXT}_$i"
            val id = runCatching { settings.getString(key) }.getOrNull()
            if (id == null || id == "none") break
            findExt(id)?.let { result.add(it) }
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
            selectFirstFound(metaTrack, streamExts, isDownload)
        } else {
            coroutineScope {
                streamExts.map { ext ->
                    async { findTrackInStreamExtension(metaTrack, ext, isDownload)?.streamables.orEmpty().sortedByDescending { it.quality } }
                }.awaitAll().flatten()
            }
        }.let { streams ->
            // Echo sorts by quality: compact ranks preserve provider priority without overflow.
            streams.mapIndexed { index, stream -> stream.copy(quality = streams.size - index) }
        }

        return metaTrack.copy(streamables = allStreamables)
    }

    private suspend fun selectFirstFound(
        metaTrack: Track,
        exts: List<MusicExtension>,
        isDownload: Boolean
    ): List<Streamable> {
        for (ext in exts) {
            val streams = findTrackInStreamExtension(metaTrack, ext, isDownload)?.streamables
            if (!streams.isNullOrEmpty()) return streams.sortedByDescending { it.quality }
        }
        return emptyList()
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val extensions = getStreamExtensions()
        val index = extensions.indexOfFirst { it.id == streamable.extras[STREAM_EXT_ID] }
        require(index >= 0) { "Stream has no configured audio provider. Reload the track and check Combine settings." }
        val nativeStream = unwrapStream(streamable)
        val failure = try {
            return extensions[index].client<TrackClient>().loadStreamableMedia(nativeStream, isDownload)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }

        // Retry resolution failures, not HTTP errors raised later by Echo's player.
        if (streamable.type != Streamable.MediaType.Server) throw failure
        val matchData = streamable.extras[MATCH_TRACK] ?: throw failure
        val track = Json.decodeFromString<Track>(matchData)
        for (extension in extensions.drop(index + 1)) {
            val loaded = findTrackInStreamExtension(track, extension, isDownload) ?: continue
            for (server in loaded.servers.sortedByDescending { it.quality }) {
                try {
                    return extension.client<TrackClient>().loadStreamableMedia(unwrapStream(server), isDownload)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error !== failure) failure.addSuppressed(error)
                }
            }
        }
        throw failure
    }

    private fun unwrapStream(stream: Streamable): Streamable = stream.extras[ORIGINAL_STREAM]?.let {
        Json.decodeFromString<Streamable>(it)
    } ?: stream.copy(extras = stream.extras - STREAM_EXT_ID)

    private fun wrapStreams(loaded: Track, metadata: Track, extensionId: String): Track {
        val matchData = Json.encodeToString(Track(metadata.id, metadata.title, artists = metadata.artists, duration = metadata.duration))
        return loaded.copy(streamables = loaded.streamables.map { stream ->
            stream.copy(extras = stream.extras + mapOf(
                STREAM_EXT_ID to extensionId,
                ORIGINAL_STREAM to Json.encodeToString(stream),
                MATCH_TRACK to matchData
            ))
        })
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = getMetadataExtension().client<TrackClient>().loadFeed(track)

    private suspend fun findTrackInStreamExtension(metaTrack: Track, streamExt: MusicExtension, isDownload: Boolean): Track? = tryProvider {
        val searchClient = streamExt.client<SearchFeedClient>()
        val trackClient = streamExt.client<TrackClient>()
        val artistName = metaTrack.artists.firstOrNull()?.name.orEmpty()
        val query = "${cleanTitle(metaTrack.title)} $artistName".trim()
        if (query.isEmpty()) return@tryProvider null

        val feed = searchClient.loadSearchFeed(query)
        // ponytail: search only the first page; add bounded pagination if providers hide exact matches later.
        val shelves = feed.getPagedData(feed.notSortTabs.firstOrNull()).pagedData.loadPage(null).data
        val candidates = shelves.flatMap { shelf ->
            when (shelf) {
                is Shelf.Item -> listOfNotNull(shelf.media as? Track)
                is Shelf.Lists.Tracks -> shelf.list
                is Shelf.Lists.Items -> shelf.list.filterIsInstance<Track>()
                else -> emptyList()
            }
        }
        for (candidate in candidates.filter { matches(it, metaTrack) }) {
            val loaded = tryProvider { trackClient.loadTrack(candidate, isDownload) }
            if (loaded != null && loaded.servers.isNotEmpty()) {
                return@tryProvider wrapStreams(loaded, metaTrack, streamExt.id)
            }
        }
        null
    }

    private fun matches(candidate: Track, wanted: Track): Boolean {
        val artist = normalize(wanted.artists.firstOrNull()?.name.orEmpty())
        var title = cleanTitle(candidate.title)
        val isFile = AUDIO_EXTENSION.containsMatchIn(candidate.title)
        val fileArtist = if (isFile) normalize(title.substringAfterLast(" - ", "")) else ""
        val artistMatches = candidate.artists.any { normalize(it.name) == artist } ||
            (candidate.artists.isEmpty() && fileArtist == artist)
        if (artist.isNotEmpty() && !artistMatches) return false
        if (isFile && fileArtist == artist && artist.isNotEmpty()) title = title.substringBeforeLast(" - ")
        val wantedTitle = normalize(cleanTitle(wanted.title))
        return wantedTitle.isNotEmpty() && normalize(title) == wantedTitle
    }

    private fun cleanTitle(title: String): String = title
        .replace(AUDIO_EXTENSION, "")
        .replace(FEATURE_BRACKETS, "")
        .replace(FEATURE_SUFFIX, "")
        .trim()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("['’]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private suspend fun <T> tryProvider(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

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
    override suspend fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.addTracksToPlaylist(playlist, tracks, index, new) ?: throw Exception("Not supported")
    override suspend fun removeTracksFromPlaylist(playlist: Playlist, tracks: List<Track>, indexes: List<Int>) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.removeTracksFromPlaylist(playlist, tracks, indexes) ?: throw Exception("Not supported")
    override suspend fun moveTrackInPlaylist(playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.moveTrackInPlaylist(playlist, tracks, fromIndex, toIndex) ?: throw Exception("Not supported")
    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.editPlaylistMetadata(playlist, title, description) ?: throw Exception("Not supported")
    override suspend fun deletePlaylist(playlist: Playlist) = getMetadataExtension().clientOrNull<PlaylistEditClient>()?.deletePlaylist(playlist) ?: throw Exception("Not supported")
    override suspend fun isItemSaved(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<SaveClient>()?.isItemSaved(item) ?: false
    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) = getMetadataExtension().clientOrNull<SaveClient>()?.saveToLibrary(item, shouldSave) ?: throw Exception("Not supported")
    override suspend fun isItemLiked(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<LikeClient>()?.isItemLiked(item) ?: false
    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) = getMetadataExtension().clientOrNull<LikeClient>()?.likeItem(item, shouldLike) ?: throw Exception("Not supported")
    override suspend fun onShare(item: EchoMediaItem): String = getMetadataExtension().clientOrNull<ShareClient>()?.onShare(item) ?: throw Exception("Not supported")
    override suspend fun isFollowing(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<FollowClient>()?.isFollowing(item) ?: false
    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) = getMetadataExtension().clientOrNull<FollowClient>()?.followItem(item, shouldFollow) ?: throw Exception("Not supported")
    override suspend fun getFollowersCount(item: EchoMediaItem): Long? = getMetadataExtension().clientOrNull<FollowClient>()?.getFollowersCount(item)
    override suspend fun isItemHidden(item: EchoMediaItem): Boolean = getMetadataExtension().clientOrNull<HideClient>()?.isItemHidden(item) ?: false
    override suspend fun hideItem(item: EchoMediaItem, shouldHide: Boolean) = getMetadataExtension().clientOrNull<HideClient>()?.hideItem(item, shouldHide) ?: throw Exception("Not supported")
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.radio(item, context) ?: throw Exception("Not supported")
    override suspend fun loadRadio(radio: Radio): Radio = getMetadataExtension().clientOrNull<RadioClient>()?.loadRadio(radio) ?: throw Exception("Not supported")
    override suspend fun loadTracks(radio: Radio): Feed<Track> = getMetadataExtension().clientOrNull<RadioClient>()?.loadTracks(radio) ?: throw Exception("Not supported")
}
