package com.yadown.di

import android.content.Context
import com.yadown.core.io.FileStore
import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.LinkRegistry
import com.yadown.core.net.MultiLinkRangeSource
import com.yadown.core.net.OkHttpRangeSource
import com.yadown.core.net.RangeSource
import com.yadown.core.net.TopologyRangeSource
import com.yadown.core.net.TransportTopologySettings
import com.yadown.core.peer.PeerClientRegistry
import com.yadown.core.peer.PeerRangeSource
import com.yadown.core.semantic.FormatResolverRegistry
import com.yadown.core.transfer.BitmapStore
import com.yadown.core.transfer.DownloadEngine
import com.yadown.core.transfer.FileBitmapStore
import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.dao.SourceHealthDao
import com.yadown.core.source.RoomSourceHealthRegistry
import com.yadown.core.source.SourceHealthRegistry
import com.yadown.core.source.SourceRouter
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
    fun provideRangeSource(
        client: OkHttpClient,
        peers: PeerClientRegistry,
        links: LinkRegistry,
        topology: TransportTopologySettings
    ): RangeSource = MultiLinkRangeSource(
        // 拓扑开关只作用于单链路默认源：多链路每条链路本就有独立客户端与连接池。
        delegate = TopologyRangeSource(
            reuse = PeerRangeSource(OkHttpRangeSource(client), peers),
            multiConnection = {
                PeerRangeSource(
                    OkHttpRangeSource(OkHttpRangeSource.createClient(http1Only = true)),
                    peers
                )
            },
            topology = { topology.topology },
            resolve = { totalSize -> topology.resolve(totalSize) }
        ),
        linkSources = { linkId -> links.sourceFor(linkId) },
        health = links.health
    )

    @Provides
    @Singleton
    fun provideFileStore(): FileStore = RandomAccessFileStore()

    @Provides
    @Singleton
    fun provideBitmapStore(@ApplicationContext context: Context): BitmapStore =
        FileBitmapStore(File(context.filesDir, "bitmaps"))

    @Provides
    @Singleton
    fun provideFormatResolverRegistry(): FormatResolverRegistry = FormatResolverRegistry()

    @Provides
    @Singleton
    fun provideSourceHealthRegistry(dao: SourceHealthDao): SourceHealthRegistry =
        RoomSourceHealthRegistry(dao)

    @Provides
    @Singleton
    fun provideSourceRouter(registry: SourceHealthRegistry): SourceRouter =
        SourceRouter(registry)

    @Provides
    @Singleton
    fun provideDownloadEngine(
        source: RangeSource,
        files: FileStore,
        bitmaps: BitmapStore,
        intentDao: IntentDao,
        chunkDao: ChunkDao,
        healthRegistry: SourceHealthRegistry
    ): DownloadEngine = DownloadEngine(
        source = source,
        files = files,
        bitmaps = bitmaps,
        intentDao = intentDao,
        chunkDao = chunkDao,
        healthRegistry = healthRegistry
    )
}
