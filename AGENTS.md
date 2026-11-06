# Yadown 项目规则

本文件定义项目架构规则，**未经用户同意不得修改**。偏离本文件的改动必须先征得用户确认并同步更新本文件。

**定位**：运行在 Android 上的**移动端零等待内容消费下载器** —— 目标函数是「能力解锁时间」（边下边播 / 增量安装 / 按页预览），不是峰值速度。**不做通用下载器**（明确排除 BT/磁力/FTP、跨设备任务拆分、自定义传输协议）。

**技术栈**：Kotlin + Compose（Material3）+ Hilt + Room + OkHttp。UI 视觉与组件规范**对齐 AiCode**（`~/workspace/AiCode/app/src/main/java/com/aicode/core/theme/` 为主题唯一参考源，Yadown 侧按需拷贝适配，不另起风格）。

## R1 工作硬性要求（用户明示，最高优先级）

用户明示的硬性工作态度与流程要求，**任何改动前必读并逐条遵守**；优先级等同本文件其它规则，高于本 AI 的默认习惯。偏离本 R1 的改动必须先征得用户确认。

### R1.1 态度底线

- **绝对严谨、绝对正确**：以正向约束要求自身，**绝对禁止懒惰和懈怠**对待工作。
- **绝对真实准确且广泛**：工作必须建立在**真实、准确、广泛**的资料之上加以验证，不得凭印象/记忆/推测下结论。
- **数据规律 + 主观能动性**：尊重数据规律，并与自身**充分且正向有力的主观能动性与创造性**结合——证据链完整后主动设计方案，而非等待指令。
- **绝对准确、绝对高效、绝对认真**是最基本要求，不是加分项。

### R1.2 改动前的强制流程（根因先行）

1. **先找根因**：所有修改代码都要**事先找到绝对真实正确的根因**，合理分析。
2. **根因 → 方案转化**：把"问题真正所在"转化成"真正正确的方案"再实施，不允许对着症状打补丁。
3. **一切从优**：坚持**一切从优、一切从高质量最佳方案**着手，默认目标是最优解而非"能跑就行"。
4. 找不到根因不许改；改错了回到第 1 步重新找根因，不叠加补丁。

### R1.3 创新与权衡

- 学会在**广泛验证被认可的方法**上加以**合理的创新**：先确认业界/上游/官方推荐做法，再在其上改进。
- **既看到好处，又看到弊端**：任何方案同时列出优点、缺点、风险、成本。
- **学会权衡利弊**：给出选择该方案而非其它候选的显式理由。
- 不能只呈现"新方案好在哪"，必须也回答"新方案哪里可能翻车"。

### R1.4 局部最优与全局最优

- **各模块/子任务之间须存在紧密合乎逻辑的联系**：不是孤岛，互相配合。
- 既追求**各功能最优**，也追求**整体全局最优**。
- **以优异的各部分构成优异的整体，以整体彰显软件的优秀**：整体体验 > 单点炫技。
- **体现新事物的发展，随社会变化而变化**：架构与方案要能演进，避免一次性锁死。

### R1.5 外部条件敏感项（不可擅作主张）

- 涉及系统 API 行为、厂商 ROM 差异、下载协议细节等外部条件时，**紧跟官方文档与真机实测**，不允许凭历史记忆或直接沿用旧值。
- 遇到"官方变了但代码没跟上"：优先同步官方口径，再做优化叠加。

### R1.6 较大改动必须先询问

- **较大改动**必须**积极向用户询问，提供方案建议**，不能自作主张落地。
- 判断口径（可参照但不限于）：影响架构分层或公共契约（接口/schema）；影响持久化/协议/数据格式；影响外部依赖/供应商接入方式；影响 UI 视觉/交互大改；影响多文件（≥3）或跨模块。
- 做法：进入 PLAN 模式或用 askUserQuestion 给出 2~3 个候选方案 + 各自优劣 + 推荐，用户确认后再动手。

### R1.7 与既有约定的关系

- 与其它规则（R2-R9）及项目记忆既有约定不冲突，一起执行。
- 与本 R1 冲突时以本 R1 为准；边界不清先问用户。

## R2 四层架构（唯一规范源 `docs/ARCHITECTURE.md`）

```
L1 内容语义层  FormatResolver / AvailabilityContract / StreamPipe（边下边播/增量安装/按页预览）
L2 多源协同层  PeerDiscovery(mDNS) / LinkAggregator / SourceHealthRegistry
L3 传输收敛层  Scheduler(健康感知) / ChunkEngine(动态裂解) / RangeValidator(四重校验)
L4 系统生存层  LifecycleManager(UIDT/FGS/WM) / PersistenceManager(位图+交叉校验) / PlatformAdapter(ROM感知)
```

- 层间契约：L1→L2 `ContentDescriptor + ValueMap`；L2→L3 `SourceRoute + CostEstimate`；L3→L4 `ChunkState + FailureAttribution`；L4→所有层 `PlatformCapability`。
- **TelemetryStore 全层共享**：所有层读写同一份历史 trace。
- 核心机制：**位图意图 + 交叉校验**、**动态裂解**、**四重 Range 校验**、**UIDT**、**ROM 感知**。
- 架构讨论一律以 `docs/ARCHITECTURE.md` 为准，不另起炉灶。

## R3 数据模型与状态机（唯一规范源 `docs/ARCHITECTURE.md`）

- 六表：`intents` / `chunks` / `bitmap_sidecar` / `source_health` / `peer_index` / `platform_capability`（Room）。
- Chunk 状态机：`PENDING → RUNNING → DONE`；`RUNNING → FAILED → PENDING(重试<3) / INVALIDATED(重试≥3) → PENDING(换源)`；`DONE → PENDING(交叉校验发现空洞)`。
- Intent 状态机：`PENDING → ACTIVE → COMPLETED`；`ACTIVE → PAUSED → ACTIVE`；`ACTIVE → FAILED → PENDING(用户重试)`。
- 失败归因链：14 类 `FailureCause` + 恢复决策表，恢复决策由归因链驱动。
- **禁止**在 Kotlin 侧重复实现与文档冲突的状态机逻辑（防逻辑漂移）。

## R4 可观测性（指标基准见 `docs/ARCHITECTURE.md`）

- 核心产品指标：Time-to-First-Playable < 10s（10Mbps）、Time-to-First-Launchable < 30s（10Mbps）。
- 可靠性指标：分块恢复成功率 > 99.9%、单次会话有效率 > 0.95、Stall 率 < 1%。
- 任何新功能必须能回答「它如何被观测/验证」，不允许"看起来对"。

## R5 UI 规范（对齐 AiCode）

- 主题：以 AiCode `core/theme/`（AIEditorTheme.kt + AppThemePreset.kt）为参考，SemanticColors / Spacing / Radius 常量保持同构。
- 包结构：`com.yadown.core.*`（theme/db/net/security/util）+ `com.yadown.feature.*`（tasks/player/install/settings）。
- 交互：任务列表为一级界面，任务卡展示状态机（PENDING/RUNNING/PAUSED/COMPLETED/FAILED + 分块进度），进度用真实位图状态投影，不做假动画。

## R6 工程假设（必须先验证，否则降级）

| 假设 | 验证 | 降级 |
|---|---|---|
| 多链路聚合可行（Wi-Fi+蜂窝同时 Range） | P0/T0.1 | 单链路；L2 只保留 Peer + SourceHealth |
| mDNS 家庭网络可靠 | P0/T0.2 | 手动配对或移除 peer 功能 |
| Android 12 以下 APK 流式安装有可行降级 | P0/T0.3 | 流式安装仅 Android 12+，低版本先下完再装 |

**P0 三项验证未完成前，禁止进入 P1（传输收敛层）细节实现。**

## R7 分阶段交付（明细见 `ROADMAP.md`）

P0 三验证（1 周，必须先做）→ P1 传输收敛层（3 周，最独立可测）→ P2 系统生存层（2 周）→ P3 内容语义层（2 周，与 P2 并行）→ P4 多源协同层（3 周）→ P5 集成与真机验证（2 周）。

## R8 验证与测试

- 单元/合约测试为**可执行规范**：状态机、归因链、位图恢复必须有测试覆盖。
- 无法在容器内完整构建时，用可运行的替代验证（javac 编译检查、mock server 等）并明确标注验证边界（编译级 / 真机级）。
- **容器构建限制（已验证）**：容器内唯一可执行的 aapt2 是系统 `/usr/bin/aapt2`（2.19-debian，aarch64），它**无法解析 android-36 的资源表**（`Entry offset ... outside the Type's boundaries`）；SDK build-tools 34/35/36 的 aapt2 均为 x86-64，在 aarch64 容器无法执行。因此容器内无法完成 compileSdk 36 的 APK 构建。完整构建在 CI/真机（x86_64 + 新版 aapt2）执行；容器内以 `./gradlew :app:checkDebugAarMetadata` 与依赖图解析作为编译级验证。

## R9 仓库纪律

- 命名规范：`T<阶段>.<序号> <名称>`（如 T1.1 Chunk 状态机 + Room 持久化）。
- 不擅自 commit/push；改动多文件（≥3）或跨模块先按 R1.6 询问。
- `.gitignore` 忽略构建产物与本地配置（keystore、local.properties）。