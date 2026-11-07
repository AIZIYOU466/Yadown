package com.yadown.core.source

import com.yadown.core.net.ProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRouterTest {

    private val ranker = SourceRanker()

    private fun endpoint(url: String, kind: SourceKind = SourceKind.ORIGIN) =
        SourceEndpoint(url, kind)

    @Test
    fun sourceKeyUsesHostAndFirstTwoPathSegments() {
        assertEquals(
            "cdn.example.com/releases/android",
            SourceKey.of("https://CDN.example.com/releases/android/app.apk?token=secret")
        )
        assertEquals("example.com", SourceKey.of("https://example.com"))
        assertEquals("unknown", SourceKey.of("not a url"))
    }

    @Test
    fun releaseAssetUuidIsPartOfHealthKey() {
        val first = SourceKey.of(
            "https://release-assets.githubusercontent.com/github-production-release-asset/" +
                "679086250/9527f7b8-5f38-4dc9-8d5a-c82a69f6a3b9?signature=a"
        )
        val second = SourceKey.of(
            "https://release-assets.githubusercontent.com/github-production-release-asset/" +
                "679086250/11111111-2222-3333-4444-555555555555?signature=b"
        )

        assertNotEquals("不同 release 文件不能共享健康键", first, second)
        assertTrue(first.endsWith("9527f7b8-5f38-4dc9-8d5a-c82a69f6a3b9"))
    }

    @Test
    fun contentIdentityRequiresAProvableMatcher() {
        val primary = ProbeResult(100, true, "\"v1\"", null, null)

        assertTrue(ContentIdentity.matches(primary, ProbeResult(100, true, "\"v1\"", null, null)))
        assertFalse(ContentIdentity.matches(primary, ProbeResult(100, true, "\"v2\"", null, null)))
        assertFalse(ContentIdentity.matches(primary, ProbeResult(99, true, "\"v1\"", null, null)))
        assertFalse(ContentIdentity.matches(primary, ProbeResult(100, false, "\"v1\"", null, null)))

        val noEtag = ProbeResult(100, true, null, "Wed, 21 Oct 2026 07:28:00 GMT", null)
        assertTrue(
            ContentIdentity.matches(
                noEtag,
                ProbeResult(100, true, null, "Wed, 21 Oct 2026 07:28:00 GMT", null)
            )
        )
        assertFalse(
            ContentIdentity.matches(
                ProbeResult(100, true, null, null, null),
                ProbeResult(100, true, null, null, null)
            )
        )
    }

    @Test
    fun mirrorLocalityOutranksOriginWithNoSamples() {
        val routes = ranker.rank(
            listOf(
                endpoint("https://origin.example.com/a"),
                endpoint("https://mirror.example.com/a", SourceKind.MIRROR)
            ),
            health = emptyMap()
        )

        assertEquals("https://mirror.example.com/a", routes.first().url)
    }

    @Test
    fun fastReliableSourceOutranksSlowFlakySource() {
        val fast = SourceHealth(
            sourceKey = SourceKey.of("https://fast.example.com/a"),
            throughput = 5_000.0,
            failureRate = 0.0,
            latencyMs = 20.0,
            connectionLimit = 4,
            lastUpdated = 1_000,
            expiresAt = 10_000
        )
        val flaky = SourceHealth(
            sourceKey = SourceKey.of("https://flaky.example.com/a"),
            throughput = 200.0,
            failureRate = 0.9,
            latencyMs = 900.0,
            connectionLimit = 1,
            lastUpdated = 1_000,
            expiresAt = 10_000
        )

        val routes = ranker.rank(
            listOf(
                endpoint("https://flaky.example.com/a"),
                endpoint("https://fast.example.com/a")
            ),
            health = mapOf(fast.sourceKey to fast, flaky.sourceKey to flaky)
        )

        assertEquals("https://fast.example.com/a", routes.first().url)
        assertTrue(routes.first().usable)
        assertFalse(routes.last().usable)
        assertEquals(4, routes.first().connectionLimit)
    }

    @Test
    fun pickRotatesAcrossSourcesAndRespectsPerSourceLimit() {
        val routes = ranker.rank(
            listOf(
                endpoint("https://a.example.com/f"),
                endpoint("https://b.example.com/f")
            ),
            health = emptyMap()
        ).map { it.copy(connectionLimit = 1) }

        assertEquals("https://a.example.com/f", SourceSelection.pick(routes, 0, { 0 }).url)
        assertEquals("https://b.example.com/f", SourceSelection.pick(routes, 1, { 0 }).url)
        // a 在途已满：slot 0 跳到 b。
        assertEquals(
            "https://b.example.com/f",
            SourceSelection.pick(routes, 0, { key -> if (key.contains("a.example.com")) 1 else 0 }).url
        )
        // 全部满员时回退到 slot 对应源，避免主循环饿死。
        assertEquals("https://a.example.com/f", SourceSelection.pick(routes, 0, { 1 }).url)
    }

    @Test
    fun unusableSourceIsSkippedWhileAnotherIsAvailable() {
        val routes = ranker.rank(
            listOf(
                endpoint("https://bad.example.com/f"),
                endpoint("https://good.example.com/f")
            ),
            health = emptyMap()
        ).mapIndexed { index, route ->
            if (index == 0) route.copy(usable = false, connectionLimit = 1) else route
        }

        repeat(4) { slot ->
            assertEquals(
                "https://good.example.com/f",
                SourceSelection.pick(routes, slot, { 0 }).url
            )
        }
    }

    @Test
    fun duplicateUrlsAreRankedOnce() {
        val routes = ranker.rank(
            listOf(
                endpoint("https://a.example.com/f"),
                endpoint("https://a.example.com/f", SourceKind.MIRROR)
            ),
            health = emptyMap()
        )

        assertEquals(1, routes.size)
        assertNotEquals(SourceKind.MIRROR, routes.single().endpoint.kind)
    }

    @Test
    fun sameUrlOnDifferentLinksIsRankedSeparately() {
        val url = "https://a.example.com/f"
        val routes = ranker.rank(
            listOf(
                SourceEndpoint(url, linkId = "wifi"),
                SourceEndpoint(url, linkId = "cellular")
            ),
            health = emptyMap()
        )

        assertEquals(2, routes.size)
        assertEquals("a.example.com/f|wifi", routes[0].healthKey)
        assertEquals("a.example.com/f|cellular", routes[1].healthKey)
        assertNotEquals(routes[0].healthKey, routes[1].healthKey)
    }
}
