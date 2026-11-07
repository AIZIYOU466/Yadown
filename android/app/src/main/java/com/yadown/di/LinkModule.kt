package com.yadown.di

import android.content.Context
import com.yadown.core.net.AndroidLinkAggregator
import com.yadown.core.net.LinkAggregationSettings
import com.yadown.core.net.LinkAggregator
import com.yadown.core.net.LinkHealthStore
import com.yadown.core.net.LinkRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object LinkModule {

    @Provides
    @Singleton
    fun provideLinkAggregationSettings(
        @ApplicationContext context: Context
    ): LinkAggregationSettings = LinkAggregationSettings(context)

    @Provides
    @Singleton
    fun provideLinkHealthStore(): LinkHealthStore = LinkHealthStore()

    @Provides
    @Singleton
    fun provideLinkAggregator(
        @ApplicationContext context: Context,
        client: OkHttpClient
    ): LinkAggregator = AndroidLinkAggregator(context, client)

    @Provides
    @Singleton
    fun provideLinkRegistry(
        aggregator: LinkAggregator,
        settings: LinkAggregationSettings,
        health: LinkHealthStore
    ): LinkRegistry = LinkRegistry(aggregator, settings, health)
}
