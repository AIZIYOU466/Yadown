package com.yadown.di

import android.content.Context
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.dao.PeerIndexDao
import com.yadown.core.peer.FilePeerTrustStore
import com.yadown.core.peer.NsdPeerDiscovery
import com.yadown.core.peer.PeerClientRegistry
import com.yadown.core.peer.PeerDiscovery
import com.yadown.core.peer.PeerHandshake
import com.yadown.core.peer.PeerManifestClient
import com.yadown.core.peer.PeerManifestSource
import com.yadown.core.peer.PeerRuntime
import com.yadown.core.peer.PeerShareRepository
import com.yadown.core.peer.PeerShareSettings
import com.yadown.core.peer.PeerSourceProvider
import com.yadown.core.peer.PeerTrustStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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

    @Provides
    @Singleton
    fun providePeerTrustStore(@ApplicationContext context: Context): PeerTrustStore =
        FilePeerTrustStore(File(context.filesDir, "peer_trust.json"))

    @Provides
    @Singleton
    fun providePeerClientRegistry(): PeerClientRegistry = PeerClientRegistry()

    @Provides
    @Singleton
    fun providePeerManifestSource(): PeerManifestSource = PeerManifestClient()

    @Provides
    @Singleton
    fun providePeerHandshake(): PeerHandshake = PeerHandshake()

    @Provides
    @Singleton
    fun providePeerSourceProvider(
        discovery: PeerDiscovery,
        trust: PeerTrustStore,
        manifests: PeerManifestSource,
        clients: PeerClientRegistry
    ): PeerSourceProvider = PeerSourceProvider(discovery, trust, manifests, clients)

    @Provides
    @Singleton
    fun providePeerShareSettings(@ApplicationContext context: Context): PeerShareSettings =
        PeerShareSettings(context)

    @Provides
    @Singleton
    fun providePeerShareRepository(
        intentDao: IntentDao,
        @ApplicationContext context: Context
    ): PeerShareRepository = PeerShareRepository(intentDao, context.filesDir)

    @Provides
    @Singleton
    fun providePeerRuntime(
        @ApplicationContext context: Context,
        settings: PeerShareSettings,
        shares: PeerShareRepository,
        trust: PeerTrustStore,
        discovery: PeerDiscovery,
        clients: PeerClientRegistry,
        handshake: PeerHandshake
    ): PeerRuntime = PeerRuntime(
        context = context,
        settings = settings,
        shares = shares,
        trust = trust,
        discovery = discovery,
        clients = clients,
        handshake = handshake
    )
}
