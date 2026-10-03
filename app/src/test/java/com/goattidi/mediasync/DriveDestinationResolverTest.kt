package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.repo.DriveLayout
import com.goattidi.mediasync.data.repo.SyncSettings
import com.goattidi.mediasync.sync.DriveDestinationResolver
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriveDestinationResolverTest {

    private lateinit var settings: SyncSettings
    private lateinit var server: MockWebServer
    private lateinit var resolver: DriveDestinationResolver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SyncSettings(context)
        server = MockWebServer()
        server.start()
        val client = DriveClient(FakeDriveAuth(), OkHttpClient(), server.url("/").toString())
        resolver = DriveDestinationResolver(settings, client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun record(filePath: String) = SyncRecord(
        mediaStoreId = 1L,
        localUri = "content://media/external/images/media/1",
        filePath = filePath,
        fileName = filePath.substringAfterLast('/'),
        sizeBytes = 100,
        mimeType = "image/jpeg",
        mediaType = MediaType.IMAGE,
        dateTaken = 0,
        dateModified = 0,
        localMd5 = null, md5SizeBytes = null, md5DateModified = null,
        driveFileId = null, driveMd5 = null, uploadedAt = null,
        status = SyncStatus.QUEUED, failureReason = null, resumeSessionUri = null
    )

    private fun folderFound(id: String) =
        MockResponse().setResponseCode(200).setBody("""{"files":[{"id":"$id","isAppAuthorized":true}]}""")

    private fun folderMissing() =
        MockResponse().setResponseCode(200).setBody("""{"files":[]}""")

    private fun folderCreated(id: String) =
        MockResponse().setResponseCode(200).setBody("""{"id":"$id"}""")

    @Test
    fun `FLAT layout returns the root folder itself`() = runTest {
        settings.setFolderName("Root Flat") // unique name → no cached id
        settings.setLayout(DriveLayout.FLAT)
        server.enqueue(folderMissing())
        server.enqueue(folderCreated("root-flat"))

        val id = resolver.resolveDestinationId(record("/dcim/Camera/a.jpg"))

        assertEquals("root-flat", id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `MIRROR_LOCAL creates one subfolder per source directory and caches it`() = runTest {
        settings.setFolderName("Root Mirror")
        settings.setLayout(DriveLayout.MIRROR_LOCAL)
        server.enqueue(folderFound("root-1"))      // root exists
        server.enqueue(folderMissing())            // "Camera" child missing
        server.enqueue(folderCreated("child-camera"))

        val first = resolver.resolveDestinationId(record("/dcim/Camera/a.jpg"))
        assertEquals("child-camera", first)
        assertEquals(3, server.requestCount)

        // Same source folder again: fully served from cache, zero requests
        val second = resolver.resolveDestinationId(record("/dcim/Camera/b.jpg"))
        assertEquals("child-camera", second)
        assertEquals(3, server.requestCount)

        // Different source folder: only the child lookup hits the network
        server.enqueue(folderFound("child-whatsapp"))
        val third = resolver.resolveDestinationId(record("/WhatsApp/Media/WhatsApp Video/c.mp4"))
        assertEquals("child-whatsapp", third)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `cached root deleted on Drive is dropped and re-created`() = runTest {
        settings.setFolderName("Root Deleted")
        settings.setLayout(DriveLayout.FLAT)
        settings.setCachedFolderId("gone")
        server.enqueue(MockResponse().setResponseCode(404))  // GET cached id
        server.enqueue(folderMissing())
        server.enqueue(folderCreated("root-new"))

        val id = resolver.resolveDestinationId(record("/dcim/Camera/a.jpg"))

        assertEquals("root-new", id)
        assertEquals("root-new", settings.cachedFolderId())
        assertTrue(server.takeRequest().path!!.startsWith("/drive/v3/files/gone"))
    }

    @Test
    fun `cached root in the trash is replaced`() = runTest {
        settings.setFolderName("Root Trashed")
        settings.setLayout(DriveLayout.FLAT)
        settings.setCachedFolderId("in-trash")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"in-trash","mimeType":"application/vnd.google-apps.folder","trashed":true}"""
            )
        )
        server.enqueue(folderMissing())
        server.enqueue(folderCreated("root-fresh"))

        assertEquals("root-fresh", resolver.resolveDestinationId(record("/dcim/Camera/a.jpg")))
        assertEquals("root-fresh", settings.cachedFolderId())
    }

    @Test
    fun `live cached root is confirmed once per process, then served from cache`() = runTest {
        settings.setFolderName("Root Live")
        settings.setLayout(DriveLayout.FLAT)
        settings.setCachedFolderId("root-live")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"root-live","mimeType":"application/vnd.google-apps.folder","trashed":false}"""
            )
        )

        assertEquals("root-live", resolver.resolveDestinationId(record("/dcim/Camera/a.jpg")))
        assertEquals("root-live", resolver.resolveDestinationId(record("/dcim/Camera/b.jpg")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `BY_MONTH names the subfolder from the upload date`() = runTest {
        settings.setFolderName("Root Months")
        settings.setLayout(DriveLayout.BY_MONTH)
        resolver.clock = { 1_752_300_000_000L } // 2025-07-12 UTC
        server.enqueue(folderFound("root-2"))
        server.enqueue(folderMissing())
        server.enqueue(folderCreated("child-month"))

        val id = resolver.resolveDestinationId(record("/dcim/Camera/a.jpg"))

        assertEquals("child-month", id)
        server.takeRequest() // root lookup
        val childLookup = server.takeRequest()
        assertTrue(childLookup.path!!.contains("2025-07"))
        val create = server.takeRequest()
        assertTrue(create.body.readUtf8().contains("2025-07"))
    }
}
