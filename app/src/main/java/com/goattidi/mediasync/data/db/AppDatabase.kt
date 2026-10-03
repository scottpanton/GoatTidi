package com.goattidi.mediasync.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = MediaType.valueOf(value)
}

@Database(
    entities = [SyncRecord::class, UploadedContent::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncRecordDao(): SyncRecordDao
    abstract fun uploadedContentDao(): UploadedContentDao

    companion object {
        /** v2: adds the upload ledger (content dedup). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `uploaded_content` (" +
                        "`md5` TEXT NOT NULL, " +
                        "`driveFileId` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`uploadedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`md5`))"
                )
            }
        }

        /** v3: media duration for the gallery's video overlay. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_records ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4: live upload progress + rate for the queue screen. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_records ADD COLUMN bytesUploaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_records ADD COLUMN uploadRateBps INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5: records which backend issued each remote object id, so the same row can hold
         * a Drive file id and an S3 key without either being read against the wrong service.
         *
         * Existing rows are left null, which reads as Drive — the only backend that
         * existed — so no synced record is invalidated by the upgrade.
         *
         * The ledger's key becomes (backendId, md5) so both backends can hold an entry for
         * the same bytes. Changing a primary key means rebuilding the table, which is also
         * the opportunity to hand it the id column the other one already uses.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_records ADD COLUMN backendId TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `uploaded_content_new` (" +
                        "`backendId` TEXT NOT NULL, " +
                        "`md5` TEXT NOT NULL, " +
                        "`driveFileId` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`uploadedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`backendId`, `md5`))"
                )
                db.execSQL(
                    "INSERT INTO `uploaded_content_new` " +
                        "(`backendId`, `md5`, `driveFileId`, `fileName`, `sizeBytes`, `uploadedAt`) " +
                        "SELECT 'DRIVE', `md5`, `driveFileId`, `fileName`, `sizeBytes`, `uploadedAt` " +
                        "FROM `uploaded_content`"
                )
                db.execSQL("DROP TABLE `uploaded_content`")
                db.execSQL("ALTER TABLE `uploaded_content_new` RENAME TO `uploaded_content`")
            }
        }
    }
}
