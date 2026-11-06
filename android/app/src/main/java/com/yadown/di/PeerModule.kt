package com.yadown.di

import android.content.Context
import com.yadown.core.db.dao.PeerIndexDao
import com.yadown.core.peer.NsdPeerDiscovery
import com.yadown.core.peer.PeerDiscovery
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PeerModule {

    @Provides
    @Singleton
    fun providePeerDiscovery(
        @ApplicationContext context: Context,
        dao: PeerIndexDao
    ): PeerDiscovery = NsdPeerDiscovery(context, dao)
}
