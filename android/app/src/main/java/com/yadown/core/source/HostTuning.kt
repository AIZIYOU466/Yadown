package com.yadown.core.source

import android.content.Context
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** 一个 host 在某个连接数下的吞吐样本（EWMA）。 */
data class LimitSample(
    val limit: Int,
    val throughput: Double,
    val samples: Int
)

/**
 * 每 host 连接数学习的纯策略：把「连接数 → 吞吐」当成多臂老虎机处理。
 *
 * - 未收集满 [explorationMin] 个样本的档位优先探索（先保证每个档位都有数据）；
 * - 收集满后选择 EWMA 吞吐最高的档位；
 * - 所有档次都没数据时回退 null，由调用方用默认值。
 *
 * 吞吐用平均字节/秒，EWMA α=0.3，对单次网络抖动有一定抗性但不掩盖长期差异。
 */
object HostTuningPolicy {

    const val DEFAULT_ALPHA = 0.3

    fun ewma(previous: Double?, observed: Double, alpha: Double = DEFAULT_ALPHA): Double {
        val value = observed.coerceAtLeast(0.0)
        return if (previous == null) value else alpha * value + (1 - alpha) * previous
    }

    fun choose(
        samples: Map<Int, LimitSample>,
        candidates: List<Int>,
        explorationMin: Int = 2
    ): Int? {
        if (candidates.isEmpty()) return null
        val unexplored = candidates.filter { (samples[it]?.samples ?: 0) < explorationMin }
        if (unexplored.isNotEmpty()) {
            return unexplored.minWithOrNull(
                compareBy({ samples[it]?.samples ?: 0 }, { candidates.indexOf(it) })
            )
        }
        return candidates
            .filter { samples.containsKey(it) }
            .maxByOrNull { samples.getValue(it).throughput }
    }

    /**
     * 剔除「每条连接摊不到 [minChunksPerConnection] 个分块」的档位。
     *
     * TCP+TLS 建连与 TCP 慢启动是每条连接的一次性成本，它只能摊到该连接实际扛的分块上：
     * 两个分块共用一条连接时，档位越高、每条连接摊到的块越少，多出的握手与冷启动就直接吃掉收益。
     * 真机 23 个 1MiB 分块跑预算 16 时每条连接只摊到 1.4 个块，首块与均速都不如 8——
     * 首波（新连接）比后续（热连接）慢一倍以上，就是这条成本的直接证据。
     *
     * 块数很少时只保留最小档位；条数足够时全部保留，不影响大文件。
     */
    fun usableLimits(
        chunkCount: Long,
        candidates: List<Int>,
        minChunksPerConnection: Int = 2
    ): List<Int> {
        if (candidates.isEmpty() || chunkCount <= 0) return candidates
        val maxUseful = chunkCount / minChunksPerConnection.coerceAtLeast(1)
        return candidates.filter { it <= maxUseful }.ifEmpty { listOf(candidates.first()) }
    }
}

/** 从 URL 提取学习键（host，小写；无 host 时返回 null）。 */
object HostTuning {
    fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * 每 host 连接数学习的持久化：`SharedPreferences` 存 JSON，避免为此新增 Room 表与迁移。
 *
 * 结构：`{ "host": { "8": {"t": 2690000.0, "n": 3}, ... } }`
 * 写入在进程内串行化；读到损坏 JSON 时按空处理，不影响下载。
 */
class HostTuningStore(context: Context) {

    private val prefs = context.getSharedPreferences("host_tuning", Context.MODE_PRIVATE)
    private val lock = Any()
    private val cache = ConcurrentHashMap<String, MutableMap<Int, LimitSample>>()

    fun samplesFor(host: String): Map<Int, LimitSample> = synchronized(lock) {
        loadLocked()[host]?.toMap() ?: emptyMap()
    }

    fun chooseLimit(host: String, candidates: List<Int>, explorationMin: Int = 2): Int? =
        HostTuningPolicy.choose(samplesFor(host), candidates, explorationMin)

    fun record(host: String, limit: Int, bytesPerSec: Double) {
        if (bytesPerSec <= 0.0) return
        synchronized(lock) {
            val all = loadLocked()
            val hostSamples = all.getOrPut(host) { mutableMapOf() }
            val previous = hostSamples[limit]
            hostSamples[limit] = LimitSample(
                limit = limit,
                throughput = HostTuningPolicy.ewma(previous?.throughput, bytesPerSec),
                samples = (previous?.samples ?: 0) + 1
            )
            saveLocked(all)
        }
    }

    fun snapshot(): Map<String, Map<Int, LimitSample>> = synchronized(lock) {
        loadLocked().mapValues { (_, value) -> value.toMap() }
    }

    private fun loadLocked(): MutableMap<String, MutableMap<Int, LimitSample>> {
        cache.clear()
        val raw = prefs.getString(KEY_DATA, null) ?: return mutableMapOf()
        return runCatching {
            val root = org.json.JSONObject(raw)
            val result = mutableMapOf<String, MutableMap<Int, LimitSample>>()
            root.keys().forEach { host ->
                val hostObj = root.optJSONObject(host) ?: return@forEach
                val samples = mutableMapOf<Int, LimitSample>()
                hostObj.keys().forEach { limitKey ->
                    val limit = limitKey.toIntOrNull() ?: return@forEach
                    val item = hostObj.optJSONObject(limitKey) ?: return@forEach
                    samples[limit] = LimitSample(
                        limit = limit,
                        throughput = item.optDouble("t", 0.0),
                        samples = item.optInt("n", 0)
                    )
                }
                result[host] = samples
            }
            result
        }.getOrDefault(mutableMapOf())
    }

    private fun saveLocked(all: Map<String, Map<Int, LimitSample>>) {
        val root = org.json.JSONObject()
        all.forEach { (host, samples) ->
            val hostObj = org.json.JSONObject()
            samples.forEach { (limit, sample) ->
                hostObj.put(
                    limit.toString(),
                    org.json.JSONObject()
                        .put("t", sample.throughput)
                        .put("n", sample.samples)
                )
            }
            root.put(host, hostObj)
        }
        prefs.edit().putString(KEY_DATA, root.toString()).apply()
    }

    private companion object {
        const val KEY_DATA = "samples"
    }
}
