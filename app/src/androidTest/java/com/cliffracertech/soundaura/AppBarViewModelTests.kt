/* This file is part of SoundAura, which is released under
   the terms of the Apache License 2.0. See license.md in
   the project's root directory to see the full license. */
package com.cliffracertech.soundaura

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cliffracertech.soundaura.appbar.AppBarViewModel
import com.cliffracertech.soundaura.appbar.SearchQueryViewState
import com.cliffracertech.soundaura.model.NavigationState
import com.cliffracertech.soundaura.model.SearchQueryState
import com.cliffracertech.soundaura.model.database.Playlist
import com.cliffracertech.soundaura.settings.PrefKeys
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppBarViewModelTests {
    @get:Rule val testScopeRule = TestScopeRule()
    @get:Rule val dataStoreTestRule = DataStoreTestRule(testScopeRule.scope)

    private val dataStore get() = dataStoreTestRule.dataStore
    private val showActivePlaylistsFirstKey =
        booleanPreferencesKey(PrefKeys.showActivePlaylistsFirst)
    private val playlistSortKey = intPreferencesKey(PrefKeys.playlistSort)

    private lateinit var navigationState: NavigationState
    private lateinit var searchQueryState: SearchQueryState
    private lateinit var instance: AppBarViewModel

    @Before fun init() {
        navigationState = NavigationState()
        searchQueryState = SearchQueryState()
        instance = AppBarViewModel(dataStore, navigationState, searchQueryState)
    }

    @Test fun initial_state() {
        assertThat(instance.onBackButtonClick).isNull()
        assertThat(instance.title.stringResId).isEqualTo(R.string.app_name)
        assertThat(instance.searchQueryViewState.query).isNull()
        assertThat(instance.searchQueryViewState.icon)
            .isEqualTo(SearchQueryViewState.Icon.Search)
        assertThat(instance.sortMenuState.currentOptionIndex)
            .isEqualTo(Playlist.Sort.entries.first().ordinal)
        assertThat(instance.sortMenuState.showingPopup).isFalse()
        assertThat(instance.showActivePlaylistsFirstSwitchState.checked).isFalse()
    }

    @Test fun entering_and_exiting_app_settings() {
        instance.onSettingsButtonClick()
        assertThat(navigationState.showingAppSettings).isTrue()
        assertThat(instance.onBackButtonClick).isNotNull()
        instance.onSettingsButtonClick()
        assertThat(navigationState.showingAppSettings).isTrue()

        instance.onBackButtonClick?.invoke()
        assertThat(navigationState.showingAppSettings).isFalse()
        assertThat(instance.onBackButtonClick).isNull()
    }

    @Test fun title_changes_when_showing_app_settings() {
        assertThat(instance.title.stringResId).isEqualTo(R.string.app_name)
        navigationState.showAppSettings()
        assertThat(instance.title.stringResId).isEqualTo(R.string.app_settings_description)
        navigationState.hideAppSettings()
        assertThat(instance.title.stringResId).isEqualTo(R.string.app_name)
    }

    @Test fun search_query_reflects_underlying_state() = runTest {
        val testQuery = "test query"
        searchQueryState.set(testQuery)
        assertThat(instance.searchQueryViewState.query).isEqualTo(testQuery)

        searchQueryState.toggleIsActive()
        assertThat(instance.searchQueryViewState.query).isNull()
    }

    @Test fun search_button_clicks_toggle_active_search_query() = runTest {
        instance.searchQueryViewState.onButtonClick()
        assertThat(instance.searchQueryViewState.query).isEqualTo("")
        instance.searchQueryViewState.onButtonClick()
        assertThat(instance.searchQueryViewState.query).isNull()

        val testQuery = "test query"
        searchQueryState.set(testQuery)
        instance.searchQueryViewState.onButtonClick()
    }

    @Test fun settings_button_clears_search_query() {
        val testQuery = "test query"
        searchQueryState.set(testQuery)
        instance.onSettingsButtonClick()
        assertThat(instance.searchQueryViewState.query).isNull()
        instance.onBackButtonClick?.invoke()
        assertThat(instance.searchQueryViewState.query).isNull()
    }

    @Test fun show_active_tracks_first_reflects_underlying_state() = runTest{
        dataStore.edit(showActivePlaylistsFirstKey, true)
        assertThat(instance.showActivePlaylistsFirstSwitchState.checked).isTrue()

        dataStore.edit(showActivePlaylistsFirstKey, false)
        assertThat(instance.showActivePlaylistsFirstSwitchState.checked).isFalse()
    }

    @Test fun show_active_tracks_first_switch_clicks_toggle_value() = runTest {
        instance.showActivePlaylistsFirstSwitchState.onClick()
        assertThat(instance.showActivePlaylistsFirstSwitchState.checked).isTrue()
        instance.showActivePlaylistsFirstSwitchState.onClick()
        assertThat(instance.showActivePlaylistsFirstSwitchState.checked).isFalse()
    }

    @Test fun current_playlistSort_reflects_underlying_state() = runTest {
        dataStore.edit(playlistSortKey, Playlist.Sort.NameDesc.ordinal)
        assertThat(instance.sortMenuState.currentOptionIndex)
            .isEqualTo(Playlist.Sort.NameDesc.ordinal)

        dataStore.edit(playlistSortKey, Playlist.Sort.NameAsc.ordinal)
        assertThat(instance.sortMenuState.currentOptionIndex)
            .isEqualTo(Playlist.Sort.NameAsc.ordinal)
    }

    @Test fun on_playlistSort_option_click() = runTest {
        instance.sortMenuState.onOptionClick(Playlist.Sort.NameDesc.ordinal)
        assertThat(instance.sortMenuState.currentOptionIndex)
            .isEqualTo(Playlist.Sort.NameDesc.ordinal)
        instance.sortMenuState.onOptionClick(Playlist.Sort.NameAsc.ordinal)
        assertThat(instance.sortMenuState.currentOptionIndex)
            .isEqualTo(Playlist.Sort.NameAsc.ordinal)
    }
}