/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.BufferPreset
import app.opentv.data.backup.BackupPreferences
import app.opentv.data.backup.BackupSource
import app.opentv.data.backup.SettingsBackup
import app.opentv.data.backup.SettingsBackupRepository
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.SourceRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings file: one device's configuration, written out and read back in.
 *
 * Three things have to be right, and they pull in opposite directions:
 *
 *  - **The providers travel with their credentials.** That is the whole point of the file —
 *    setting up a second box should not mean typing a password on a television remote.
 *  - **The things that belong to one install do not travel.** The TV-or-phone mode is the
 *    shape of the device; the parental PIN guards this screen; the USB folder is a permission
 *    grant the other device does not have.
 *  - **Importing never destroys what is already here.** A provider added by hand survives; a
 *    favourite set on this box survives; a preference this device chose survives a file that
 *    never mentions it.
 *
 * The last one is the subtle one, and it is why a preference still sitting at the app's
 * default does not travel: the file saying "no NAS" usually means the device that wrote it
 * never had one, not that the NAS should be removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SettingsBackupTest {

    private lateinit var db: OpenTvDatabase
    private lateinit var repository: SettingsBackupRepository
    private lateinit var settings: AppSettings

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun stored(): SharedPreferences =
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenTvDatabase::class.java,
        ).allowMainThreadQueries().build()

        settings = AppSettings.get(context)
        // The AppSettings singleton outlives a test method, so every value a test depends on
        // is put back explicitly rather than assumed to be the default.
        stored().edit().clear().commit()
        settings.setThemeMode(AppSettings.ThemeMode.SYSTEM)
        settings.setChannelLayout(AppSettings.ChannelLayout.GRID)
        settings.setBufferPreset(BufferPreset.DEFAULT)
        settings.setSmbConfig("", "", "", "", "")
        settings.setTmdbApiKey("")

        val http = OkHttpClient.Builder().build()
        val sourceRepository = SourceRepository(db.sources(), XtreamApi(http), StalkerApi(http))
        val catalogRepository = CatalogRepository(
            context = context,
            sourceDao = db.sources(),
            channelDao = db.channels(),
            categoryDao = db.categories(),
            movieDao = db.movies(),
            seriesDao = db.series(),
            episodeDao = db.episodes(),
            positionDao = db.positions(),
            api = XtreamApi(http),
            stalkerApi = StalkerApi(http),
            http = http,
            settings = settings,
        )
        repository = SettingsBackupRepository(sourceRepository, catalogRepository, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun provider(
        name: String = "Lab",
        url: String = "http://lab:8080",
        username: String? = "user",
        password: String? = "secret",
    ) = Source(
        name = name,
        kind = SourceKind.XTREAM,
        url = url,
        username = username,
        password = password,
    )

    @Test
    fun `the file carries the providers and their credentials`() = runBlocking<Unit> {
        db.sources().insert(provider())

        val backup = repository.gather("0.12.4")
        val text = repository.encode(backup)

        // The credentials are the point of the file: without them there is nothing to carry
        // to the second box but a list of addresses to retype.
        assertThat(text).contains("\"username\":\"user\"")
        assertThat(text).contains("\"password\":\"secret\"")
        assertThat(text).contains("http://lab:8080")
        assertThat(backup.sources).hasSize(1)
        assertThat(backup.sources.first().username).isEqualTo("user")
    }

    @Test
    fun `the file does not carry what belongs to one device`() = runBlocking<Unit> {
        settings.setAppMode(AppSettings.AppMode.PHONE)
        settings.setPin("1234")
        settings.setUsbTree("content://com.android.externalstorage/tree/primary%3AUSB", "USB")

        val text = repository.encode(repository.gather("0.12.4"))

        // The mode is the shape of the device, the PIN guards this screen, and the USB folder
        // is a permission grant the other device has to ask for itself.
        assertThat(text).doesNotContain("app_mode")
        assertThat(text).doesNotContain("parental_pin_hash")
        assertThat(text).doesNotContain("usb_tree_uri")
        assertThat(text).doesNotContain("sync_device_id")
        assertThat(text).doesNotContain("1234")
    }

    @Test
    fun `a file written by a newer build still opens`() {
        val text = """
            {"version":99,"exportedAtMillis":1,"appVersion":"9.9.9","somethingNew":{"a":1},
             "sources":[{"kind":"XTREAM","name":"Lab","url":"http://lab:8080"}],
             "preferences":{"themeMode":"DARK","aBrandNewPreference":true}}
        """.trimIndent()

        val backup = repository.decode(text)

        assertThat(backup.version).isEqualTo(99)
        assertThat(backup.sources).hasSize(1)
        assertThat(backup.preferences.themeMode).isEqualTo("DARK")
    }

    @Test
    fun `a file that is not ours is refused rather than half-read`() {
        val outcome = runCatching { repository.decode("this is not a settings file") }

        assertThat(outcome.isFailure).isTrue()
    }

    @Test
    fun `importing adds a provider and updates the one already here`() = runBlocking<Unit> {
        db.sources().insert(provider(username = "old", password = "old"))

        val file = SettingsBackup(
            sources = listOf(
                BackupSource(kind = "XTREAM", name = "Lab", url = "http://lab:8080", username = "new", password = "new"),
                BackupSource(kind = "M3U", name = "Playlist", url = "http://lab/list.m3u"),
            ),
        )

        val report = repository.apply(file)

        assertThat(report.sourcesUpdated).isEqualTo(1)
        assertThat(report.sourcesAdded).isEqualTo(1)
        val sources = db.sources().all()
        assertThat(sources).hasSize(2)
        val xtream = sources.first { it.kind == SourceKind.XTREAM }
        assertThat(xtream.username).isEqualTo("new")
        assertThat(xtream.password).isEqualTo("new")
    }

    @Test
    fun `importing never deletes a provider`() = runBlocking<Unit> {
        db.sources().insert(provider(name = "Only here"))
        db.sources().insert(provider(name = "Also here", url = "http://other:8080"))

        repository.apply(SettingsBackup(sources = listOf(BackupSource(kind = "XTREAM", name = "New", url = "http://new:8080"))))

        // The file is a merge, not a replacement: the provider the user added by hand is not
        // collateral damage of restoring somebody else's setup.
        assertThat(db.sources().all().map { it.name }).containsExactly(
            "Only here",
            "Also here",
            "New",
        )
    }

    @Test
    fun `importing merges favourites without removing the local ones`() = runBlocking<Unit> {
        db.sources().insert(provider())
        db.channels().upsertAll(
            listOf(
                channel(streamId = "one", streamUrl = "http://lab/one"),
                channel(streamId = "two", streamUrl = "http://lab/two"),
            ),
        )
        val local = db.channels().allForMatching().first { it.streamUrl == "http://lab/one" }
        db.channels().setFavourite(local.id, true)

        repository.apply(
            SettingsBackup(
                curation = app.opentv.data.backup.BackupCuration(
                    favouriteChannels = listOf("http://lab/two"),
                ),
            ),
        )

        val favourites = db.channels().allForMatching().filter { it.favourite }.map { it.streamUrl }
        assertThat(favourites).containsExactly("http://lab/one", "http://lab/two")
    }

    @Test
    fun `a preference still at the default does not overwrite this device's own`() = runBlocking<Unit> {
        settings.setThemeMode(AppSettings.ThemeMode.DARK)

        // The file was written by a device that never touched the theme, so it says SYSTEM —
        // which is the default, not a choice, and must not undo what this device chose.
        repository.apply(SettingsBackup(preferences = BackupPreferences(themeMode = "SYSTEM")))

        assertThat(settings.themeMode.value).isEqualTo(AppSettings.ThemeMode.DARK)
    }

    @Test
    fun `a preference the file did change is restored`() = runBlocking<Unit> {
        settings.setThemeMode(AppSettings.ThemeMode.DARK)

        repository.apply(SettingsBackup(preferences = BackupPreferences(themeMode = "LIGHT")))

        assertThat(settings.themeMode.value).isEqualTo(AppSettings.ThemeMode.LIGHT)
    }

    @Test
    fun `a file with no NAS does not wipe the NAS this device has`() = runBlocking<Unit> {
        settings.setSmbConfig("192.168.1.10", "video", "Recordings", "user", "pass")

        // The file was written by a phone that never had a NAS. "No NAS" is not the same as
        // "remove the NAS", and treating it as one would cost the user their recordings.
        repository.apply(SettingsBackup(preferences = BackupPreferences()))

        assertThat(settings.smbHost.value).isEqualTo("192.168.1.10")
        assertThat(settings.smbShare.value).isEqualTo("video")
        assertThat(settings.smbPassword.value).isEqualTo("pass")
    }

    @Test
    fun `a NAS in the file does reach the device`() = runBlocking<Unit> {
        repository.apply(
            SettingsBackup(
                preferences = BackupPreferences(
                    smbHost = "192.168.1.20",
                    smbShare = "films",
                    smbFolder = "TV",
                    smbUser = "u",
                    smbPassword = "p",
                ),
            ),
        )

        assertThat(settings.smbHost.value).isEqualTo("192.168.1.20")
        assertThat(settings.smbShare.value).isEqualTo("films")
        assertThat(settings.smbFolder.value).isEqualTo("TV")
    }

    @Test
    fun `a provider the file cannot describe is skipped, not dropped silently`() = runBlocking<Unit> {
        val report = repository.apply(
            SettingsBackup(
                sources = listOf(
                    BackupSource(kind = "XTREAM", name = "No address", url = ""),
                    BackupSource(kind = "A_KIND_FROM_THE_FUTURE", name = "Alien", url = "http://alien"),
                ),
            ),
        )

        assertThat(report.sourcesSkipped).isEqualTo(2)
        assertThat(report.sourcesAdded).isEqualTo(0)
        assertThat(db.sources().all()).isEmpty()
    }

    @Test
    fun `the same address written differently is still the same provider`() = runBlocking<Unit> {
        db.sources().insert(provider(url = "http://lab:8080"))

        val report = repository.apply(
            SettingsBackup(
                sources = listOf(
                    BackupSource(kind = "XTREAM", name = "Lab", url = "http://lab:8080/"),
                ),
            ),
        )

        // A trailing slash is the same provider, not a second one. Two boxes would then show
        // the same channels twice and fight over the guide.
        assertThat(report.sourcesUpdated).isEqualTo(1)
        assertThat(report.sourcesAdded).isEqualTo(0)
        assertThat(db.sources().all()).hasSize(1)
    }

    private fun channel(streamId: String, streamUrl: String) = Channel(
        sourceId = 1L,
        streamId = streamId,
        name = "Channel $streamId",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = streamUrl,
    )
}
