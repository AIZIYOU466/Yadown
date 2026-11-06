# Yadown 架构文档（v1.0）

> 定稿来源：与 DeepSeek 讨论定型的「顶尖安卓下载器」架构（分享链接 h5zgapohajh5b1tcwu）。本文档为唯一规范源，后续讨论若试图推翻以下冻结条款，直接拒绝。

## 0. 架构定型声明

以下五条已冻结，所有后续工作建立在此之上，不再讨论：

1. **核心洞见**：下载器是"在不可靠的文件系统、网络、进程生命周期之下，让磁盘状态持续收敛到完整且正确"的系统，目标函数是**能力解锁时间**，不是峰值速度。
2. **场景**：移动端零等待内容消费，**不做通用下载器**。
3. **四层划分**：内容语义层 / 多源协同层 / 传输收敛层 / 系统生存层。
4. **关键机制**：位图意图 + 交叉校验、动态裂解、四重 Range 校验、UIDT、ROM 感知。
5. **代价承诺**：长期维护语义解析矩阵 + ROM 适配矩阵。

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
    ttl_expires_at INTEGER                  -- 24h TTL
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
- `INVALIDATED → PENDING`：换源后重新调度
- `DONE → PENDING`：恢复时交叉校验发现文件对应区间为空

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
    SERVER_SILENT_REJECTION,     // 200+空body
    CONTENT_CHANGED,             // ETag不匹配
    CHECKSUM_MISMATCH,
    STORAGE_UNREACHABLE,         // SD卡移除/目录不可写
    STORAGE_FULL,                // ENOSPC
    ROM_KILLED_SERVICE,
    RATE_LIMITED,
    BUDGET_EXCEEDED,
    PEER_UNAVAILABLE,
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
    fun recordSuccess(sourceKey: String, throughput: Long, latency: Long)
    fun recordFailure(sourceKey: String, cause: FailureCause)
    fun getConnectionLimit(sourceKey: String): Int?
    fun getHealthySources(contentId: String): List<SourceRoute>
}
```

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
fun adjustDispatcher(health: Float, storageIops: Int) {
    val networkLimit = when {
        health > 0.7 -> 6
        health > 0.3 -> 3
        health > 0.1 -> 1
        else -> 0
    }
    val storageLimit = when {
        storageIops > 5000 -> 8
        storageIops > 2000 -> 4
        else -> 2
    }
    dispatcher.maxRequests = min(networkLimit, storageLimit)
}
```

**RangeValidator 四重校验**：
1. HTTP 状态码是否 206（或 200 单块退化为顺序传输）
2. `Content-Range` 与请求 Range 是否一致
3. `ETag` / `Last-Modified` 与会话起始值是否一致（防内容漂移）
4. body 实际字节数与声明区间长度是否一致（防截断/空 body）

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

---

## 6. 可观测指标

| 指标 | 定义 | 目标 | 用途 |
|---|---|---|---|
| Time-to-First-Playable | 从开始下载到可播放的时间 | < 10s（10Mbps） | 核心产品指标 |
| Time-to-First-Launchable | APK 可启动时间 | < 30s（10Mbps） | 核心产品指标 |
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

*架构版本：v1.0 — 2026-11-06*
*定稿来源：DeepSeek 分享对话 h5zgapohajh5b1tcwu + 用户确认（Yadown 项目，UI 对齐 AiCode，硬性要求对齐 yaya_ai R1）*