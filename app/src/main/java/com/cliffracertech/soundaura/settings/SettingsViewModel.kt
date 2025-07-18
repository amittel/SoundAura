/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cliffracertech.soundaura.Dispatcher
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.collectAsState
import com.cliffracertech.soundaura.edit
import com.cliffracertech.soundaura.enumPreferenceState
import com.cliffracertech.soundaura.launchIO
import com.cliffracertech.soundaura.model.database.Playlist
import com.cliffracertech.soundaura.preferenceFlow
import com.cliffracertech.soundaura.preferenceState
import com.cliffracertech.soundaura.service.PlayerService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore by preferencesDataStore(name = "settings")

@Module @InstallIn(SingletonComponent::class)
class PreferencesModule {
    @Singleton @Provides
    fun provideDatastore(@ApplicationContext app: Context) = app.dataStore
}

object PrefKeys {
    /** An int value that indicates the version code of the app the last
     * time it was launched. */
    const val lastLaunchedVersionCode = "last_launched_version_code"

    /** A boolean value that indicates whether the list of tracks/playlists
     * should be sorted by their active states (with active tracks/playlists
     * appearing before inactive ones) before being sorted by another
     * sorting method. */
    const val showActivePlaylistsFirst = "show_active_tracks_first"

    /** An int value that represents the ordinal of the desired [Playlist.Sort]
     * enum value to use for sorting tracks/playlists in the main activity. */
    const val playlistSort = "track_sort"

    /** A [String] value that represents the name of the currently active preset. */
    const val activePresetName = "active_preset_name"

    /** An int value that represents the ordinal of the desired [AppTheme]
     * enum value to use as the application's light/dark theme. */
    const val appTheme = "app_theme"

    /**
     * A boolean value that indicates whether playback should occur in the
     * background. Expected behavior when playInBackground == false is:
     * - The app will respect normal audio focus behavior
     * - The app's playback will respond to hardware media keys even in the background
     * - The app's foreground service notification will appear as a media session
     *
     * Expected behavior when playInBackground == true is:
     * - The app will not request audio focus, and will consequently be allowed
     *   to play alongside other apps
     * - The app's playback will not respond to hardware media keys unless they
     *   are pressed while the app is in the foreground
     * - The app's foreground service notification will appear as a regular notification
     * - Playback will not automatically pause during phone calls unless the
     *   read phone state permission is granted and the preference autoPauseDuringCalls
     *   is true (due to the app having no other way to determine if a phone call
     *   is ongoing)
     */
    const val playInBackground = "play_in_background"

    /** A boolean value that indicates whether the user has been asked for notification
     * permission. Permission should only be asked once, to prevent annoying the user. */
    const val notificationPermissionRequested = "notification_permission_requested"

    /** A boolean value that indicates whether playback should automatically
     * pause when a phone call is ongoing. This setting will have no effect if
     * the playInBackground setting is false because the app will automatically
     * pause playback during calls due to losing audio focus. This setting also
     * has no effect if the app has not been granted the read phone state
     * permission, and should be prevented from being true in that case. */
    const val autoPauseDuringCalls = "auto_pause_during_calls"

    /** An int value that represents the ordinal of the desired [OnZeroVolumeAudioDeviceBehavior]
     * enum value to use as the application's response to an audio device change
     * leading to a media volume of zero. See OnZeroVolumeAudioDeviceBehavior's
     * documentation for descriptions of each value. */
    const val onZeroVolumeAudioDeviceBehavior = "on_zero_volume_audio_device_behavior"

    /** A boolean value that indicates whether tracks/playlists will
     * be stopped instead of paused when the pause button is clicked. */
    const val stopInsteadOfPause = "stop_instead_of_pause"

    /** A boolean value that indicates whether the user has been shown the long
     * click hint for the play/pause button. */
    const val playButtonLongClickHintShown = "play_button_long_click_hint_shown"
}

enum class AppTheme { UseSystem, Light, Dark;
    companion object {
        /** Return an Array<String> containing strings that describe the enum values. */
        @Composable fun valueStrings() =
            with(LocalContext.current) {
                remember { arrayOf(
                    getString(R.string.match_system_theme),
                    getString(R.string.light_theme),
                    getString(R.string.dark_theme)
                )}
            }
    }
}

/** An enum describing the behavior of the application when the current
 * audio device is changed to one with a media stream volume of zero. The
 * described behaviors will only be used when the zero media volume is the
 * result of a audio device change. If the zero media volume is a result
 * of the user manually changing it to zero on the current audio device,
 * playback will not be affected. */
enum class OnZeroVolumeAudioDeviceBehavior {
    /** [PlayerService] will be automatically stopped to conserve battery. */
    AutoStop,
    /** Playback will be automatically paused, and then resumed when another
     * audio device change brings the media volume back up above zero. */
    AutoPause,
    /** Playback will not be affected.*/
    DoNothing;

    companion object {
        /** Return an [Array] containing [String]s that describe the enum values. */
        @Composable fun valueStrings() =
            with(LocalContext.current) {
                remember { arrayOf(
                    getString(R.string.stop_playback_on_zero_volume_title),
                    getString(R.string.pause_playback_on_zero_volume_title),
                    getString(R.string.do_nothing_on_zero_volume_title)
                )}
            }

        /** Return an [Array] containing nullable strings that further describe the enum values if necessary. */
        @Composable fun valueDescriptions() =
            with(LocalContext.current) {
                remember { arrayOf(
                    getString(R.string.stop_playback_on_zero_volume_description),
                    getString(R.string.pause_playback_on_zero_volume_description),
                    getString(R.string.do_nothing_on_zero_volume_description)
                )}
            }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val dataStore: DataStore<Preferences>,
) : ViewModel() {
    private val scope = viewModelScope + Dispatcher.Immediate
    private val appThemeKey = intPreferencesKey(PrefKeys.appTheme)
    private val playInBackgroundKey = booleanPreferencesKey(PrefKeys.playInBackground)
    private val notificationPermissionRequestedKey =
        booleanPreferencesKey(PrefKeys.notificationPermissionRequested)
    private val autoPauseDuringCallKey = booleanPreferencesKey(PrefKeys.autoPauseDuringCalls)
    private val onZeroVolumeAudioDeviceBehaviorKey =
        intPreferencesKey(PrefKeys.onZeroVolumeAudioDeviceBehavior)
    private val stopInsteadOfPauseKey = booleanPreferencesKey(PrefKeys.stopInsteadOfPause)

    val appTheme by dataStore.enumPreferenceState<AppTheme>(appThemeKey, scope)

    fun onAppThemeClick(theme: AppTheme) =
        dataStore.edit(appThemeKey, theme.ordinal, scope)

    val playInBackground by dataStore
        .preferenceFlow(playInBackgroundKey, false)
        .collectAsState(false, scope)

    var showingPlayInBackgroundExplanation by mutableStateOf(false)
        private set

    // This value should always be up to date due to granting or revoking
    // permissions outside of the app causing an app restart. If the user
    // approves the notification permission inside the app, the value
    // must be changed to true manually.
    private var hasNotificationPermission by mutableStateOf(
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            true
        else ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED)

    private val notificationPermissionRequested by
        dataStore.preferenceState(notificationPermissionRequestedKey, false, scope)

    var showingNotificationPermissionDialog by mutableStateOf(false)
        private set

    fun onPlayInBackgroundTitleClick() {
        showingPlayInBackgroundExplanation = true
    }

    fun onPlayInBackgroundExplanationDismiss() {
        showingPlayInBackgroundExplanation = false
    }

    fun onNotificationPermissionDialogDismiss() {
        showingNotificationPermissionDialog = false
    }

    fun onNotificationPermissionDialogConfirm(permissionGranted: Boolean) {
        onNotificationPermissionDialogDismiss()
        if (permissionGranted)
            hasNotificationPermission = true
        togglePlayInBackground()
    }

    fun onPlayInBackgroundSwitchClick() {
        if (!playInBackground &&
            !notificationPermissionRequested &&
            !hasNotificationPermission
        ) {
            scope.launchIO {
                dataStore.edit(notificationPermissionRequestedKey, true)
            }
            showingNotificationPermissionDialog = true
        }
        else togglePlayInBackground()
    }

    private fun togglePlayInBackground() {
        scope.launch {
            dataStore.edit {
                val newValue = !playInBackground
                if (!newValue)
                    it[autoPauseDuringCallKey] = false
                it[playInBackgroundKey] = newValue
            }
        }
    }

    val autoPauseDuringCallSettingVisible by ::playInBackground

    // This value should always be up to date due to granting or revoking
    // permissions outside of the app causing an app restart. If the user
    // approves the read phone state permission inside the app, the value
    // must be changed to true manually.
    private var hasReadPhoneStatePermission by mutableStateOf(
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED)

    private val autoPauseDuringCallPreference by
        dataStore.preferenceState(
            key = autoPauseDuringCallKey,
            initialValue = false,
            scope = scope)

    val autoPauseDuringCall get() =
        autoPauseDuringCallPreference &&
        hasReadPhoneStatePermission &&
        autoPauseDuringCallSettingVisible

    var showingPhoneStatePermissionDialog by mutableStateOf(false)
        private set

    fun onAutoPauseDuringCallClick() {
        if (!playInBackground) return

        if (!autoPauseDuringCall && !hasReadPhoneStatePermission)
            showingPhoneStatePermissionDialog = true
        else dataStore.edit(autoPauseDuringCallKey, !autoPauseDuringCall, scope)
    }

    fun onPhoneStatePermissionDialogDismiss() {
        showingPhoneStatePermissionDialog = false
    }

    fun onPhoneStatePermissionDialogConfirm(permissionGranted: Boolean) {
        if (permissionGranted) {
            dataStore.edit(autoPauseDuringCallKey, true, scope)
            hasReadPhoneStatePermission = true
        }
        onPhoneStatePermissionDialogDismiss()
    }

    val onZeroVolumeAudioDeviceBehavior by
        dataStore.enumPreferenceState<OnZeroVolumeAudioDeviceBehavior>(
            onZeroVolumeAudioDeviceBehaviorKey, scope)

    fun onOnZeroVolumeAudioDeviceBehaviorClick(
        behavior: OnZeroVolumeAudioDeviceBehavior
    ) = dataStore.edit(onZeroVolumeAudioDeviceBehaviorKey, behavior.ordinal, scope)

    val stopInsteadOfPause by dataStore.preferenceState(stopInsteadOfPauseKey, false, scope)

    fun onStopInsteadOfPauseClick() =
        dataStore.edit(stopInsteadOfPauseKey, !stopInsteadOfPause, scope)
}