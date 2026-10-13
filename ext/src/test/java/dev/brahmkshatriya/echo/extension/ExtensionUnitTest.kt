package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Injectable
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExtensionUnitTest {
    private fun track(title: String = "Evil Deeds", artist: String = "Eminem", id: String = title) =
        Track(id, title, artists = listOf(Artist(artist, artist)))

    private class Provider(var results: List<Track> = emptyList()) : ExtensionClient, TrackClient, SearchFeedClient {
        override suspend fun getSettingItems() = emptyList<dev.brahmkshatriya.echo.common.settings.Setting>()
        override fun setSettings(settings: dev.brahmkshatriya.echo.common.settings.Settings) {}

        var streamLoads = 0
        var searches = 0
        var lastQuery: String? = null
        var loadFailure: Exception? = null
        var searchFailure: Exception? = null
        var mediaFailure: Exception? = null
        var searchFeed: Feed<Shelf>? = null
        var streams: List<Streamable>? = null
        var unplayableIds = emptySet<String>()
        var beforeSearch: suspend () -> Unit = {}
        var loadedStreamable: Streamable? = null
        var download: Boolean? = null

        override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
            searches++
            lastQuery = query
            beforeSearch()
            searchFailure?.let { throw it }
            searchFeed?.let { return it }
            return listOf<Shelf>(Shelf.Lists.Tracks("results", "Results", results)).toFeed()
        }

        override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
            download = isDownload
            loadFailure?.let { throw it }
            if (track.id in unplayableIds) return track.copy(streamables = emptyList())
            return track.copy(streamables = streams ?: listOf(Streamable.server(track.id, 100, extras = mapOf("token" to "original"))))
        }

        override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
            streamLoads++
            loadedStreamable = streamable
            download = isDownload
            mediaFailure?.let { throw it }
            return Streamable.Media.Server(listOf(Streamable.Source.Http(NetworkRequest("https://test/${streamable.id}"))), false)
        }

        override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
    }

    private fun extension(id: String, provider: Provider): MusicExtension = MusicExtension(
        Metadata("", "", ImportType.File, ExtensionType.MUSIC, id, id, "1", "", ""),
        Injectable<ExtensionClient>({ provider }, emptyList())
    )

    private fun combine(settings: MockedSettings, vararg providers: Pair<String, Provider>) = CombineExtension().apply {
        setSettings(settings)
        setMusicExtensions(providers.map { (id, provider) -> extension(id, provider) })
    }

    private fun settings(firstAvailable: Boolean = false) = MockedSettings().apply {
        putString(CombineExtension.KEY_METADATA_EXT, "metadata")
        putString(CombineExtension.KEY_STREAM_EXT, "audio")
        putBoolean(CombineExtension.KEY_FIRST_AVAILABLE, firstAvailable)
    }

    @Test
    fun streamSettingsExcludeMetadataAndDisabledProviders() = runTest {
        val config = settings()
        val combine = combine(config, "metadata" to Provider(), "audio" to Provider())
        combine.setMusicExtensions(listOf(
            extension("metadata", Provider()),
            extension("audio", Provider()),
            extension("disabled", Provider()).let { it.copy(metadata = it.metadata.copy(isEnabled = false)) },
            extension(CombineExtension.COMBINE_ID, Provider())
        ))
        val choices = combine.getSettingItems().filterIsInstance<dev.brahmkshatriya.echo.common.settings.SettingList>()
            .single { it.key == CombineExtension.KEY_STREAM_EXT }.entryValues
        assertEquals(listOf("none", "audio"), choices)
        config.putString("stream_extension", "disabled")
        assertTrue(combine.loadTrack(track(), false).streamables.isEmpty())
        combine.setMusicExtensions(emptyList())
        assertTrue(combine.getSettingItems().isNotEmpty())
    }

    @Test
    fun priorityDoesNotOverflowAndProviderReceivesUnmodifiedStreamAfterSerialization() = runTest {
        val original = Streamable.server("preferred", Int.MIN_VALUE, "Original", mapOf("stream_ext_id" to "provider-owned", "token" to "secret"))
        val audio = Provider(listOf(track())).apply { streams = listOf(original) }
        val backup = Provider(listOf(track())).apply { streams = listOf(Streamable.server("backup", Int.MAX_VALUE)) }
        val config = settings().apply { putString("stream_extension_1", "backup") }
        val combine = combine(config, "metadata" to Provider(), "audio" to audio, "backup" to backup)
        val result = combine.loadTrack(track(), false)
        assertEquals(listOf("preferred", "backup"), result.servers.sortedByDescending { it.quality }.map { it.id })
        val restored = Json.decodeFromString<Streamable>(Json.encodeToString(result.servers.first()))
        combine.loadStreamableMedia(restored, false)
        assertEquals(original, audio.loadedStreamable)
    }

    @Test
    fun firstAvailableDoesNotSearchUnusedBackupsAndAllModeStillHonorsPriority() = runTest {
        for (firstAvailable in listOf(false, true)) {
            val audio = Provider(listOf(track(id = "preferred"))).apply { beforeSearch = { delay(100) } }
            val backup = Provider(listOf(track(id = "backup")))
            val config = settings(firstAvailable).apply {
                putString("stream_extension_1", "audio") // Duplicate selections should not cause duplicate work.
                putString("stream_extension_2", "backup")
            }
            val combine = combine(config, "metadata" to Provider(), "audio" to audio, "backup" to backup)
            val result = combine.loadTrack(track(), false)
            assertEquals("preferred", result.servers.first().id)
            assertEquals(1, audio.searches)
            assertEquals(if (firstAvailable) 0 else 1, backup.searches)
        }
    }

    @Test
    fun searchPagingAndTrackErrorsDoNotPreventOtherProvidersFromWorking() = runTest {
        for (phase in listOf("search", "page", "track")) {
            for (firstAvailable in listOf(false, true)) {
                val audio = Provider(listOf(track())).apply {
                    val failure = IllegalStateException("provider failed")
                    when (phase) {
                        "search" -> searchFailure = failure
                        "page" -> searchFeed = Feed(emptyList()) { throw failure }
                        "track" -> loadFailure = failure
                    }
                }
                val config = settings(firstAvailable).apply { putString("stream_extension_1", "backup") }
                val combine = combine(config, "metadata" to Provider(), "audio" to audio, "backup" to Provider(listOf(track(id = "backup"))))
                assertEquals("backup", combine.loadTrack(track(), false).servers.single().id)
            }
        }
    }

    @Test
    fun continuesToOtherCandidatesInTheSameShelfWhenFirstHasNoAudio() = runTest {
        val audio = Provider(listOf(track(id = "unplayable"), track(id = "playable"))).apply { unplayableIds = setOf("unplayable") }
        val combine = combine(settings(), "metadata" to Provider(), "audio" to audio)
        assertEquals("playable", combine.loadTrack(track(), false).servers.single().id)
        audio.unplayableIds = emptySet()
        audio.streams = listOf(Streamable.background("video", 1))
        assertTrue(combine.loadTrack(track(), false).streamables.isEmpty())
    }

    @Test
    fun cancellationIsNeverTreatedAsAProviderFailure() = runTest {
        for (phase in listOf("search", "page", "track", "media")) {
            val audio = Provider(listOf(track()))
            val config = settings(true).apply { putString("stream_extension_1", "backup") }
            val backup = Provider(listOf(track()))
            val combine = combine(config, "metadata" to Provider(), "audio" to audio, "backup" to backup)
            val stream = combine.loadTrack(track(), false).servers.single()
            val cancellation = CancellationException("cancelled")
            when (phase) {
                "search" -> audio.searchFailure = cancellation
                "page" -> audio.searchFeed = Feed(emptyList()) { throw cancellation }
                "track" -> audio.loadFailure = cancellation
                "media" -> audio.mediaFailure = cancellation
            }
            try {
                if (phase == "media") combine.loadStreamableMedia(stream, false) else combine.loadTrack(track(), false)
                fail("Cancellation swallowed during $phase")
            } catch (error: CancellationException) {
                assertSame(cancellation, error)
            }
            assertEquals(0, backup.searches)
        }
    }

    @Test
    fun fallbackStopsAfterAllProvidersFailAndNeverUsesMetadata() = runTest {
        val primaryFailure = IllegalStateException("HTTP 403")
        val metadata = Provider()
        val audio = Provider(listOf(track())).apply { mediaFailure = primaryFailure }
        val backup = Provider(listOf(track())).apply { mediaFailure = IllegalStateException("HTTP 429") }
        val config = settings(true).apply { putString("stream_extension_1", "backup") }
        val combine = combine(config, "metadata" to metadata, "audio" to audio, "backup" to backup)
        val stream = combine.loadTrack(track(), false).servers.single()
        try {
            combine.loadStreamableMedia(stream, false)
            fail("Expected all providers to fail")
        } catch (error: IllegalStateException) {
            assertSame(primaryFailure, error)
            assertEquals(1, error.suppressed.size)
        }
        assertEquals(1, audio.streamLoads)
        assertEquals(1, backup.streamLoads)
        assertEquals(0, metadata.streamLoads)
    }

    @Test
    fun changedMetadataSelectionBlocksPreviouslyResolvedAudioProvider() = runTest {
        val config = settings()
        val audio = Provider(listOf(track()))
        val combine = combine(config, "metadata" to Provider(), "audio" to audio)
        val stream = combine.loadTrack(track(), false).servers.single()
        config.putString("metadata_extension", "audio")
        try {
            combine.loadStreamableMedia(stream, false)
            fail("Previously resolved provider is now metadata")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, audio.streamLoads)
    }

    @Test
    fun mediaResolutionFallsBackToNextAudioProviderAndPreservesNativePayload() = runTest {
        for (firstAvailable in listOf(false, true)) {
            val config = settings(firstAvailable).apply { putString("stream_extension_1", "backup") }
            val metadata = Provider()
            val audio = Provider(listOf(track())).apply { mediaFailure = IllegalStateException("HTTP 429") }
            val backup = Provider(listOf(track(id = "backup-track")))
            val combine = combine(config, "metadata" to metadata, "audio" to audio, "backup" to backup)
            val stream = combine.loadTrack(track(), true).servers.first()
            val media = combine.loadStreamableMedia(stream, true) as Streamable.Media.Server
            assertEquals("https://test/backup-track", (media.sources.single() as Streamable.Source.Http).request.url)
            assertEquals(Streamable.server("backup-track", 100, extras = mapOf("token" to "original")), backup.loadedStreamable)
            assertEquals(true, backup.download)
            assertEquals(0, metadata.streamLoads)
        }
    }

    @Test
    fun mediaRejectsUntaggedMetadataAndUnconfiguredProviders() = runTest {
        val metadata = Provider()
        val audio = Provider(listOf(track()))
        val combine = combine(settings(), "metadata" to metadata, "audio" to audio, "other" to Provider())
        for (id in listOf(null, "metadata", "other", "missing")) {
            val stream = Streamable.server("bad", 0, extras = id?.let { mapOf("stream_ext_id" to it) }.orEmpty())
            try {
                combine.loadStreamableMedia(stream, false)
                fail("Accepted forbidden provider: $id")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, metadata.streamLoads)
        assertEquals(0, audio.streamLoads)
    }

    @Test
    fun metadataCannotBeSelectedViaLegacyOrPrioritySettings() = runTest {
        for (legacy in listOf(false, true)) {
            for (firstAvailable in listOf(false, true)) {
                val config = settings(firstAvailable).apply {
                    if (legacy) putStringSet("stream_extension", setOf("metadata", "audio"))
                    else {
                        putString("stream_extension", "metadata")
                        putString("stream_extension_1", "audio")
                    }
                }
                val combine = combine(config, "metadata" to Provider(), "audio" to Provider(listOf(track())))
                assertEquals(listOf("audio"), combine.loadTrack(track(), false).servers.map { it.extras["stream_ext_id"] })
            }
        }
    }

    @Test
    fun excludesMetadataAndUnavailableSelectionsWithoutBlockingLaterBackups() = runTest {
        val config = settings().apply {
            putString("stream_extension_1", "metadata")
            putString("stream_extension_2", "missing")
            putString("stream_extension_3", "backup")
        }
        val combine = combine(config, "metadata" to Provider(), "audio" to Provider(), "backup" to Provider(listOf(track(id = "backup"))))
        assertEquals("backup", combine.loadTrack(track(), false).servers.single().id)
    }

    @Test
    fun absentOrInvalidConfigurationNeverChoosesAnArbitraryProvider() = runTest {
        for (value in listOf(null, "none", "missing", "metadata")) {
            val config = settings().apply { putString("stream_extension", value) }
            val combine = combine(config, "metadata" to Provider(), "audio" to Provider(listOf(track())))
            assertTrue(combine.loadTrack(track(), false).servers.isEmpty())
        }
    }

    @Test
    fun issue9RejectsRegularRecordingsForAcousticRequests() = runTest {
        for (firstAvailable in listOf(false, true)) {
            for (title in listOf("Only Love Can Save Me Now", "Death by Rock and Roll")) {
                val wanted = track("$title - Acoustic", "The Pretty Reckless")
                val audio = Provider(listOf(track(title, "The Pretty Reckless", "studio")))
                val combine = combine(settings(firstAvailable), "metadata" to Provider(), "audio" to audio)
                assertTrue("Studio recording matched acoustic request: $title", combine.loadTrack(wanted, false).streamables.isEmpty())
            }
        }
    }

    @Test
    fun issue9SelectsAcousticRecordingEvenWhenStudioAppearsFirst() = runTest {
        val examples = listOf(
            "Only Love Can Save Me Now" to "Only Love Can Save Me Now - Acoustic",
            "Death by Rock and Roll" to "Death by Rock and Roll – Acoustic,"
        )
        for (firstAvailable in listOf(false, true)) {
            for ((baseTitle, metadataTitle) in examples) {
                for (audioTitle in listOf("$baseTitle (Acoustic)", "$baseTitle – Acoustic", "$baseTitle - Acoustic (feat. Guest)")) {
                    val metadata = Provider()
                    val audio = Provider(listOf(
                        track(baseTitle, "The Pretty Reckless", "studio"),
                        track("$baseTitle (Live)", "The Pretty Reckless", "live"),
                        track(audioTitle, "The Pretty Reckless", "acoustic")
                    ))
                    val combine = combine(settings(firstAvailable), "metadata" to metadata, "audio" to audio)
                    val loaded = combine.loadTrack(track(metadataTitle, "The Pretty Reckless"), false)
                    assertEquals(metadataTitle, "acoustic", loaded.servers.single().id)
                    val media = combine.loadStreamableMedia(loaded.servers.single(), false) as Streamable.Media.Server
                    assertEquals("https://test/acoustic", (media.sources.single() as Streamable.Source.Http).request.url)
                    assertEquals(0, metadata.streamLoads)
                }
            }
        }
    }

    @Test
    fun issue9FallbackNeverSubstitutesStudioWhenAcousticResolutionFails() = runTest {
        for (firstAvailable in listOf(false, true)) {
            for (title in listOf("Only Love Can Save Me Now", "Death by Rock and Roll")) {
                val wanted = track("$title - Acoustic", "The Pretty Reckless")
                val failure = IllegalStateException("Acoustic stream failed")
                val audio = Provider(listOf(wanted)).apply { mediaFailure = failure }
                val backup = Provider(listOf(track(title, "The Pretty Reckless", "studio")))
                val config = settings(firstAvailable).apply { putString("stream_extension_1", "backup") }
                val combine = combine(config, "metadata" to Provider(), "audio" to audio, "backup" to backup)
                val loaded = combine.loadTrack(wanted, false)
                assertEquals(listOf(wanted.id), loaded.servers.map { it.id })
                try {
                    combine.loadStreamableMedia(loaded.servers.single(), false)
                    fail("Fallback substituted studio recording: $title")
                } catch (error: IllegalStateException) {
                    assertSame(failure, error)
                }
                assertEquals(0, backup.streamLoads)
            }
        }
    }

    @Test
    fun titleMatchingRejectsSubstringsAndDifferentVersionsOrArtists() = runTest {
        val examples = listOf(
            track("Evil Deeds") to listOf(track("Evil"), track("Evil Deeds", id = "correct")),
            track("Only Love Can Save Me Now - Acoustic") to listOf(
                track("Only Love Can Save Me Now"), track("Only Love Can Save Me Now – Acoustic,", id = "correct")
            ),
            track("Desires (ft. Future)", "Drake") to listOf(track("Desires", "Other Artist"), track("Desires", "Drake", "correct")),
            track("Song", "John Smith") to listOf(track("Song", "John Doe"), track("Song", "John Smith", "correct")),
            track("Song") to listOf(track("Song - Eminem.m4a", "Other Artist"), track("Song", id = "correct")),
            track("Song") to listOf(track("Song (Live)"), track("Song (Remix)"), track("Song", id = "correct")),
            track("Song (Part II)") to listOf(track("Song"), track("Song (Part II)", id = "correct")),
            track("Song feat. Guest - Acoustic") to listOf(track("Song"), track("Song - Acoustic", id = "correct")),
            track("Song ft. Guest (Live)") to listOf(track("Song"), track("Song (Live)", id = "correct")),
            track("Evil Deeds") to listOf(track("Evil - Eminem.m4a"), track("Evil Deeds - Eminem.m4a", id = "correct"))
        )
        for ((wanted, candidates) in examples) {
            val combine = combine(settings(), "metadata" to Provider(), "audio" to Provider(candidates))
            assertEquals(wanted.title, "correct", combine.loadTrack(wanted, false).servers.single().id)
        }
    }

    @Test
    fun featuredArtistAndPunctuationDifferencesAreAccepted() = runTest {
        val wanted = track("Don't Stop (feat. Guest)", "Main Artist")
        val candidate = track("Don’t Stop", "MAIN ARTIST", "correct")
        val combine = combine(settings(), "metadata" to Provider(), "audio" to Provider(listOf(candidate)))
        assertEquals("correct", combine.loadTrack(wanted, false).servers.single().id)
    }

    @Test
    fun issue8MatchesDreamvilleWithCreditsWithoutLosingVersionsOrArtists() = runTest {
        // Public Spotify album 2n3quCZ0anEa46j2IveacI / Deezer album 102427652.
        val albumTitles = listOf(
            "Under The Sun (with J. Cole & Lute feat. DaBaby)" to "Under The Sun",
            "Down Bad (feat. JID, Bas, J. Cole, EARTHGANG & Young Nudy)" to "Down Bad",
            "LamboTruck (with Cozz feat. REASON & Childish Major)" to "LamboTruck",
            "Swivel (with EARTHGANG) - From The Album \"Mirrorland\"" to "Swivel (From The Album \"Mirrorland\")",
            "Oh Wow...Swerve (with J. Cole feat. Zoink Gang, KEY! & Maxo Kream)" to "Oh Wow...Swerve",
            "Don't Hit Me Right Now (with Bas & Cozz feat. Yung Baby Tate, Guapdad 4000 & Buddy)" to "Don't Hit Me Right Now",
            "Wells Fargo (with JID & EARTHGANG feat. Buddy & Guapdad 4000) - Interlude" to "Wells Fargo (Interlude)",
            "Sleep Deprived (with Lute & Omen feat. Mez & DaVionne)" to "Sleep Deprived",
            "Self Love (with Ari Lennox & Bas feat. Baby Rose)" to "Self Love",
            "Ladies, Ladies, Ladies (with JID feat. T.I.)" to "Ladies, Ladies, Ladies",
            "Costa Rica (with Bas & JID feat. Guapdad 4000, Reese LAFLARE, Jace, Mez, Smokepurpp, Buddy & Ski Mask The Slump God)" to "Costa Rica",
            "1993 (with J. Cole, JID, Cozz & EARTHGANG feat. Smino & Buddy)" to "1993",
            "Rembrandt...Run It Back (with JID & J. Cole feat. Vince Staples)" to "Rembrandt...Run It Back",
            "Sunset (with J. Cole feat. Young Nudy)" to "Sunset",
            "Got Me (with Ari Lennox & Omen feat. Ty Dolla \$ign & Dreezy)" to "Got Me",
            "MIDDLE CHILD" to "MIDDLE CHILD",
            "PTSD (with Omen feat. Mereba, Deante' Hitchcock & St. Beauty)" to "PTSD",
            "Sacrifices (with EARTHGANG & J. Cole feat. Smino & Saba)" to "Sacrifices"
        )
        val versionTitles = listOf(
            "Song (with Guest) - Acoustic" to "Song (Acoustic)",
            "Song (Live) (with Guest)" to "Song - Live",
            "Song [WITH Guest] (Remix)" to "Song - Remix",
            "Song (with Guest) - Interlude" to "Song (Interlude)",
            "With A Little Help From My Friends" to "With A Little Help From My Friends",
            "Love With You" to "Love With You"
        )
        val titles = albumTitles + versionTitles
        for (firstAvailable in listOf(false, true)) {
            for ((wantedTitle, audioTitle) in titles + titles.map { (a, b) -> b to a }) {
                val artist = if (wantedTitle == "MIDDLE CHILD") "J. Cole" else "Dreamville"
                val wanted = track(wantedTitle, artist)
                val audio = Provider(listOf(
                    track(audioTitle, "Other Artist", "wrong-artist"),
                    track("Song", artist, "studio"),
                    track("Song (Instrumental)", artist, "different-version"),
                    track(audioTitle, artist, "correct")
                ))
                val metadata = Provider()
                val combine = combine(settings(firstAvailable), "metadata" to metadata, "audio" to audio)
                val loaded = combine.loadTrack(wanted, false)
                assertEquals(wantedTitle, "correct", loaded.servers.singleOrNull()?.id)
                assertFalse("Unclean search: ${audio.lastQuery}", audio.lastQuery!!.contains("(with ", true))
                assertFalse("Unclean search: ${audio.lastQuery}", audio.lastQuery!!.contains("[with ", true))
                val media = combine.loadStreamableMedia(loaded.servers.single(), false) as Streamable.Media.Server
                assertEquals("https://test/correct", (media.sources.single() as Streamable.Source.Http).request.url)
                assertEquals(0, metadata.streamLoads)
            }
        }
    }

    @Test
    fun metadataStreamsAreNeverReturnedEvenWhenAudioFails() = runTest {
        for (firstAvailable in listOf(false, true)) {
            val metadata = Provider()
            val audio = Provider()
            val combine = combine(settings(firstAvailable), "metadata" to metadata, "audio" to audio)
            val result = combine.loadTrack(track(), false)
            assertTrue(result.streamables.isEmpty())
            assertEquals(0, metadata.streamLoads)
        }
    }
}
