/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cliffracertech.soundaura.model.Validator
import com.cliffracertech.soundaura.model.database.newPlaylistNameValidator
import com.cliffracertech.soundaura.model.database.playlistRenameValidator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistNameValidatorTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val testScopeRule = TestScopeRule()
    @get:Rule val dbTestRule = SoundAuraDbTestRule(context)

    private val dao get() = dbTestRule.db.playlistDao()
    private lateinit var instance: Validator<String>

    private val existingName1 = "playlist 1"
    private val existingName2 = "playlist 2"
    private val newPlaylistName = "new playlist"

    @Before fun init() {
        runTest {
            val names = listOf(existingName1, existingName2)
            val uris = List(2) { "test uri $it".toUri() }
            dao.insertSingleTrackPlaylists(names, uris, uris)
        }
    }

    private fun initNewNameValidator() {
        instance = newPlaylistNameValidator(dao, testScopeRule.scope)
    }
    private fun initRenameValidator() {
        instance = playlistRenameValidator(dao, existingName1, testScopeRule.scope)
    }

    @Test fun new_name_validator_begins_blank_without_error() = runTest {
        initNewNameValidator()
        assertThat(instance.value).isEqualTo("")
        waitUntil { instance.message != null } // should time out
        assertThat(instance.message).isNull()
    }

    @Test fun new_name_validator_shows_error_for_existing_name() = runTest {
        initNewNameValidator()
        instance.value = existingName1
        waitUntil { instance.message != null }
        assertThat(instance.message).isInstanceOf(Validator.Message.Error::class)
        assertThat(instance.message?.stringResource?.stringResId)
            .isEqualTo(R.string.name_dialog_duplicate_name_error_message)
    }

    @Test fun new_name_validator_shows_error_for_blank_name_after_change() = runTest {
        initNewNameValidator()
        instance.value = newPlaylistName
        instance.value = ""
        waitUntil { instance.message != null }
        assertThat(instance.message).isInstanceOf(Validator.Message.Error::class)
        assertThat(instance.message?.stringResource?.stringResId)
            .isEqualTo(R.string.name_dialog_blank_name_error_message)
    }

    @Test fun new_name_validator_fails_and_shows_error_for_blank_name() = runTest {
        initNewNameValidator()
        val result = instance.validate()
        waitUntil { instance.message != null }
        assertThat(result).isNull()
        assertThat(instance.message).isInstanceOf(Validator.Message.Error::class)
        assertThat(instance.message?.stringResource?.stringResId)
            .isEqualTo(R.string.name_dialog_blank_name_error_message)
    }

    @Test fun new_name_validator_fails_for_existing_name() = runTest {
        initNewNameValidator()
        instance.value = existingName1
        val result = instance.validate()
        waitUntil {
            instance.message?.stringResource?.stringResId != R.string.name_dialog_duplicate_name_error_message
        } // should time out
        assertThat(result).isNull()
        assertThat(instance.message).isInstanceOf(Validator.Message.Error::class)
        assertThat(instance.message?.stringResource?.stringResId)
            .isEqualTo(R.string.name_dialog_duplicate_name_error_message)
    }

    @Test fun new_name_validator_success() = runTest {
        initNewNameValidator()
        instance.value = newPlaylistName
        waitUntil { instance.message != null } // should time out
        val result = instance.validate()
        assertThat(result).isEqualTo(newPlaylistName)
    }

    @Test fun rename_validator_begins_with_existing_name_without_error() = runTest {
        initRenameValidator()
        assertThat(instance.value).isEqualTo(existingName1)
        waitUntil { instance.message != null } // should time out
        assertThat(instance.message).isNull()
    }

    @Test fun rename_validator_shows_error_for_existing_name() = runTest {
        initRenameValidator()
        instance.value = existingName2
        waitUntil { instance.message != null }
        assertThat(instance.message).isInstanceOf(Validator.Message.Error::class)
        assertThat(instance.message?.stringResource?.stringResId)
            .isEqualTo(R.string.name_dialog_duplicate_name_error_message)
    }

    @Test fun rename_validator_fails_for_blank_name() = runTest {
        initRenameValidator()
        instance.value = ""
        waitUntil { instance.message != null }
        assertThat(instance.message).isInstanceOf(Validator.Message.Error::class)
        assertThat(instance.message?.stringResource?.stringResId)
            .isEqualTo(R.string.name_dialog_blank_name_error_message)
        assertThat(instance.validate()).isNull()
    }

    @Test fun rename_validator_success() = runTest {
        initRenameValidator()
        instance.value = newPlaylistName
        waitUntil { instance.message != null } // should time out
        assertThat(instance.validate()).isEqualTo(newPlaylistName)
    }
}