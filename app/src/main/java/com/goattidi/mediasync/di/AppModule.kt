package com.goattidi.mediasync.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import com.goattidi.mediasync.data.db.AppDatabase
import com.goattidi.mediasync.data.db.SyncRecordDao
import com.goattidi.mediasync.data.db.UploadedContentDao
import com.goattidi.mediasync.data.drive.DriveAuthProvider
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveUploader
import com.goattidi.mediasync.data.storage.DriveStorageBackend
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "goattidi.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5
            )
            .build()

    @Provides
    fun provideSyncRecordDao(db: AppDatabase): SyncRecordDao = db.syncRecordDao()

    @Provides
    fun provideUploadedContentDao(db: AppDatabase): UploadedContentDao = db.uploadedContentDao()

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideDriveClient(auth: DriveAuthProvider, http: OkHttpClient): DriveClient =
        DriveClient(auth, http)

    @Provides
    @Singleton
    fun provideDriveUploader(client: DriveClient): DriveUploader = DriveUploader(client)

    @Provides
    @Singleton
    fun provideDriveStorageBackend(
        client: DriveClient,
        uploader: DriveUploader
    ): DriveStorageBackend = DriveStorageBackend(client, uploader)
}
