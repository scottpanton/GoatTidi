package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goattidi.mediasync.data.db.AppDatabase
import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveUploader
import com.goattidi.mediasync.data.drive.RetryPolicy
import com.goattidi.mediasync.data.storage.DriveStorageBackend
import com.goattidi.mediasync.data.storage.StorageBackendProvider
import com.goattidi.mediasync.data.hash.Md5
import com.goattidi.mediasync.data.media.MediaStoreScanner
import com.goattidi.mediasync.data.repo.SyncStateRepository
import com.goattidi.mediasync.sync.LocalFiles
import com.goattidi.mediasync.sync.ReclaimEngine
import com.goattidi.mediasync.sync.VerifyEngine
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
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VerifyAndReclaimTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SyncStateRepository
    private lateinit var server: MockWebServer
    private lateinit var client: DriveClient

    /**
     * Drives the engines through the Drive adapter, which is how this file doubles as the
     * regression test for the adapter: every mock Drive response below now has to survive
     * being translated into a StorageBackend call.
     */
    private fun driveBackends() = StorageBackendProvider { DriveStorageBackend(client, DriveUploader(client)) }

    private val content = "hello world".toByteArray()
    private val contentMd5 = "5eb63bbbe01eeed093cb22bb8f5acdc3"
    private val stat = LocalFiles.Stat(11, 1_700_000_000L)
    private val policy = RetryPolicy(maxAttempts = 2, sleeper = {})

    private class StaticLocalFiles(
        private val content: ByteArray?,
        private val stat: LocalFiles.Stat?
    ) : LocalFiles {
        override fun stat(record: SyncRecord) = if (content == null) null else stat
        override fun open(record: SyncRecord, offset: Long): InputStream =
            ByteArrayInputStream(content!!, offset.toInt(), content.size - offset.toInt())
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = SyncStateRepository(db.syncRecordDao(), db.uploadedContentDao(), MediaStoreScanner(context.contentResolver))
        server = MockWebServer()
        server.start()
        client = DriveClient(FakeDriveAuth(), OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private suspend fun seed(
        id: Long,
        status: SyncStatus,
        driveFileId: String? = "drv-$id",
        localMd5: String? = contentMd5,
        driveMd5: String? = contentMd5
    ): SyncRecord {
        val rec = SyncRecord(
            mediaStoreId = id,
            localUri = "content://media/external/video/media/$id",
            filePath = "/dcim/clip$id.mp4",
            fileName = "clip$id.mp4",
            sizeBytes = stat.sizeBytes,
            mimeType = "video/mp4",
            mediaType = MediaType.VIDEO,
            dateTaken = 0,
            dateModified = stat.dateModified,
            localMd5 = localMd5,
            md5SizeBytes = if (localMd5 != null) stat.sizeBytes else null,
            md5DateModified = if (localMd5 != null) stat.dateModified else null,
            driveFileId = driveFileId,
            driveMd5 = driveMd5,
            uploadedAt = 1_700_000_100_000L,
            status = status,
            failureReason = null,
            resumeSessionUri = null
        )
        db.syncRecordDao().upsert(rec)
        return rec
    }

    // ---- VerifyEngine ----

    @Test
    fun `verify flags orphan when Drive file is 404`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(MockResponse().setResponseCode(404))

        val summary = VerifyEngine(repo, driveBackends()).verifyAll(policy)

        assertEquals(1, summary.orphaned)
        assertEquals(SyncStatus.ORPHANED, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `verify flags orphan when Drive file is trashed`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"$contentMd5","trashed":true}""")
        )

        VerifyEngine(repo, driveBackends()).verifyAll(policy)

        assertEquals(SyncStatus.ORPHANED, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `verify promotes unverified upload once checksum matches`() = runTest {
        seed(1, SyncStatus.UPLOADING, driveMd5 = null)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"$contentMd5"}""")
        )

        val summary = VerifyEngine(repo, driveBackends()).verifyAll(policy)

        assertEquals(1, summary.confirmedSynced)
        val rec = db.syncRecordDao().getById(1)!!
        assertEquals(SyncStatus.SYNCED, rec.status)
        assertEquals(contentMd5, rec.driveMd5)
    }

    @Test
    fun `verify demotes SYNCED file whose checksums no longer match`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"ffffffffffffffffffffffffffffffff"}""")
        )

        val summary = VerifyEngine(repo, driveBackends()).verifyAll(policy)

        assertEquals(1, summary.modified)
        assertEquals(SyncStatus.MODIFIED_SINCE_UPLOAD, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `verify never promotes without a Drive checksum`() = runTest {
        seed(1, SyncStatus.UPLOADING, driveMd5 = null)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"drv-1"}"""))

        val summary = VerifyEngine(repo, driveBackends()).verifyAll(policy)

        assertEquals(1, summary.stillUnverified)
        assertEquals(SyncStatus.UPLOADING, db.syncRecordDao().getById(1)!!.status)
    }

    // ---- ReclaimEngine (invariant #3) ----

    @Test
    fun `delete allowed when fresh local md5 matches fresh Drive md5`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"$contentMd5"}""")
        )
        val engine = ReclaimEngine(repo, driveBackends(), StaticLocalFiles(content, stat))

        val gate = engine.confirmSafeToDelete(1, policy)

        assertEquals(ReclaimEngine.Gate.Safe, gate)
    }

    @Test
    fun `delete blocked when local bytes changed even though status says SYNCED`() = runTest {
        seed(1, SyncStatus.SYNCED) // DB still claims SYNCED with matching md5s
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"$contentMd5"}""")
        )
        // ...but the actual bytes on disk are different now
        val engine = ReclaimEngine(repo, driveBackends(), StaticLocalFiles("tampered!!".toByteArray(), stat))

        val gate = engine.confirmSafeToDelete(1, policy)

        assertTrue(gate is ReclaimEngine.Gate.Blocked)
        assertEquals(SyncStatus.MODIFIED_SINCE_UPLOAD, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `delete blocked when Drive copy is gone`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(MockResponse().setResponseCode(404))
        val engine = ReclaimEngine(repo, driveBackends(), StaticLocalFiles(content, stat))

        val gate = engine.confirmSafeToDelete(1, policy)

        assertTrue(gate is ReclaimEngine.Gate.Blocked)
        assertEquals(SyncStatus.ORPHANED, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `delete blocked for non-synced statuses without any network call`() = runTest {
        seed(1, SyncStatus.MODIFIED_SINCE_UPLOAD)
        val engine = ReclaimEngine(repo, driveBackends(), StaticLocalFiles(content, stat))

        val gate = engine.confirmSafeToDelete(1, policy)

        assertTrue(gate is ReclaimEngine.Gate.Blocked)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `candidates are synced files sorted biggest first`() = runTest {
        seed(1, SyncStatus.SYNCED)
        db.syncRecordDao().upsert(seed(2, SyncStatus.SYNCED).copy(sizeBytes = 999_999))
        seed(3, SyncStatus.NOT_UPLOADED)
        val engine = ReclaimEngine(repo, driveBackends(), StaticLocalFiles(content, stat))

        val candidates = engine.candidates()

        assertEquals(listOf(2L, 1L), candidates.map { it.mediaStoreId })
    }

    @Test
    fun `ledger import pages through all app files and stores checksums`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[
                    {"id":"f1","name":"a.mp4","md5Checksum":"aaaa","size":"100","isAppAuthorized":true},
                    {"id":"f2","name":"doc-no-checksum","isAppAuthorized":true}
                ],"nextPageToken":"page2"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"f3","name":"b.jpg","md5Checksum":"BBBB","size":"200","isAppAuthorized":true}]}"""
            )
        )

        val imported = VerifyEngine(repo, driveBackends()).importLedger(policy)

        assertEquals(2, imported) // the checksum-less file is skipped
        assertEquals("f1", db.uploadedContentDao().getByChecksum("DRIVE", "aaaa")!!.driveFileId)
        assertEquals("f3", db.uploadedContentDao().getByChecksum("DRIVE", "bbbb")!!.driveFileId) // case-normalized
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().path!!.contains("fields=nextPageToken"))
        assertTrue(server.takeRequest().path!!.contains("pageToken=page2"))
    }

    @Test
    fun `ledger import skips files the app didn't create when drive_readonly is granted`() = runTest {
        // With the opt-in read scope the listing spans the whole Drive; only the app's
        // own uploads belong in the ledger — never the user's other files
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[
                    {"id":"mine","name":"a.mp4","md5Checksum":"aaaa","size":"100","isAppAuthorized":true},
                    {"id":"theirs","name":"b.jpg","md5Checksum":"cccc","size":"200","isAppAuthorized":false}
                ]}"""
            )
        )

        val imported = VerifyEngine(repo, driveBackends()).importLedger(policy)

        assertEquals(1, imported)
        assertEquals("mine", db.uploadedContentDao().getByChecksum("DRIVE", "aaaa")!!.driveFileId)
        assertEquals(null, db.uploadedContentDao().getByChecksum("DRIVE", "cccc"))
    }

    @Test
    fun `folder sync scan indexes the tree and the app's own uploads in one pass`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"ext-root","trashed":false}"""))
        // Every folder in Drive, once: the chosen tree (ext-root > sub1 > deep), an unrelated
        // folder, and a parent loop that must not hang the membership check
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[
                    {"id":"ext-root","parents":["my-drive"]},
                    {"id":"sub1","parents":["ext-root"]},
                    {"id":"deep","parents":["sub1"]},
                    {"id":"elsewhere","parents":["my-drive"]},
                    {"id":"loopA","parents":["loopB"]},
                    {"id":"loopB","parents":["loopA"]}
                ]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[
                    {"id":"v1","ownedByMe":true,"name":"a.mp4","md5Checksum":"c1","size":"1","parents":["ext-root"]},
                    {"id":"v2","ownedByMe":true,"name":"b.mp4","md5Checksum":"c2","size":"1","parents":["deep"]},
                    {"id":"x1","ownedByMe":true,"name":"c.mp4","md5Checksum":"c3","size":"1","parents":["elsewhere"]},
                    {"id":"x2","ownedByMe":true,"name":"d.mp4","md5Checksum":"c6","size":"1","parents":["loopA"]},
                    {"id":"own1","name":"e.jpg","md5Checksum":"c4","size":"1","parents":["elsewhere"],"isAppAuthorized":true},
                    {"id":"doc","name":"notes","parents":["sub1"]},
                    {"id":"shared","name":"g.mp4","md5Checksum":"c7","size":"1","parents":["sub1"],"ownedByMe":false}
                ],"nextPageToken":"p2"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"v3","ownedByMe":true,"name":"f.mp4","md5Checksum":"c5","size":"1","parents":["sub1"]}]}"""
            )
        )

        val result = VerifyEngine(repo, driveBackends()).scanOwnUploadsAndContainer("ext-root", policy)

        assertEquals(VerifyEngine.SyncScanResult(own = 1, inContainer = 3), result)
        val ledger = db.uploadedContentDao()
        listOf("c1" to "v1", "c2" to "v2", "c5" to "v3", "c4" to "own1").forEach { (md5, id) ->
            assertEquals(id, ledger.getByChecksum("DRIVE", md5)!!.driveFileId)
        }
        assertEquals(null, ledger.getByChecksum("DRIVE", "c3")) // outside the tree and not the app's own
        assertEquals(null, ledger.getByChecksum("DRIVE", "c6")) // in a parent loop outside the tree
        assertEquals(null, ledger.getByChecksum("DRIVE", "c7")) // in the tree, but someone else's file
        assertEquals(4, server.requestCount) // folder check + one folder listing + two file pages
        server.takeRequest()
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("parents,isAppAuthorized,ownedByMe"))
    }

    @Test
    fun `folder sync scan of a deleted folder still rebuilds the own-upload ledger`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"own1","name":"e.jpg","md5Checksum":"c4","size":"1","isAppAuthorized":true}]}"""
            )
        )

        val result = VerifyEngine(repo, driveBackends()).scanOwnUploadsAndContainer("gone", policy)

        assertEquals(VerifyEngine.SyncScanResult(own = 1, inContainer = null), result)
        assertEquals("own1", db.uploadedContentDao().getByChecksum("DRIVE", "c4")!!.driveFileId)
    }

    @Test
    fun `entire-Drive sync search lists the user's own files flat instead of walking folders`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[
                    {"id":"d1","name":"a.jpg","md5Checksum":"1111","size":"10"},
                    {"id":"d2","name":"a-doc"}
                ],"nextPageToken":"p2"}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"d3","name":"b.mp4","md5Checksum":"2222","size":"20"}]}"""
            )
        )

        val imported = VerifyEngine(repo, driveBackends()).importEverything(policy)

        assertEquals(2, imported) // the checksum-less Doc is skipped
        assertEquals("d1", db.uploadedContentDao().getByChecksum("DRIVE", "1111")!!.driveFileId)
        assertEquals("d3", db.uploadedContentDao().getByChecksum("DRIVE", "2222")!!.driveFileId)
        assertEquals(2, server.requestCount) // no folder validation, no per-folder walk
        val first = java.net.URLDecoder.decode(server.takeRequest().path!!, "UTF-8")
        assertTrue(first.contains("'me' in owners")) // files merely shared with the user are excluded
        assertTrue(server.takeRequest().path!!.contains("pageToken=p2"))
    }

    @Test
    fun `ensureFolder returns existing folder id`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"files":[{"id":"folder-existing","name":"Phone Media","isAppAuthorized":true}]}""")
        )

        val id = client.ensureFolder("Phone Media")

        assertEquals("folder-existing", id)
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("q="))
    }

    @Test
    fun `ensureFolder never adopts a same-named folder the app didn't create`() = runTest {
        // Visible through drive.readonly, but drive.file can't upload into it
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"files":[{"id":"users-own","name":"Phone Media","isAppAuthorized":false}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"folder-new"}"""))

        val id = client.ensureFolder("Phone Media")

        assertEquals("folder-new", id)
        val lookup = server.takeRequest()
        assertTrue(lookup.path!!.contains("isAppAuthorized"))
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `ensureFolder creates folder when absent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"folder-new"}"""))

        val id = client.ensureFolder("Phone Media")

        assertEquals("folder-new", id)
        server.takeRequest() // list
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertTrue(create.body.readUtf8().contains("application/vnd.google-apps.folder"))
    }
}
