package com.yadown.core.net

import okhttp3.OkHttpClient

/** L2 链路聚合契约，见 docs/ARCHITECTURE.md §5.2。 */
interface LinkAggregator {
    fun availableLinks(): List<NetworkLink>

    fun bindRequest(link: NetworkLink): OkHttpClient
}
