/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cliffracertech.soundaura.library.LibraryState
import com.cliffracertech.soundaura.library.LibraryViewModel
import com.cliffracertech.soundaura.library.Playlist
import com.cliffracertech.soundaura.library.PlaylistDialog
import com.cliffracertech.soundaura.library.RemovablePlaylistTrack
import com.cliffracertech.soundaura.library.uri
import com.cliffracertech.soundaura.model.MessageHandler
import com.cliffracertech.soundaura.model.ModifyLibraryUseCase
import com.cliffracertech.soundaura.model.PlaybackState
import com.cliffracertech.soundaura.model.ReadLibraryUseCase
import com.cliffracertech.soundaura.model.SearchQueryState
import com.cliffracertech.soundaura.model.TestPermissionHandler
import com.cliffracertech.soundaura.model.TestPlaybackState
import com.cliffracertech.soundaura.model.UriPermissionHandler
import com.cliffracertech.soundaura.model.database.PlaylistDao
import com.cliffracertech.soundaura.model.database.Track
import com.cliffracertech.soundaura.settings.PrefKeys
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

typealias PlaylistSort = com.cliffracertech.soundaura.model.database.Playlist.Sort

@RunWith(AndroidJUnit4::class)
class LibraryViewModelTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val testScopeRule = TestScopeRule()
    @get:Rule val dbTestRule = SoundAuraDbTestRule(context)
    @get:Rule val dataStoreTestRule = DataStoreTestRule(testScopeRule.scope)

    private val dao get() = dbTestRule.db.playlistDao()
    private val dataStore get() = dataStoreTestRule.dataStore
    private val playlistSortKey = intPreferencesKey(PrefKeys.playlistSort)
    private val showActivePlaylistsFirstKey = booleanPreferencesKey(PrefKeys.showActivePlaylistsFirst)

    private lateinit var permissionHandler: UriPermissionHandler
    private lateinit var messageHandler: MessageHandler
    private lateinit var playbackState: PlaybackState
    private lateinit var searchQueryState: SearchQueryState
    private lateinit var instance: LibraryViewModel

    @Before fun init() {
        permissionHandler = TestPermissionHandler()
        messageHandler = MessageHandler()
        playbackState = TestPlaybackState()
        searchQueryState = SearchQueryState()
        instance = LibraryViewModel(
            ReadLibraryUseCase(dataStore, searchQueryState, dao),
            ModifyLibraryUseCase(permissionHandler, messageHandler, dao),
            searchQueryState, messageHandler, playbackState)
    }

    private val testUris = List(4) { "uri $it".toUri() }
    private val testTracks = testUris.map(::Track)
    private val testPlaylistNames = List(5) { "playlist $it" }
    private val testPlaylists = List(5) {
        Playlist(
            id = it.toLong() + 1L,
            name = testPlaylistNames[it],
            isActive = false,
            volume = 1.0f,
            hasError = false,
            isSingleTrack = it != 4)
    }

    private val renameDialog get() = instance.shownDialog as PlaylistDialog.Rename
    private val fileChooser get() = instance.shownDialog as PlaylistDialog.FileChooser
    private val playlistOptionsDialog get() = instance.shownDialog as PlaylistDialog.PlaylistOptions
    private val removeDialog get() = instance.shownDialog as PlaylistDialog.Remove

    private val emptyState get() = instance.viewState as LibraryState.Empty
    private val contentState get() = instance.viewState as LibraryState.Content

    private suspend fun PlaylistDao.getPlaylistUris(id: Long) = getPlaylistTracks(id).map(Track::uri)

    private suspend fun insertTestPlaylists() {
        // Of the five names in testPlaylistNames, the first four will
        // be used for four single track playlists. The last playlist
        // name will be used for a multi-track playlist containing all
        // four uris in testUris.
        dao.insertSingleTrackPlaylists(
            names = testPlaylistNames.subList(0, testUris.size),
            uris = testUris,
            newUris = testUris)
        dao.insertPlaylist(
            playlistName = testPlaylistNames.last(),
            shuffle = true,
            tracks = testTracks,
            newUris = emptyList())
        waitUntil { dao.getPlaylistNames().size == 5 }
        waitUntil { instance.viewState != LibraryState.Loading }
    }

    @Test fun dialog_not_shown_initially() {
        assertThat(instance.shownDialog).isNull()
    }

    @Test fun viewState_starts_as_loading() {
        assertThat(instance.viewState).isInstanceOf(LibraryState.Loading::class)
    }

    @Test fun empty_library_state() = runTest {
        waitUntil { instance.viewState != LibraryState.Loading }
        assertThat(instance.viewState).isInstanceOf(LibraryState.Empty::class)
        assertThat(emptyState.message.stringResId).isEqualTo(R.string.empty_library_message)
    }

    @Test fun empty_search_results_state() = runTest {
        searchQueryState.set("query")
        waitUntil { instance.viewState is LibraryState.Empty }
        assertThat(instance.viewState).isInstanceOf(LibraryState.Empty::class)
        assertThat(emptyState.message.stringResId).isEqualTo(R.string.no_search_results_message)

        insertTestPlaylists()
        waitUntil { instance.viewState !is LibraryState.Empty } // should time out
        assertThat(instance.viewState).isInstanceOf(LibraryState.Empty::class)
        assertThat(emptyState.message.stringResId).isEqualTo(R.string.no_search_results_message)
    }

    @Test fun content_state_playlists_match_db() = runTest {
        insertTestPlaylists()
        assertThat(instance.viewState).isInstanceOf(LibraryState.Content::class)
        assertThat(contentState.playlists).containsExactlyElementsIn(testPlaylists).inOrder()

        dao.deletePlaylist(testPlaylists[1].id)
        waitUntil { (instance.viewState as LibraryState.Content).playlists?.size == 4 }
        assertThat(instance.viewState).isInstanceOf(LibraryState.Content::class)
        val expected = testPlaylists - testPlaylists[1]
        assertThat(contentState.playlists).containsExactlyElementsIn(expected).inOrder()
    }

    @Test fun playlists_property_reflects_sort_option() = runTest {
        insertTestPlaylists()
        dataStore.edit { it[playlistSortKey] = PlaylistSort.NameDesc.ordinal }
        waitUntil { contentState.playlists?.first() == testPlaylists.last() }
        assertThat(contentState.playlists).containsExactlyElementsIn(testPlaylists.reversed()).inOrder()

        dataStore.edit(playlistSortKey, PlaylistSort.OrderAdded.ordinal)
        waitUntil { contentState.playlists?.first() == testPlaylists.first() }
        assertThat(contentState.playlists).containsExactlyElementsIn(testPlaylists).inOrder()
    }

    @Test fun playlists_property_reflects_show_active_first_option() = runTest {
        insertTestPlaylists()
        dataStore.edit(showActivePlaylistsFirstKey, true)
        dao.toggleIsActive(testPlaylists[1].id)
        dao.toggleIsActive(testPlaylists[3].id)
        waitUntil { contentState.playlists?.first()?.id == testPlaylists[1].id }
        assertThat(contentState.playlists?.map(Playlist::name)).containsExactly(
            testPlaylistNames[1], testPlaylistNames[3], testPlaylistNames[0],
            testPlaylistNames[2], testPlaylistNames[4]).inOrder()

        dataStore.edit(showActivePlaylistsFirstKey, false)
        waitUntil { contentState.playlists?.first()?.id == testPlaylists[0].id }
        assertThat(contentState.playlists?.map(Playlist::name))
            .containsExactlyElementsIn(testPlaylistNames).inOrder()
    }

    @Test fun playlist_property_reflects_search_query() = runTest {
        insertTestPlaylists()
        searchQueryState.set("playlist ")
        waitUntil { instance.viewState != LibraryState.Loading }
        waitUntil { (contentState.playlists?.size ?: 0) < 5 } // should time out
        assertThat(instance.viewState).isInstanceOf(LibraryState.Content::class)
        assertThat(contentState.playlists).containsExactlyElementsIn(testPlaylists).inOrder()

        searchQueryState.set("2")
        waitUntil { (contentState.playlists?.size ?: 0) == 1 }
        assertThat(contentState.playlists).containsExactly(testPlaylists[2])

        searchQueryState.set("4")
        waitUntil { contentState.playlists?.contains(testPlaylists[4]) == true }
        assertThat(contentState.playlists).containsExactly(testPlaylists[4])

        searchQueryState.toggleIsActive()
        waitUntil { (contentState.playlists?.size ?: 0) == 5 }
        assertThat(contentState.playlists).containsExactlyElementsIn(testPlaylists).inOrder()
    }

    @Test fun track_add_remove_click() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onAddRemoveButtonClick(testPlaylists[3])
        waitUntil { contentState.playlists?.map(Playlist::isActive)?.contains(true) == true }
        assertThat(contentState.playlists?.map(Playlist::isActive))
            .containsExactly(false, false, false, true, false).inOrder()

        contentState.playlistViewCallback.onAddRemoveButtonClick(testPlaylists[1])
        contentState.playlistViewCallback.onAddRemoveButtonClick(testPlaylists[3])
        waitUntil { contentState.playlists?.filter(Playlist::isActive)?.size == 2 }
        assertThat(contentState.playlists?.map(Playlist::isActive))
            .containsExactly(false, true, false, false, false).inOrder()
    }

    @Test fun track_volume_slider_change() = runTest {
        insertTestPlaylists()
        assertThat(contentState.playlists?.map(Playlist::volume))
            .containsExactly(1f, 1f, 1f, 1f, 1f).inOrder()

        contentState.playlistViewCallback.onVolumeChangeFinished(testPlaylists[2], 0.5f)
        waitUntil { contentState.playlists?.map(Playlist::volume)?.contains(0.5f) == true }
        assertThat(contentState.playlists?.map(Playlist::volume))
            .containsExactly(1f, 1f, 0.5f, 1f, 1f).inOrder()

        contentState.playlistViewCallback.onVolumeChangeFinished(testPlaylists[2], 1f)
        contentState.playlistViewCallback.onVolumeChangeFinished(testPlaylists[1], 0.25f)
        contentState.playlistViewCallback.onVolumeChangeFinished(testPlaylists[4], 0.75f)
        waitUntil { contentState.playlists?.map(Playlist::volume)?.sum() == 4f }
        assertThat(contentState.playlists?.map(Playlist::volume))
            .containsExactly(1f, 0.25f, 1f, 1f, 0.75f).inOrder()
    }

    @Test fun rename_dialog_appearance() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onRenameClick(testPlaylists[1])
        assertThat(instance.shownDialog).isInstanceOf(PlaylistDialog.Rename::class)
        assertThat(renameDialog.target).isEqualTo(testPlaylists[1])
    }

    @Test fun rename_dialog_dismissal() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onRenameClick(testPlaylists[1])

        // Make changes that (hopefully) won't be saved
        renameDialog.onNameChange("new name")
        renameDialog.onDismissRequest()
        assertThat(instance.shownDialog).isNull()
        assertThat(dao.getPlaylistNames()).doesNotContain("new name")
    }

    @Test fun rename_dialog_confirmation() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onRenameClick(testPlaylists[1])
        renameDialog.onNameChange("new name")
        renameDialog.finish()

        waitUntil { dao.getPlaylistNames().contains("new name") }
        assertThat(instance.shownDialog).isNull()
        assertThat(dao.getPlaylistNames()).containsExactlyElementsIn(
            testPlaylistNames.toMutableList().also { it[1] = "new name" })
    }

    @Test fun single_track_playlist_extra_options_click_opens_file_chooser() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[1])
        waitUntil { instance.shownDialog != null }
        assertThat(instance.shownDialog).isInstanceOf(PlaylistDialog.FileChooser::class)
    }

    @Test fun single_track_playlist_file_chooser_dismissal() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[1])
        waitUntil { instance.shownDialog != null }
        fileChooser.onDismissRequest()

        assertThat(instance.shownDialog).isNull()
        val playlistUris = dao.getPlaylistUris(testPlaylists[1].id)
        assertThat(playlistUris).containsExactly(testUris[1])
    }
    
    @Test fun single_track_playlist_file_chooser_confirm_opens_playlist_options() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[1])
        waitUntil { instance.shownDialog != null }
        val newUris = List(2) { "new uri $it".toUri() }
        fileChooser.onFilesChosen(newUris)
        waitUntil { instance.shownDialog !is PlaylistDialog.FileChooser }

        assertThat(instance.shownDialog).isInstanceOf(PlaylistDialog.PlaylistOptions::class)
        assertThat(playlistOptionsDialog.target).isEqualTo(testPlaylists[1])
        val playlistUris = playlistOptionsDialog.mutablePlaylist.tracks.map { it.uri }
        val expectedUris = listOf(testUris[1]) + newUris
        assertThat(playlistUris).containsExactlyElementsIn(expectedUris).inOrder()
        assertThat(playlistOptionsDialog.shuffleEnabled).isFalse()
    }
    
    @Test fun single_track_playlist_options_dismissal() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[1])
        waitUntil { instance.shownDialog != null }
        val newUris = List(2) { "new uri $it".toUri() }
        fileChooser.onFilesChosen(newUris)
        playlistOptionsDialog.onDismissRequest()
        assertThat(instance.shownDialog).isNull()
    }

    @Test fun single_track_playlist_options_confirmation() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[1])
        waitUntil { instance.shownDialog != null }
        val newUris = List(2) { "new uri $it".toUri() }
        fileChooser.onFilesChosen(newUris)

        playlistOptionsDialog.mutablePlaylist.moveTrack(2, 0)
        playlistOptionsDialog.onShuffleSwitchClick()
        playlistOptionsDialog.onFinishClick()
        waitUntil { dao.getPlaylistShuffle(testPlaylists[1].id) }

        assertThat(instance.shownDialog).isNull()
        assertThat(dao.getPlaylistShuffle(testPlaylists[1].id)).isTrue()
        val expectedUris = listOf(newUris[1], testUris[1], newUris[0])
        val actualUris = dao.getPlaylistUris(testPlaylists[1].id)
        assertThat(actualUris).containsExactlyElementsIn(expectedUris).inOrder()
    }

    @Test fun multi_track_playlist_extra_options_click_opens_playlist_options() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[4])
        waitUntil { instance.shownDialog != null }

        assertThat(instance.shownDialog).isInstanceOf(PlaylistDialog.PlaylistOptions::class)
        assertThat(playlistOptionsDialog.target).isEqualTo(testPlaylists[4])
        assertThat(playlistOptionsDialog.shuffleEnabled).isTrue()
        val dialogUris = playlistOptionsDialog.mutablePlaylist.tracks.map(RemovablePlaylistTrack::uri)
        assertThat(dialogUris).containsExactlyElementsIn(testUris).inOrder()
    }

    @Test fun multi_track_playlist_options_dismissal() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[4])
        waitUntil { instance.shownDialog != null }

        // Make changes that (hopefully) won't be saved
        playlistOptionsDialog.onShuffleSwitchClick()
        playlistOptionsDialog.mutablePlaylist.moveTrack(3, 1)
        playlistOptionsDialog.mutablePlaylist.toggleTrackRemoval(3)

        playlistOptionsDialog.onDismissRequest()
        assertThat(instance.shownDialog).isNull()
        assertThat(dao.getPlaylistShuffle(testPlaylists[4].id)).isTrue()
        val actualUris = dao.getPlaylistUris(testPlaylists[4].id)
        assertThat(actualUris).containsExactlyElementsIn(testUris).inOrder()
    }

    @Test fun multi_track_playlist_options_confirmation() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[4])
        waitUntil { instance.shownDialog != null }

        playlistOptionsDialog.onShuffleSwitchClick()
        playlistOptionsDialog.mutablePlaylist.moveTrack(3, 1)
        playlistOptionsDialog.mutablePlaylist.toggleTrackRemoval(3)
        playlistOptionsDialog.onFinishClick()
        waitUntil { !dao.getPlaylistShuffle(testPlaylists[4].id) }

        assertThat(instance.shownDialog).isNull()
        assertThat(dao.getPlaylistShuffle(testPlaylists[4].id)).isFalse()
        val expectedUris = listOf(testUris[0], testUris[3], testUris[1])
        val actualUris = dao.getPlaylistUris(testPlaylists[4].id)
        assertThat(actualUris).containsExactlyElementsIn(expectedUris).inOrder()
    }

    @Test fun multi_track_playlist_options_add_button_opens_file_chooser() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[4])
        waitUntil { instance.shownDialog != null }

        playlistOptionsDialog.onAddFilesClick()
        assertThat(instance.shownDialog).isInstanceOf(PlaylistDialog.FileChooser::class)
    }

    @Test fun multi_track_playlist_file_chooser_dismissal_returns_to_playlist_options() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[4])
        waitUntil { instance.shownDialog != null }
        playlistOptionsDialog.onAddFilesClick()

        fileChooser.onDismissRequest()
        assertThat(instance.shownDialog).isInstanceOf(PlaylistDialog.PlaylistOptions::class)
        assertThat(playlistOptionsDialog.target).isEqualTo(testPlaylists[4])
        val actualUris = playlistOptionsDialog.mutablePlaylist.tracks.map(RemovablePlaylistTrack::uri)
        assertThat(actualUris).containsExactlyElementsIn(testUris).inOrder()
    }

    @Test fun multi_track_playlist_file_chooser_confirmation() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onExtraOptionsClick(testPlaylists[4])
        waitUntil { instance.shownDialog != null }
        playlistOptionsDialog.onAddFilesClick()

        val newUris = List(2) { "new uri $it".toUri() }
        fileChooser.onFilesChosen(newUris)

        assertThat(instance.shownDialog).isInstanceOf(PlaylistDialog.PlaylistOptions::class)
        assertThat(playlistOptionsDialog.target).isEqualTo(testPlaylists[4])
        val expectedUris = testUris + newUris
        var actualUris = playlistOptionsDialog.mutablePlaylist.tracks.map(RemovablePlaylistTrack::uri)
        assertThat(actualUris).containsExactlyElementsIn(expectedUris).inOrder()

        playlistOptionsDialog.onFinishClick()
        waitUntil { dao.getPlaylistTracks(testPlaylists[4].id).size > 4 }
        assertThat(instance.shownDialog).isNull()
        actualUris = dao.getPlaylistUris(testPlaylists[4].id)
        assertThat(actualUris).containsExactlyElementsIn(expectedUris).inOrder()
    }

    @Test fun remove_dialog_appearance() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onRemoveClick(testPlaylists[1])
        assertThat(instance.shownDialog).isInstanceOf(PlaylistDialog.Remove::class)
        assertThat(removeDialog.target).isEqualTo(testPlaylists[1])
    }
    
    @Test fun remove_dialog_dismissal() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onRemoveClick(testPlaylists[1])
        removeDialog.onDismissRequest()
        waitUntil { dao.getPlaylistNames().size < testPlaylists.size } // should time out
        assertThat(instance.shownDialog).isNull()
        assertThat(dao.getPlaylistNames()).contains(testPlaylistNames[1])
    }
    
    @Test fun remove_dialog_confirmation() = runTest {
        insertTestPlaylists()
        contentState.playlistViewCallback.onRemoveClick(testPlaylists[1])
        removeDialog.onConfirmClick()

        waitUntil { dao.getPlaylistNames().size < testPlaylists.size }
        assertThat(instance.shownDialog).isNull()
        assertThat(dao.getPlaylistNames())
            .containsExactlyElementsIn(testPlaylistNames - testPlaylistNames[1])
    }
}