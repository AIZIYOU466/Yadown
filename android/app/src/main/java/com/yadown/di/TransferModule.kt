package com.yadown.di

import android.content.Context
import com.yadown.core.io.FileStore
import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.OkHttpRangeSource
import com.yadown.core.net.RangeSource
import com.yadown.core.transfer.BitmapStore
import com.yadown.core.transfer.DownloadEngine
import com.yadown.core.transfer.FileBitmapStore
import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object TransferModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpRangeSource.createClient()

    @Provides
    @Singleton
    fun provideRangeSource(client: OkHttpClient): RangeSource = OkHttpRangeSource(client)

    @Provides
    @Singleton
    fun provideFileStore(): FileStore = RandomAccessFileStore()

    @Provides
    @Singleton
    fun provideBitmapStore(@ApplicationContext context: Context): BitmapStore =
        FileBitmapStore(File(context.filesDir, "bitmaps"))

    @Provides
    @Singleton
    fun provideDownloadEngine(
        source: RangeSource,
        files: FileStore,
        bitmaps: BitmapStore,
        intentDao: IntentDao,
        chunkDao: ChunkDao
    ): DownloadEngine = DownloadEngine(source, files, bitmaps, intentDao, chunkDao)
}
