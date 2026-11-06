package com.yadown.di

import android.content.Context
import androidx.room.Room
import com.yadown.core.db.YadownDatabase
import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.dao.PeerIndexDao
import com.yadown.core.db.dao.SourceHealthDao
import com.yadown.core.db.dao.TransferEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): YadownDatabase =
        Room.databaseBuilder(context, YadownDatabase::class.java, "yadown.db")
            .addMigrations(YadownDatabase.MIGRATION_1_2, YadownDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun provideIntentDao(db: YadownDatabase): IntentDao = db.intentDao()

    @Provides
    fun provideChunkDao(db: YadownDatabase): ChunkDao = db.chunkDao()

    @Provides
    fun provideTransferEventDao(db: YadownDatabase): TransferEventDao = db.transferEventDao()

    @Provides
    fun provideSourceHealthDao(db: YadownDatabase): SourceHealthDao = db.sourceHealthDao()

    @Provides
    fun providePeerIndexDao(db: YadownDatabase): PeerIndexDao = db.peerIndexDao()
}
