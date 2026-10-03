package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
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
import com.goattidi.mediasync.sync.SyncWorker
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: SyncStateRepository
    private lateinit var server: MockWebServer
    private lateinit var uploader: DriveUploader
    private lateinit var backends: StorageBackendProvider
    private lateinit var files: FakeLocalFiles

    private val content = "hello world".toByteArray() // md5 5eb63bbbe01eeed093cb22bb8f5acdc3
    private val contentMd5 = "5eb63bbbe01eeed093cb22bb8f5acdc3"
    private val statA = LocalFiles.Stat(sizeBytes = 11, dateModified = 1_700_000_000L)

    class FakeLocalFiles(var content: ByteArray, defaultStat: LocalFiles.Stat?) : LocalFiles {
        /** Stats served in order; the last one repeats forever. */
        val statSequence = ArrayDeque<LocalFiles.Stat?>()
        private var last: LocalFiles.Stat? = defaultStat

        override fun stat(record: SyncRecord): LocalFiles.Stat? {
            if (statSequence.isNotEmpty()) last = statSequence.removeFirst()
            return last
        }

        override fun open(record: SyncRecord, offset: Long): InputStream =
            ByteArrayInputStream(content, offset.toInt(), content.size - offset.toInt())
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SyncStateRepository(db.syncRecordDao(), db.uploadedContentDao(), MediaStoreScanner(context.contentResolver))
        server = MockWebServer()
        server.start()
        val client = DriveClient(FakeDriveAuth(), OkHttpClient(), server.url("/").toString())
        uploader = DriveUploader(client, RetryPolicy(maxAttempts = 3, sleeper = {}), DriveUploader.CHUNK_GRANULARITY)
        // The worker only ever sees the abstraction, so this test also covers the Drive
        // adapter end to end: every mocked Drive response has to survive translation.
        backends = StorageBackendProvider { DriveStorageBackend(client, uploader) }
        files = FakeLocalFiles(content, statA)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun record(
        id: Long = 1L,
        status: SyncStatus = SyncStatus.QUEUED,
        sessionUri: String? = null,
        driveFileId: String? = null,
        md5: String? = null
    ) = SyncRecord(
        mediaStoreId = id,
        localUri = "content://media/external/video/media/$id",
        filePath = "/dcim/Camera/clip$id.mp4",
        fileName = "clip$id.mp4",
        sizeBytes = statA.sizeBytes,
        mimeType = "video/mp4",
        mediaType = MediaType.VIDEO,
        dateTaken = statA.dateModified * 1000,
        dateModified = statA.dateModified,
        localMd5 = md5,
        md5SizeBytes = if (md5 != null) statA.sizeBytes else null,
        md5DateModified = if (md5 != null) statA.dateModified else null,
        driveFileId = driveFileId,
        driveMd5 = null,
        uploadedAt = null,
        status = status,
        failureReason = null,
        resumeSessionUri = sessionUri
    )

    private fun buildWorker(): SyncWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = SyncWorker(appContext, workerParameters, repo, backends, files) { _ -> null }
        }
        return TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(factory).build() as SyncWorker
    }

    private fun sessionStart(path: String = "/upload/sess") =
        MockResponse().setResponseCode(200).setHeader("Location", server.url(path).toString())

    private fun done(md5: String = contentMd5, id: String = "drv-1") =
        MockResponse().setResponseCode(200).setBody("""{"id":"$id","md5Checksum":"$md5"}""")

    @Test
    fun `queued file uploads and is marked SYNCED with verified md5`() = runTest {
        db.syncRecordDao().upsert(record())
        server.enqueue(sessionStart())
        server.enqueue(done())

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val rec = db.syncRecordDao().getById(1L)!!
        assertEquals(SyncStatus.SYNCED, rec.status)
        assertEquals("drv-1", rec.driveFileId)
        assertEquals(contentMd5, rec.driveMd5)
        assertNotNull(rec.uploadedAt)
        assertNull(rec.resumeSessionUri)
        assertEquals(contentMd5, rec.localMd5) // md5 was computed and cached
    }

    @Test
    fun `stale UPLOADING record resumes from persisted session via offset probe`() = runTest {
        // Simulates process death mid-upload: DB says UPLOADING with a live session URI
        val sessionUri = server.url("/upload/resume-sess").toString()
        db.syncRecordDao().upsert(record(status = SyncStatus.UPLOADING, sessionUri = sessionUri, md5 = contentMd5))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-5"))
        server.enqueue(done())

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val probe = server.takeRequest()
        assertEquals("PUT", probe.method)
        assertEquals("bytes */11", probe.getHeader("Content-Range")) // probed, not re-pushed
        val put = server.takeRequest()
        assertEquals("bytes 6-10/11", put.getHeader("Content-Range")) // resumed at confirmed offset
        assertEquals("world", put.body.readUtf8())
        assertEquals(SyncStatus.SYNCED, db.syncRecordDao().getById(1L)!!.status)
    }

    @Test
    fun `md5 mismatch is FAILED and never SYNCED`() = runTest {
        db.syncRecordDao().upsert(record())
        server.enqueue(sessionStart())
        server.enqueue(done(md5 = "ffffffffffffffffffffffffffffffff"))

        buildWorker().doWork()

        val rec = db.syncRecordDao().getById(1L)!!
        assertEquals(SyncStatus.FAILED, rec.status)
        assertTrue(rec.failureReason!!.contains("mismatch"))
        assertNull(rec.uploadedAt)
    }

    @Test
    fun `missing local file is FAILED without any network calls`() = runTest {
        db.syncRecordDao().upsert(record())
        files.statSequence.add(null)

        buildWorker().doWork()

        val rec = db.syncRecordDao().getById(1L)!!
        assertEquals(SyncStatus.FAILED, rec.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `file modified during upload is re-queued with md5 discarded`() = runTest {
        db.syncRecordDao().upsert(record())
        // stat calls: initial, post-md5-compute, post-upload re-check (changed!)
        files.statSequence.add(statA)
        files.statSequence.add(statA)
        files.statSequence.add(LocalFiles.Stat(sizeBytes = 11, dateModified = 1_700_000_999L))
        server.enqueue(sessionStart())
        server.enqueue(done())

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val rec = db.syncRecordDao().getById(1L)!!
        assertEquals(SyncStatus.QUEUED, rec.status)
        assertNull(rec.localMd5)          // invariant #2: hash no longer describes the bytes
        assertNull(rec.resumeSessionUri)
        assertEquals(SyncStatus.QUEUED, rec.status)
    }

    @Test
    fun `storage quota keeps item QUEUED and aborts the run`() = runTest {
        db.syncRecordDao().upsert(record())
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"errors":[{"reason":"storageQuotaExceeded"}]}}"""
            )
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val rec = db.syncRecordDao().getById(1L)!!
        assertEquals(SyncStatus.QUEUED, rec.status)
        assertTrue(rec.failureReason!!.contains("storage"))
    }

    @Test
    fun `upload without checksum stays UPLOADING and verifies on next run`() = runTest {
        db.syncRecordDao().upsert(record())
        server.enqueue(sessionStart())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"drv-9"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"drv-9"}""")) // files.get: still no md5

        val first = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), first)
        var rec = db.syncRecordDao().getById(1L)!!
        assertEquals(SyncStatus.UPLOADING, rec.status) // not SYNCED on 2xx alone
        assertEquals("drv-9", rec.driveFileId)
        assertNull(rec.resumeSessionUri)

        // Next run: only files.get, checksum now available → SYNCED
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"drv-9","md5Checksum":"$contentMd5"}"""))
        val second = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), second)
        rec = db.syncRecordDao().getById(1L)!!
        assertEquals(SyncStatus.SYNCED, rec.status)

        // The second run must be verify-only: a single GET, no re-upload
        val methods = buildList { repeat(server.requestCount) { add(server.takeRequest().method!!) } }
        assertEquals(listOf("POST", "PUT", "GET", "GET"), methods)
    }

    @Test
    fun `moved file with identical bytes is adopted from ledger without re-upload`() = runTest {
        // The original record was dropped when the file moved; the new MediaStore row
        // is NOT_UPLOADED→QUEUED, but the ledger remembers these exact bytes on Drive.
        db.syncRecordDao().upsert(record(id = 7, md5 = contentMd5))
        db.uploadedContentDao().upsert(
            com.goattidi.mediasync.data.db.UploadedContent("DRIVE", contentMd5, "drv-earlier", "old-name.mp4", 11, 123L)
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-earlier","md5Checksum":"$contentMd5"}""")
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val rec = db.syncRecordDao().getById(7L)!!
        assertEquals(SyncStatus.SYNCED, rec.status)
        assertEquals("drv-earlier", rec.driveFileId) // re-linked, not re-uploaded
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method) // single files.get, no upload
    }

    @Test
    fun `stale ledger entry pointing at deleted Drive file falls back to real upload`() = runTest {
        db.syncRecordDao().upsert(record(id = 8, md5 = contentMd5))
        db.uploadedContentDao().upsert(
            com.goattidi.mediasync.data.db.UploadedContent("DRIVE", contentMd5, "drv-gone", "old.mp4", 11, 123L)
        )
        server.enqueue(MockResponse().setResponseCode(404)) // ledger check: file deleted on Drive
        server.enqueue(sessionStart())
        server.enqueue(done(id = "drv-new"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val rec = db.syncRecordDao().getById(8L)!!
        assertEquals(SyncStatus.SYNCED, rec.status)
        assertEquals("drv-new", rec.driveFileId)
        // ledger now points at the fresh upload
        assertEquals("drv-new", db.uploadedContentDao().getByChecksum("DRIVE", contentMd5)!!.driveFileId)
    }

    @Test
    fun `enqueue only transitions eligible statuses`() = runTest {
        db.syncRecordDao().upsert(record(id = 1, status = SyncStatus.NOT_UPLOADED))
        db.syncRecordDao().upsert(record(id = 2, status = SyncStatus.SYNCED))
        db.syncRecordDao().upsert(record(id = 3, status = SyncStatus.FAILED))

        val changed = repo.enqueue(listOf(1, 2, 3))

        assertEquals(2, changed)
        assertEquals(SyncStatus.QUEUED, db.syncRecordDao().getById(1)!!.status)
        assertEquals(SyncStatus.SYNCED, db.syncRecordDao().getById(2)!!.status) // untouched
        assertEquals(SyncStatus.QUEUED, db.syncRecordDao().getById(3)!!.status)
    }
}
