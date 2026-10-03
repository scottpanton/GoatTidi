package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.goattidi.mediasync.data.db.AppDatabase
import com.goattidi.mediasync.data.db.UploadedContent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MIGRATION_4_5 is the one migration that touches data an existing user already depends on,
 * so it runs against a real v4 file rather than being trusted.
 *
 * Room performs the migration and validates the resulting schema against the entities when
 * it opens the file: a migration that dropped a column, mistyped a primary key, or forgot a
 * table would fail here rather than in the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var openDatabase: AppDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        openDatabase?.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun `upgrading from v4 keeps synced records and reads them as Drive`() = runTest {
        seedV4()
        val db = openAsV5()

        db.openHelper.writableDatabase
            .query("SELECT backendId, driveFileId, driveMd5, status FROM sync_records").use { c ->
                c.moveToFirst()
                // Null reads as Drive, so an existing install keeps its synced state as it
                // was instead of every file being treated as never uploaded.
                assertNull("backendId should stay null so pre-S3 rows read as Drive", c.getString(0))
                assertEquals("DRIVEFILEID", c.getString(1))
                assertEquals(MD5, c.getString(2))
                assertEquals("SYNCED", c.getString(3))
            }
        assertEquals(1, db.syncRecordDao().getAll().size)
        assertEquals("DRIVEFILEID", db.uploadedContentDao().getByChecksum("DRIVE", MD5)!!.driveFileId)
    }

    @Test
    fun `the rebuilt ledger lets both backends hold the same checksum`() = runTest {
        seedV4()
        val db = openAsV5()

        // The same bytes legitimately exist in both backends once a user switches between
        // them. Under the old single-column key one entry would have overwritten the other.
        db.uploadedContentDao().upsert(UploadedContent("DRIVE", MD5, "D", "a.jpg", 1, 1))
        db.uploadedContentDao().upsert(UploadedContent("S3", MD5, "KEY", "a.jpg", 1, 2))

        assertEquals("D", db.uploadedContentDao().getByChecksum("DRIVE", MD5)!!.driveFileId)
        assertEquals("KEY", db.uploadedContentDao().getByChecksum("S3", MD5)!!.driveFileId)
    }

    private fun openAsV5(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
        .addMigrations(AppDatabase.MIGRATION_4_5)
        .allowMainThreadQueries()
        .build()
        .also { openDatabase = it }

    /** A v4 file with one synced record and one ledger entry, written by hand. */
    private fun seedV4() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sync_records` (" +
                            "`mediaStoreId` INTEGER NOT NULL, `localUri` TEXT NOT NULL, " +
                            "`filePath` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                            "`sizeBytes` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, " +
                            "`mediaType` TEXT NOT NULL, `dateTaken` INTEGER NOT NULL, " +
                            "`dateModified` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL DEFAULT 0, " +
                            "`localMd5` TEXT, `md5SizeBytes` INTEGER, `md5DateModified` INTEGER, " +
                            "`driveFileId` TEXT, `driveMd5` TEXT, `uploadedAt` INTEGER, " +
                            "`status` TEXT NOT NULL, `failureReason` TEXT, `resumeSessionUri` TEXT, " +
                            "`bytesUploaded` INTEGER NOT NULL DEFAULT 0, " +
                            "`uploadRateBps` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`mediaStoreId`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `uploaded_content` (" +
                            "`md5` TEXT NOT NULL, `driveFileId` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                            "`sizeBytes` INTEGER NOT NULL, `uploadedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`md5`))"
                    )
                    db.execSQL(
                        "INSERT INTO sync_records (mediaStoreId, localUri, filePath, fileName, sizeBytes, " +
                            "mimeType, mediaType, dateTaken, dateModified, durationMs, localMd5, " +
                            "md5SizeBytes, md5DateModified, driveFileId, driveMd5, uploadedAt, status, " +
                            "failureReason, resumeSessionUri, bytesUploaded, uploadRateBps) VALUES " +
                            "(1, 'content://media/1', '/dcim/clip1.mp4', 'clip1.mp4', 11, 'video/mp4', " +
                            "'VIDEO', 0, 1700000000, 0, '$MD5', 11, 1700000000, 'DRIVEFILEID', '$MD5', " +
                            "1700000100000, 'SYNCED', NULL, NULL, 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO uploaded_content (md5, driveFileId, fileName, sizeBytes, uploadedAt) " +
                            "VALUES ('$MD5', 'DRIVEFILEID', 'clip1.mp4', 11, 1700000100000)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val MD5 = "5eb63bbbe01eeed093cb22bb8f5acdc3"
    }
}