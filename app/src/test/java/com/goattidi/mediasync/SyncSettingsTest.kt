package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goattidi.mediasync.data.repo.S3Settings
import com.goattidi.mediasync.data.repo.SyncSettings
import com.goattidi.mediasync.data.storage.BackendId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncSettingsTest {

    @Test
    fun `fresh install uses the device-derived default`() {
        assertEquals("GoatTidi_Pixel", SyncSettings.effectiveFolderName(null, hasCachedFolder = false, "GoatTidi_Pixel"))
    }

    @Test
    fun `install that resolved a folder under the old default keeps Phone Media`() {
        // Otherwise Settings would show the new default while uploads kept going to the old folder
        assertEquals("Phone Media", SyncSettings.effectiveFolderName(null, hasCachedFolder = true, "GoatTidi_Pixel"))
    }

    @Test
    fun `a saved name always wins`() {
        assertEquals("Trips", SyncSettings.effectiveFolderName("Trips", hasCachedFolder = true, "GoatTidi_Pixel"))
    }

    @Test
    fun `default folder name is GoatTidi_ plus a device name`() {
        val name = SyncSettings(ApplicationProvider.getApplicationContext<Context>()).defaultFolderName
        assertTrue(name, name.startsWith("GoatTidi_") && name.length > "GoatTidi_".length)
        assertFalse(name, name.contains('/'))
    }

    @Test
    fun `an install that never chose a backend is Drive`() = runTest {
        // The setting is new, so every existing install has no value stored. Reading Drive
        // is what keeps the upgrade from moving anyone's uploads.
        assertEquals(BackendId.DRIVE, settings.backendId.first())
    }

    @Test
    fun `an unrecognised stored backend falls back to Drive rather than failing`() {
        assertEquals(BackendId.DRIVE, BackendId.parse("SOMETHING_ELSE"))
        assertEquals(BackendId.DRIVE, BackendId.parse(null))
        assertEquals(BackendId.S3, BackendId.parse("S3"))
    }

    @Test
    fun `s3 settings are incomplete until endpoint bucket region and key are all present`() {
        val blank = S3Settings()
        assertFalse(blank.isComplete)
        assertFalse(blank.hasCredentials)

        val noKey = blank.copy(endpoint = "https://s3.example", region = "us-east-1", bucket = "b")
        assertFalse("credentials are required", noKey.isComplete)

        val noBucket = blank.copy(endpoint = "https://s3.example", region = "us-east-1", accessKeyId = "a", secretAccessKey = "s")
        assertFalse("a bucket is required", noBucket.isComplete)

        val noScheme = blank.copy(endpoint = "s3.example", region = "us-east-1", bucket = "b", accessKeyId = "a", secretAccessKey = "s")
        assertFalse("an endpoint without a scheme cannot be called", noScheme.isComplete)

        val complete = blank.copy(endpoint = "https://s3.example", region = "us-east-1", bucket = "b", accessKeyId = "a", secretAccessKey = "s")
        assertTrue(complete.isComplete)
        assertTrue(complete.hasCredentials)
    }

    private val settings = SyncSettings(ApplicationProvider.getApplicationContext<Context>())
}
