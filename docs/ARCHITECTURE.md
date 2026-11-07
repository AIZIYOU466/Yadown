# Yadown 架构文档（v1.0）

> 定稿来源：与 DeepSeek 讨论定型的「顶尖安卓下载器」架构（分享链接 h5zgapohajh5b1tcwu）。本文档为唯一规范源，后续讨论若试图推翻以下冻结条款，直接拒绝。

## 0. 架构定型声明

以下五条已冻结，所有后续工作建立在此之上，不再讨论：

1. **核心洞见**：下载器是"在不可靠的文件系统、网络、进程生命周期之下，让磁盘状态持续收敛到完整且正确"的系统，目标函数是**能力解锁时间**，不是峰值速度。
2. **场景**：移动端零等待内容消费，**不做通用下载器**。
3. **四层划分**：内容语义层 / 多源协同层 / 传输收敛层 / 系统生存层。
4. **关键机制**：位图意图 + 交叉校验、动态裂解、四重 Range 校验、UIDT、ROM 感知。
5. **代价承诺**：长期维护语义解析矩阵 + ROM 适配矩阵。

### 0.1 正确性不变量（实现验收标准）

以下六条优先于任何性能/功能目标，未满足即不算完成：

1. **I1 完成谓词**：`COMPLETED` 当且仅当 DB 中 DONE 分块覆盖 `[0,totalSize)` 且文件长度足够；队列为空不算完成。裂解子块与原始计划块一视同仁。
2. **I2 提交顺序**：数据 `fdatasync` → 位图原子替换 + 目录 fsync → DB 事务提交；任何一步失败都不能产生“DB 说完成、文件没有”的状态。
3. **I3 终止性**：裂解有深度/重试预算，终态原因（403/416/ENOSPC/TLS/Content-Encoding 等）不裂解；单分块请求数有上界。
4. **I4 协议先行**：先校验 HTTP 状态、Content-Range、Content-Length，再读 body；body 读取有硬上限；非 Range 源走单流顺序回退。
5. **I5 身份与版本**：位图绑定 plan fingerprint；DB 是 DONE 的唯一提交记录；启动时做 DB/位图/文件三真相对账。
6. **I6 平台闭环**：dataSync 前台服务必须实现 `onTimeout`；UIDT 按 Android 14+ JobScheduler 实现；peer 传输必须先有信任模型，不允许明文 HTTP。

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│  L1 内容语义层 (ContentSemanticLayer)                            │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │ FormatResolver│ │ Availability │ │ StreamPipe               │ │
│  │ (MP4/APK/PDF)│ │ Contract     │ │ (边下边播/增量安装/预览)  │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │ ContentDescriptor + ValueMap
┌────────────────────────────▼────────────────────────────────────┐
│  L2 多源协同层 (MultiSourceLayer)                                │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │ PeerDiscovery│ │ LinkAggregator│ │ SourceHealthRegistry    │ │
│  │ (mDNS)       │ │ (Wi-Fi+蜂窝) │ │ (吞吐/失败率/成本学习)    │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │ SourceRoute + CostEstimate
┌────────────────────────────▼────────────────────────────────────┐
│  L3 传输收敛层 (TransferConvergenceLayer)                        │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │ Scheduler    │ │ ChunkEngine  │ │ RangeValidator           │ │
│  │ (健康感知)    │ │ (动态裂解)   │ │ (四重校验)                │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
└────────────────────────────┬────────────────────────────────────┘
                             │ ChunkState + FailureAttribution
┌────────────────────────────▼────────────────────────────────────┐
│  L4 系统生存层 (SystemSurvivalLayer)                             │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────────┐ │
│  │ Lifecycle    │ │ Persistence  │ │ PlatformAdapter          │ │
│  │ (UIDT/前台)  │ │ (位图+合并闸) │ │ (ROM/存储/网络探测)       │ │
│  └──────────────┘ └──────────────┘ └──────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │ TelemetryStore  │  ← 所有层读写同一份历史trace
                    └─────────────────┘
```

### 层间契约

| 方向 | 契约 | 内容 |
|---|---|---|
| L1 → L2 | `ContentDescriptor` + `ValueMap` | 内容元数据 + 价值密度排序 |
| L2 → L3 | `SourceRoute` + `CostEstimate` | 候选源列表 + 成本估计 |
| L3 → L4 | `ChunkState` + `FailureAttribution` | 分块状态 + 失败归因链 |
| L4 → 所有层 | `PlatformCapability` | ROM 类型、存储能力、网络能力、UIDT 可用性 |

---

## 2. 核心数据模型（数据库 Schema）

### 2.1 `intents` 表（下载意图）

```sql
CREATE TABLE intents (
    id TEXT PRIMARY KEY,                    -- UUID
    content_id TEXT NOT NULL,               -- 内容哈希 SHA-256
    source_url TEXT NOT NULL,
    total_size INTEGER,                     -- NULL = 未知
    mime_type TEXT,
    etag TEXT,                              -- 首次探测的ETag，用于If-Range
    last_modified TEXT,
    range_supported INTEGER DEFAULT 0,      -- 布尔
    value_map_json TEXT,                    -- ValueMap序列化
    availability_contract_json TEXT,        -- 可用性契约
    mirror_urls_json TEXT,                  -- 与主源内容身份一致的镜像 URL 列表
    state TEXT NOT NULL,                    -- PENDING/ACTIVE/PAUSED/COMPLETED/FAILED
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

### 2.2 `chunks` 表（分块）

```sql
CREATE TABLE chunks (
    id TEXT PRIMARY KEY,                    -- contentId + range 的哈希
    intent_id TEXT NOT NULL,
    start_byte INTEGER NOT NULL,
    end_byte INTEGER NOT NULL,
    state TEXT NOT NULL,                    -- PENDING/RUNNING/DONE/FAILED/INVALIDATED
    retry_count INTEGER DEFAULT 0,
    checksum INTEGER,                       -- xxHash64，DONE时写入
    connection_fingerprint TEXT,            -- 完成该分块的连接指纹
    failure_chain_json TEXT,                -- 失败归因链
    started_at INTEGER,
    completed_at INTEGER,
    FOREIGN KEY (intent_id) REFERENCES intents(id)
);
CREATE INDEX idx_chunks_state ON chunks(state);
CREATE INDEX idx_chunks_intent ON chunks(intent_id);
```

### 2.3 `bitmap_sidecar` 表（位图元数据）

```sql
CREATE TABLE bitmap_sidecar (
    intent_id TEXT PRIMARY KEY,
    bitmap_path TEXT NOT NULL,              -- .bitset 文件路径
    total_chunks INTEGER NOT NULL,
    last_committed_at INTEGER NOT NULL,     -- 上次批量提交时间
    FOREIGN KEY (intent_id) REFERENCES intents(id)
);
```

### 2.4 `source_health` 表（源健康度学习）

```sql
CREATE TABLE source_health (
    source_key TEXT PRIMARY KEY,            -- 域名+路径前缀的哈希
    ewma_throughput REAL,                   -- 滑动窗口吞吐
    ewma_failure_rate REAL,
    connection_limit_learned INTEGER,       -- 学到的保守连接上限
    last_updated INTEGER,
    ttl_expires_at INTEGER,                 -- 24h TTL
    ewma_latency REAL DEFAULT 0.0           -- v3 增加：滑动窗口延迟
);
```

### 2.5 `peer_index` 表（局域网 peer）

```sql
CREATE TABLE peer_index (
    peer_id TEXT PRIMARY KEY,
    ip_address TEXT NOT NULL,
    port INTEGER NOT NULL,
    content_hashes_json TEXT,               -- 该peer拥有的内容哈希列表
    last_seen_at INTEGER NOT NULL,
    reliability_score REAL DEFAULT 0.5
);
```

### 2.6 `platform_capability` 表（平台能力快照）

```sql
CREATE TABLE platform_capability (
    key TEXT PRIMARY KEY,                   -- rom_type/io_mode/uidt_available/...
    value TEXT NOT NULL,
    probed_at INTEGER NOT NULL,
    ttl_expires_at INTEGER
);
```

---

## 3. 关键状态机

### 3.1 Chunk 状态机

```
        ┌─────────┐
        │ PENDING │◄──────────────────────────┐
        └────┬────┘                           │
             │ 调度器分配                      │ 裂解/重置
             ▼                                │
        ┌─────────┐   无进展超时(20s)    ┌────┴────┐
        │ RUNNING │────────────────────►│ FAILED  │
        └────┬────┘                     └────┬────┘
             │ 校验通过                       │ 重试预算耗尽
             ▼                                ▼
        ┌─────────┐                     ┌──────────┐
        │  DONE   │                     │INVALIDATED│
        └─────────┘                     └──────────┘
             │                                │
             │ 交叉校验发现文件空洞            │ 换源重新调度
             ▼                                ▼
        ┌─────────┐                     ┌─────────┐
        │ PENDING │                     │ PENDING │
        └─────────┘                     └─────────┘
```

**转换规则**：
- `RUNNING → FAILED`：stall watchdog（20s 无字节到达）触发，或网络错误
- `FAILED → PENDING`：retry_count < 3
- `FAILED → INVALIDATED`：retry_count ≥ 3
- `DONE → PENDING`：恢复时交叉校验发现文件对应区间为空，或完成校验发现覆盖/长度不成立
- `INVALIDATED → PENDING`：仅由用户主动重试或修复流程触发；进程自动续传不重置，避免失败任务无限复活

### 3.2 Intent 状态机

```
PENDING → ACTIVE → COMPLETED
              ↓
           PAUSED → ACTIVE
              ↓
           FAILED → PENDING（用户重试）
```

---

## 4. 失败归因链

每个失败的分块记录完整归因链，不是单一原因：

```kotlin
enum class FailureCause {
    NETWORK_TIMEOUT,
    DNS_RESOLVE_FAILED,
    TLS_HANDSHAKE_FAILED,
    PRIVATE_APN_DETECTED,        // 目标级可达性探测失败
    SERVER_RANGE_UNSUPPORTED,
    RANGE_NOT_SATISFIABLE,       // 416
    SERVER_SILENT_REJECTION,     // 200+空body/非预期状态
    CONTENT_CHANGED,             // ETag不匹配
    CHECKSUM_MISMATCH,
    STORAGE_UNREACHABLE,         // SD卡移除/目录不可写
    STORAGE_FULL,                // ENOSPC
    ROM_KILLED_SERVICE,
    RATE_LIMITED,
    BUDGET_EXCEEDED,
    PEER_UNAVAILABLE,
    INCOMPLETE,                  // 完成校验未覆盖全部字节
    UNKNOWN
}

data class FailureChain(
    val causes: List<FailureCause>,  // 按发生顺序
    val context: Map<String, String> // 补充信息
)
```

### 恢复决策表（由归因链驱动）

| 归因链首因 | 恢复动作 |
|---|---|
| NETWORK_TIMEOUT / DNS_RESOLVE_FAILED | 退避重试，健康度下降 |
| PRIVATE_APN_DETECTED | 暂停，等网络切换，不发请求 |
| SERVER_SILENT_REJECTION | 降低并发数，换源 |
| CONTENT_CHANGED | 所有已完成分块标记 INVALIDATED，从零开始 |
| STORAGE_UNREACHABLE | 暂停，等存储恢复 |
| STORAGE_FULL | 暂停，通知用户 |
| ROM_KILLED_SERVICE | 重新引导权限设置 |

### 扩展（定稿表未显式列出的归因）

以下归因在定稿表外，按语义补全，代码见 `FailureRecovery.kt`，并有单测覆盖：

| 归因链首因 | 恢复动作 |
|---|---|
| TLS_HANDSHAKE_FAILED | RETRY_BACKOFF |
| SERVER_RANGE_UNSUPPORTED | SEQUENTIAL_FALLBACK（单流顺序下载；不裂解） |
| CHECKSUM_MISMATCH | RETRY_BACKOFF（重下该分块） |
| RANGE_NOT_SATISFIABLE | RETRY_BACKOFF（先校验 total 是否漂移） |
| RATE_LIMITED | RETRY_BACKOFF |
| BUDGET_EXCEEDED | PAUSE_NOTIFY_USER |
| PEER_UNAVAILABLE | REDUCE_CONCURRENCY_SWITCH_SOURCE |
| UNKNOWN | RETRY_BACKOFF |
| 空归因链 | NONE |

---

## 5. 每层接口契约

### 5.1 L1 内容语义层

```kotlin
interface FormatResolver {
    suspend fun resolve(source: SourceSpec, probe: RangeProbe): ContentDescriptor?
}

data class ContentDescriptor(
    val contentId: String,
    val totalSize: Long,
    val mimeType: String,
    val valueMap: ValueMap,
    val availabilityContract: AvailabilityContract,
    val sinkSpec: SinkSpec
)

interface StreamPipe {
    // 视频：边下边播
    fun createVideoPipe(descriptor: ContentDescriptor): VideoSink
    // APK：增量安装
    fun createApkPipe(descriptor: ContentDescriptor): ApkSink
    // 文档：按页预览
    fun createDocumentPipe(descriptor: ContentDescriptor): DocumentSink
}
```

支持格式（第一版）：**MP4、APK、PDF**。其余格式退化为顺序传输（valueMap = null）。

### 5.2 L2 多源协同层

```kotlin
interface PeerDiscovery {
    fun discover(): Flow<List<Peer>>
    fun queryContent(contentId: String): List<Peer>
}

interface LinkAggregator {
    fun availableLinks(): List<NetworkLink>  // Wi-Fi、蜂窝、双卡
    fun bindRequest(link: NetworkLink): OkHttpClient
}

interface SourceHealthRegistry {
    suspend fun recordSuccess(sourceKey: String, throughput: Double, latencyMs: Double)
    suspend fun recordFailure(sourceKey: String, cause: FailureCause)
    suspend fun get(sourceKey: String): SourceHealth?
    suspend fun getConnectionLimit(sourceKey: String): Int?
}

// 内容→源映射由调用方以 endpoint 列表提供，因此不保留 contentId 版查询。
interface SourceRouter {
    suspend fun rank(endpoints: List<SourceEndpoint>): List<SourceRoute>
    fun pick(routes: List<SourceRoute>, slot: Int, inFlight: (String) -> Int): SourceRoute
}
```

**T4.1/T4.4 实现口径（2026-11）**：

- `source_key` = 域名 + 路径前两级，不含 query（避免签名参数把同一源拆成无数 key）。
- 吞吐/延迟/失败率均为 EWMA（α=0.3，24h TTL）；只有可归因于源的失败写入健康度，存储、内容漂移、预算类失败不污染源评分。
- 连接上限从 2 起步，失败率 ≤0.05 时逐次 +1、上限 4（与每主机闸门一致）；失败率 >0.2 立即减半，下限 1。
- 评分 = 0.55×可靠性 + 0.30×吞吐归一化 + 0.15×本地性 − 0.10×成本；无样本源用中性先验（0.8/0.5）。
- 逐块选源：`slot = chunkIndex + sourceSlot` 轮转，失败后 `sourceSlot+1` 换源；每源在途数受学习到的连接上限约束，全局仍受 DispatcherPolicy 与 TransferLimiter 约束。
- **镜像准入**：必须与主源大小、Range 能力一致，且 ETag 或 Last-Modified 至少一项相同。分块校验和只能证明块没写坏，不能证明各源给的是同一份字节；身份不可证明的镜像一律拒绝。

**peer 信任模型（定案，T4.3 实施前生效）**：

- peer 传输只允许自签 TLS（证书与私钥存 Android KeyStore），禁止明文 HTTP。
- 首次连接由双方比对 6 位短码（证书 SHA-256 指纹派生）后 TOFU 固定；指纹变化必须重新配对。
- 内容按分块 xxHash64 校验，并核对内容身份（大小 + ETag/Last-Modified 或签名内容清单）。
- mDNS 本地网络权限：targetSdk 36 及以下由 INTERNET 隐式授予，**不要**声明 `ACCESS_LOCAL_NETWORK`；targetSdk 37+ 才声明并在运行时请求，或优先使用 `DiscoveryRequest.FLAG_SHOW_PICKER` 的系统选择器。

**T4.3 实现口径（2026-11）**：

- 协议：`GET /v1/lookup/<contentKey>` 返回 manifest；`GET /v1/content/<contentId>` 只接受单区间 `Range`，返回 206 + `Content-Range`/`ETag`/`Last-Modified`/`X-Yadown-XxHash64`。越界、多区间、缺 Range 返回 416；非 GET 405；非法路径 400。
- `contentKey` = 规范化 sourceUrl（scheme/host 小写、去默认端口、保留 path/query、拒绝带 userInfo）的 SHA-256 前 16 hex。
- 身份校验：manifest 的 `contentKey`、`size` 必须与本地一致；强 ETag 相等，或（无强 ETag 时）Last-Modified 相等。两项都没有的任务不参与 peer 传输。
- 分块校验：服务端对每个响应区间计算 xxHash64 写入响应头；客户端在 `PeerRangeSource` 中与收到的字节比对，缺失或不匹配都按 `CHECKSUM_MISMATCH` 失败，不降级放行。
- 证书：AndroidKeyStore 生成 RSA 2048 自签证书（PURPOSE_SIGN + DIGEST_NONE + PKCS1），TLS 固定 1.2（TLS 1.3 需要 RSA-PSS 签名授权）；私钥不可导出。
- TOFU 存 `filesDir/peer_trust.json`（原子替换），以证书指纹为键，不写 Room：指纹是稳定身份，`peer_index.peer_id` 会随服务名变化。
- 共享范围：仅 `COMPLETED`、支持 Range 且文件长度与记录一致的任务；共享总开关默认关闭，关闭即停止服务端与发现。
- mDNS TXT 只广播设备名、证书指纹与最近 4 个 contentKey（受 key+value < 255 字节限制）；广播的指纹只用于命中信任库，配对短码始终来自实际 TLS 握手。
- 服务端限制：单请求区间 ≤2MiB、单连接 15s、全局 8 连接、keep-alive ≤32 请求；contentId 只用于查共享白名单，不拼入文件路径。

**T4.5 实现口径（2026-11，真机绑定待 P0.1 回填）**：

- 链路作为 `SourceEndpoint` 的维度：`linkId` 非空表示绑定该网络；`healthKey` 追加 `|linkId`，同一 URL 的不同链路各自学习健康度与连接上限。
- `SourceRanker` 按 `(url, linkId)` 去重；`SourceSelection` 的槽位轮转把分块分散到多条链路。
- 绑定：`Network.socketFactory` 绑定 raw socket，TLS 在其上建立；每条链路用独立 OkHttpClient 与连接池，不调用 `bindProcessToNetwork`。
- 准入：只用 INTERNET + VALIDATED 链路；私网/`.local`/单标签主机只允许 Wi-Fi/以太网；计费链路需用户允许（默认允许，UI 提示流量）。
- 少于两条可用链路时不聚合，直接走系统默认路由，单链路行为不变。
- 限流：`TransferLimiter` 键为 `host@linkId`，每链路每主机 4 连接、全局 8；链路失败由 `sourceSlot+1` 换源与 `LinkHealthStore` 熔断（连续 2 次失败冷却 30s）双重处理。
- 真机验收：双链路吞吐 > 单链路 1.5x；`Network.socketFactory` 对 HTTPS 的绑定效果需 P0.1 真机回填。
- **性能前提（2026-11 校准）**：单链路若跑不到物理带宽 70%，双链路只是两个低效调度器并行。多链路吞吐验收必须排在单链路事件驱动裂解、OkHttp 连接池治理、I/O 非阻塞化之后，用同设备/同服务器对比。

三条工程假设需在此层验证（见第 8 节）。

### 5.3 L3 传输收敛层

```kotlin
interface Scheduler {
    fun onHealthChanged(score: Float)
    fun allocate(chunk: Chunk, availableConnections: List<Connection>): Assignment
}

interface ChunkEngine {
    suspend fun transfer(chunk: Chunk, connection: Connection): ChunkResult
    fun split(chunk: Chunk): Pair<Chunk, Chunk>  // 动态裂解
}

interface RangeValidator {
    fun validate(response: Response, requested: Range, sessionEtag: String): ValidationResult
}
```

**调度决策**：

```kotlin
fun computeScore(chunk: Chunk, intent: Intent, source: SourceRoute): Float {
    return w_semantic * valueDensity(chunk)
         + w_urgency * urgencyWeight(intent)
         + w_locality * localBonus(chunk, source)
         - w_cost * costEstimate(source, chunk.size)
         - w_retry * chunk.retryCount
}
```

**并发闸门**：

```kotlin
fun adjustDispatcher(health: Float, storageIops: Int, linkCount: Int = 1) {
    val dual = linkCount >= 2
    val networkLimit = when {
        health > 0.7 -> if (dual) 24 else 16
        health > 0.3 -> if (dual) 12 else 8
        health > 0.1 -> 2
        else -> 0
    }
    val storageLimit = when {
        storageIops > 5000 -> 32
        storageIops > 2000 -> 16
        else -> 2
    }
    dispatcher.maxRequests = min(networkLimit, storageLimit)
}
```

**RangeValidator 四重校验**：
1. HTTP 状态码是否 206（或 200 单块退化为顺序传输）
2. `Content-Range` 与请求 Range 是否一致，且 total 不能漂移
3. `ETag` / `Last-Modified` 与会话起始值是否一致（防内容漂移；弱 ETag 不能作为强校验器）
4. body 实际字节数与声明区间长度是否一致（防截断/空 body）

**实现约束（2026-11 补充）**：

- 200 只允许“请求覆盖整个文件”时读取；部分区间返回 200 时立即关闭响应，绝不先读全量 body。
- `206` 读取上限为 `expected + 1` 字节；多读的 1 字节只用于发现超长 body，避免把整文件读进内存。
- `416` 校验 `bytes */total` 是否漂移；`429` 读 `Retry-After` 并归因 `RATE_LIMITED`；`Content-Encoding` 非 identity 直接拒绝。
- 非 Range 源、或中途确认不支持 Range 的任务，下一次重试走 `SequentialDownloader`，按文件长度续传；不能续传时明确从头开始。
- `If-Range` 目前采用事后 ETag 校验而非请求头；这是效率优化，不改变正确性，后续可加。

**单链路调度与连接治理（P1/P2，2026-11）**：

- 事件驱动裂解：1s 窗口跨活动块中位数，连续 2 个窗口低于相对阈值后由 `ChunkSplitPolicy` 准入：全局中位数 <64KB/s 不裂解、已传 ≥25% 不取消、per-key=1 不裂解；子块插到队列最前，允许并发临时 +20%，块完成后回落。
- 自适应粒度：<256KB/s 最小 512KB，中速 1MB，高速 2MB；尾部 64–128KB 允许 64KB 微拆。慢裂解不消耗失败预算，达最小粒度后换源重试并计预算，防止无限拆分。
- 原子性：`replaceWithChildren` 单事务完成父段失效与子段插入；慢裂解期间父段为 INVALIDATED，不参与完成校验。
- 限额：全局配额动态（单链路 16、双链路 24），per-key 由 `source_health` 学习值传入（默认 8、上限 16、下限 2，失败迟滞）；OkHttp `Dispatcher` 显式设为 64/32，消除默认每主机 5 的隐藏上限。
- 健康度：连接上限默认 8、上限 16、下限 2；单次失败不降并发，连续 3 次或 10s 窗口失败率 >50% 才减半；成功后失败率快速衰减（×0.5）并逐次扩容。401/403 归因 `LINK_EXPIRED`，不写入源健康度且不重试膨胀。健康键对 GitHub release assets 纳入第三段文件 UUID，避免同 repo 不同文件共享一次坏链接的惩罚。
- 熔断：10s 窗口失败率 >50% 或连续 3 次失败即熔断；429 按 `Retry-After` 冷却且不裂解；DNS 失败 5s 冷却；冷却后允许半开探测，成功清零。
- 观测：`TransferTelemetry` 进程累计 + `SessionTelemetry` 按 intent 的会话统计（完成/失败/字节/重试/stall/慢裂解/耗时/平均速度/峰值并发/本任务新建连接）；debug 构建顶栏「诊断」分栏展示。真机回填 P95/stall/复用率/429 率，容器内只验证逻辑。
- 文件名：`DownloadPaths.fileNameOf` 优先解析签名 URL 的 `response-content-disposition`/`rscd` 中的 `filename=`，再回退 path 最后一段，并过滤路径分隔符与控制字符；任务卡/通知/存储均使用真实文件名。
- 拓扑观察（2026-11-07）：同一 22.4MB Azure Blob 链接 8 次 A/B（各 4 次）：REUSE 中位 13s/1.69MB/s，MULTI_CONNECTION 中位 7s/2.97MB/s（约 1.76x）；单连接 H2 稳定在 ~1.7MB/s，多连接可叠加到 ~3MB/s。之前两次异常样本（REUSE 6s vs MULTI 20s）是网络波动，不作为结论。默认已翻转为 `AUTO`：总大小 ≥8MB 走 MULTI_CONNECTION，小文件/未知大小保持 REUSE；手动开关可强制。
- host 连接数学习：`HostTuningStore`（SharedPreferences JSON，不动 Room schema）按 host 记录 `(limit → EWMA 吞吐, 样本数)`；`HostTuningPolicy.choose` 先把每个候选（8/12/16）探索到 2 个样本，再选 EWMA 最高档；任务结束仅在「无失败、有实际字节」时 `record`，避免把网络故障当档位差异。`DownloadRepository` 在 AUTO+MULTI 时优先用学到的 limit，诊断页展示每 host 学习快照。意义：GitHub 甜点 8、Azure 16，单一全局值只是折中，按 host 学习可自动适配不同 CDN。
- 多连接数：`TransportTopologySettings.multiConnectionLimit`（4/6/8/12/16，默认 **8**）与 `TopologyConcurrency.concurrencyFor(linkCount, topology, limit)` 统一计算单/双链路并发预算；MULTI 下即 TCP 连接数上限，REUSE/AUTO 小文件固定 16，双链路 24。诊断页显示「并发预算」。容器内 `MultiConnectionThroughputTest` 用每连接限速 server 验证 4 连接与 2 连接的聚合吞吐比例。真机 GitHub A/B（2026-11-07）：预算 8 中位 8s/2.69MB/s（只开 3 条 TCP，复用 67–72%）、预算 12 中位 18.5s/1.28MB/s、预算 16 中位 16s/1.37MB/s；8 为当前 GitHub CDN 甜点，16 额外连接主要付握手与公平限速代价。Azure Blob 当时 16 最优，两 CDN 甜点不同，后续按 host 学习。
- 拓扑 A/B 落地（2026-11-07，真机数据待回填）：`TransportTopologySettings` + `TopologyRangeSource` 提供两种模式——`REUSE`（默认，保留 H2 单连接复用，行为不变）与 `MULTI_CONNECTION`（强制 HTTP/1.1，并发分块各占一条 TCP）；入口在「多链路加速」对话框，切换后下一个分块生效，多连接客户端延迟创建、未启用时不多出连接池。该开关只作用于单链路默认源；多链路每条链路本就有独立客户端与连接池，不受影响。`SessionTelemetry` 记录会话开始时的拓扑与并发预算，诊断页与 `connectStarts`/峰值并发/平均速度同屏展示。默认值已翻转为 `AUTO`（≥8MB 多连接），翻转依据见上条 8 次 A/B。
- 已知限制：整块读入内存后落盘；慢裂解仍会丢弃已传字节，但已限制为块内 <25% 且低速/单连接不裂解；零丢弃需要流式读取+部分提交（后续）。

**I/O 非阻塞化（P3，2026-11）**：

- 传输协程只提交写任务（有界队列 16 块，背压），不执行磁盘系统调用；单写线程用 `FileChannel.write(buffer, position)` 定位写，支持乱序块。
- 提交协调器每 2s（`FsyncGate`）或下载结束时：`pipeline.flush()`（等待写完 + `force(true)`）→ 批量写 DB DONE → 批量保存位图。不变式：**DB 中 DONE 的块，其字节已持久化**。
- 掉电：未提交块 DB 仍为 RUNNING/PENDING，重启后重下；文件完整但 DB 未提交时不判完成。提交失败只重试 DB，不重下已落盘数据。
- 播放器 `DownloadingDataSource` 用 `RangeReadySignal` 条件变量唤醒，替代固定 150ms 轮询；超时仍兜底。
- **Direct I/O 不实现**：Java/`FileChannel` 无 O_DIRECT API，且 O_DIRECT 要求缓冲区地址/长度对齐，纯 JVM 无法保证；需要 native（NDK/JNI）+ inline encryption 探测，当前工程不具备，不做伪实现。
- 真机待回填：传输线程磁盘等待占比 <5%、掉电恢复分块校验通过率 >99.9%、首播时间不因批量 fsync 退化。

### 5.4 L4 系统生存层

```kotlin
interface LifecycleManager {
    fun selectCarrier(intent: Intent): Carrier
    // Carrier = FOREGROUND_SERVICE / UIDT / WORK_MANAGER / DOZE_STOPPED
}

interface PersistenceManager {
    fun commitBitmap(intentId: String, chunks: List<ChunkId>)
    fun crossValidate(intentId: String): RecoveryPlan
    fun globalFsyncGate(intentId: String)  // 整文件级合并闸
}

interface PlatformAdapter {
    fun probeRom(): RomType
    fun probeStorageCapability(uri: Uri): StorageCapability
    fun probeNetworkReachability(host: String): Reachability
    fun probeUidtAvailability(): Boolean
    fun probeInlineEncryption(): Boolean
}
```

**生命周期选择规则**：

| 条件 | Carrier |
|---|---|
| 用户主动下载 + 前台活跃 | FOREGROUND_SERVICE |
| 用户主动下载 + 后台 + UIDT 可用 | UIDT |
| 用户主动下载 + 后台 + UIDT 不可用 | FOREGROUND_SERVICE + WorkManager 兜底 |
| 可延迟的后台补齐 | WORK_MANAGER |
| Doze 状态 | DOZE_STOPPED（保存位图） |

**平台约束（2026-11 补充）**：

- target 35+ 的 `dataSync` 前台服务在 24 小时内累计 6 小时后会收到 `Service.onTimeout`；必须保存状态并 `stopSelf()`，否则系统抛 `RemoteServiceException`。
- UIDT 是 Android 14（API 34）的 JobScheduler 能力，需要 `RUN_USER_INITIATED_JOBS` + `JobService` + `setUserInitiated(true)`；不是 API 35 自动获得，也不是 FGS 的别名。
- target 28+ 默认禁止明文流量；L2 的局域网 peer 必须先定义 HTTPS/内容签名的信任模型，禁止直接用明文 HTTP。

---

## 6. 可观测指标

| 指标 | 定义 | 目标 | 用途 |
|---|---|---|---|
| Time-to-First-Playable | 从开始下载到可播放的时间 | < 10s（10Mbps） | 核心产品指标 |
| Time-to-First-Launchable | APK 可启动时间 | 第三方应用不可兑现；仅在系统应用或“下完即装”路径测量 | 参考指标，非核心承诺 |
| Completion Integrity Rate | COMPLETED 任务中通过覆盖+长度+校验的比例 | 100% | 正确性指标 |
| Request Amplification Bound | 单分块失败路径的最大请求数 | ≤ 15（1MB 分块，预算内） | 终止性指标 |
| Crash Recovery Correctness | 断点杀进程后完成校验通过率 | 100% | 崩溃一致性指标 |
| 分块恢复成功率 | 中断后从位图恢复且不重传已完成分块的比率 | > 99.9% | 可靠性指标 |
| 单次会话有效率 | 完成字节 / 消耗流量 | > 0.95 | 流量效率 |
| Stall 率 | 触发 20s 无进展超时的分块比率 | < 1% | 网络健康 |
| Peer 命中率 | 从局域网 peer 拉取的字节比率 | > 20%（家庭场景） | 多源协同效果 |
| ROM 存活率 | 传输过程中 Service 存活的比率 | > 95% | 厂商适配效果 |
| 失败归因覆盖率 | 有明确归因链的失败比率 | > 99% | 可运维性 |

---

## 7. 任务分解（按依赖顺序，明细见 ROADMAP.md）

| 阶段 | 内容 | 时长 | 依赖 |
|---|---|---|---|
| P0 | 三个工程假设验证 | 1 周 | 无（必须先做） |
| P1 | 传输收敛层（最独立、最可测） | 3 周 | P0 |
| P2 | 系统生存层 | 2 周 | P1.2 |
| P3 | 内容语义层 | 2 周 | 与 P2 并行 |
| P4 | 多源协同层 | 3 周 | P0 验证结果 + P1.5 |
| P5 | 集成与真机验证 | 2 周 | P1-P4 |

**P1 交付标准**：杀进程重启后，从位图恢复，不重传已完成分块；模拟弱网下，分块成功率 > 90%。

---

## 8. 三个必须优先验证的工程假设

| 假设 | 验证方法 | 失败后的降级 |
|---|---|---|
| 多链路聚合可行 | 在目标 API 上绑定 Wi-Fi 和蜂窝，同时发起两个 Range 请求 | 降级为单链路，多源协同层只保留 Peer 和 SourceHealth |
| mDNS 在家庭网络可靠 | 在 5 种常见路由器下测试 peer 发现率 | 降级为手动配对，或移除 peer 功能 |
| Android 12 以下 APK 流式安装有可行降级 | 测试"先下完再安装"的体验损失 | APK 流式安装仅支持 Android 12+ |

这三个假设的验证必须在 P1 开始前完成。它们不改变架构骨架，但会改变 L1 和 L2 的实现范围。

> 2026-11 执行状态：P0 仍待真机回填；P1/P2/P3 已按正确性脊柱先行落地。L2 在 P0/P5 通过前不进入实现，也不计入当前架构能力。

---

## 9. 不做什么（明确排除）

| 排除项 | 原因 |
|---|---|
| 通用协议支持（BT/磁力/FTP） | 场景聚焦内容消费，不做通用下载器 |
| 跨设备任务拆分 | 复杂度远超收益 |
| 自定义传输协议 | 兼容性代价高于收益 |
| 对抗运营商 DPI | 生态问题，非技术问题 |
| 内核级 BBR 替换 | 非 root 不可行，应用层模拟其精神即可 |
| io_uring | Android 应用进程默认禁用 |
| 0-RTT | 重放风险，默认禁用 |
| StrongBox 加密数据本身 | 只包 DEK，不加密数据 |

---

## 10. 代价承诺（长期维护矩阵）

- **语义解析矩阵**：MP4（moov 优先）/ APK（Manifest 优先）/ PDF（目录优先）各自的格式解析边界与降级路径，随内容格式演变持续维护。
- **ROM 适配矩阵**：EMUI/HyperOS/MIUI/ColorOS/OriginOS/AOSP 的 Service 存活、权限引导、存储行为差异，形成可查询的适配表格。

*架构版本：v1.1 — 2026-11-06*
*v1.1 变更：L2 源健康/路由落地；`intents.mirror_urls_json`、`source_health.ewma_latency`（Room v3）；peer 信任模型定案；本地网络权限口径随 Android 官方文档更新。*
*定稿来源：DeepSeek 分享对话 h5zgapohajh5b1tcwu + 用户确认（Yadown 项目，UI 对齐 AiCode，硬性要求对齐 yaya_ai R1）*