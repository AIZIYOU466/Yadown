# Yadown ROADMAP

> 依据 `docs/ARCHITECTURE.md` 第 7-8 节与 DeepSeek 定稿任务分解。所有验收标准必须可执行、可验证。P0 三项验证未完成前禁止进入 P1 细节实现。

## P0：三个工程假设验证（1 周，必须先做）

| 任务 | 验证内容 | 交付物 | 阻塞关系 |
|---|---|---|---|
| T0.1 | 多链路聚合可行性：ConnectivityManager 多网络绑定在目标 API（28/31/34）上是否可用 | 可行性报告 + demo 代码 | 阻塞 L2 LinkAggregator |
| T0.2 | mDNS 可靠性：家庭/办公网络下 peer 发现率和传输成功率 | 发现率数据 + 可靠性报告 | 阻塞 L2 PeerDiscovery |
| T0.3 | 低版本 APK 流式安装回退：Android 12 以下降级路径 | 降级方案文档 | 阻塞 L1 ApkSink |

**降级规则**：
- T0.1 失败：LinkAggregator 降级为单链路，多源协同层只保留 PeerDiscovery 和 SourceHealthRegistry。
- T0.2 失败：PeerDiscovery 降级为手动配对，或移除局域网 peer 功能。
- T0.3 失败：APK 流式安装仅支持 Android 12+，低版本走"先下完再安装"。

**第一周节奏**：
- Day 1-2：T0.1 多链路聚合验证
- Day 3-4：T0.2 mDNS 验证
- Day 5：T0.3 低版本 APK 安装验证
- Day 6-7：根据验证结果调整 P1 任务范围，开始 T1.1

---

## P1：传输收敛层（3 周，最独立、最可测）

| 任务 | 内容 | 依赖 | 验收标准 |
|---|---|---|---|
| T1.1 | Chunk 状态机 + Room 持久化 | 无 | 单元测试覆盖所有状态转换 |
| T1.2 | 位图侧车 + 批量提交 + 交叉校验 | T1.1 | 模拟掉电测试：位图与文件不一致时正确恢复 |
| T1.3 | Range 四重校验 | T1.1 | mock server 测试：200+空 body、ETag 变化、Content-Range 不匹配 |
| T1.4 | 动态裂解 + 原子事务 | T1.1 | 并发测试：裂解时断电不产生重叠分块 |
| T1.5 | 健康感知 Dispatcher + 存储 IOPS 联动 | T1.1 | 弱网模拟：8 条连接不产生重试风暴 |
| T1.6 | Stall watchdog + 失败归因链 | T1.1 | 注入故障：每类归因链正确记录 |

**P1 交付标准**：杀进程重启后，从位图恢复，不重传已完成分块；模拟弱网下，分块成功率 > 90%。

---

## P2：系统生存层（2 周）

| 任务 | 内容 | 依赖 | 验收标准 |
|---|---|---|---|
| T2.1 | PlatformAdapter：ROM/存储/网络/UIDT 探测 | 无 | 在 EMUI/MIUI/AOSP 上正确识别 |
| T2.2 | LifecycleManager：前台服务/UIDT/WorkManager 选择 | T2.1 | 各场景下选择正确的 Carrier |
| T2.3 | ROM 感知的权限引导 | T2.1 | EMUI 上引导后 Service 存活率 > 90% |
| T2.4 | 存储分层落盘（私有随机写 / 公共顺序迁移） | T2.1 | Scoped Storage 下公共目录正确迁移 |
| T2.5 | 全局 fsync 合并闸 | T1.2 | 64 并发下 fdatasync 次数 < 每 2 秒 1 次 |

---

## P3：内容语义层（2 周，与 P2 并行）

| 任务 | 内容 | 依赖 | 验收标准 |
|---|---|---|---|
| T3.1 | MP4 FormatResolver（moov 优先） | 无 | 10% 下载后可播放 |
| T3.2 | APK FormatResolver（Manifest 优先） | T0.3 | Android 12+ 可增量安装 |
| T3.3 | PDF FormatResolver（目录优先） | 无 | 5% 下载后可导航 |
| T3.4 | AvailabilityContract + UI 契约 | T3.1-3.3 | UI 正确渲染"现在可播"状态 |
| T3.5 | 未知格式降级 | 无 | 无 resolver 时顺序传输 |

---

## P4：多源协同层（3 周，依赖 P0 验证结果）

| 任务 | 内容 | 依赖 | 验收标准 |
|---|---|---|---|
| T4.1 | SourceHealthRegistry（域名连接上限学习） | T1.5 | 扩容崩塌时正确回滚 |
| T4.2 | PeerDiscovery（mDNS） | T0.2 | 家庭网络下发现率 > 80% |
| T4.3 | 局域网 HTTP 传输 + 内容哈希校验 | T4.2 | peer 传输成功率 > 95% |
| T4.4 | 多源路由（本地/peer/源站/镜像） | T4.1-4.3 | 成本-延迟-可靠性加权正确 |
| T4.5 | LinkAggregator（如 T0.1 通过） | T0.1 | Wi-Fi+蜂窝聚合吞吐 > 单链路 1.5x |

---

## P5：集成与真机验证（2 周）

| 任务 | 内容 | 验收标准 |
|---|---|---|
| T5.1 | 端到端集成测试 | 完整下载流程无崩溃 |
| T5.2 | 掉电恢复测试 | 100 次随机掉电，状态恢复正确率 > 99% |
| T5.3 | 弱网真机测试（地铁/电梯） | 完成率 > 80% |
| T5.4 | 国产 ROM 适配测试（华为/小米/OPPO/vivo） | Service 存活率 > 90% |
| T5.5 | 低端设备性能测试（eMMC） | 无 OOM、无 ANR |

### P5 真机验收清单（2026-11 增补）

- [ ] 首播：10Mbps 限速，MP4 moov 在头/尾各 3 个样本，记录 Time-to-First-Playable，目标 <10s。
- [ ] 完成完整率：构造 200 全量/416/429/截断 server，确认不产生 COMPLETED 空洞，目标 100%。
- [ ] 请求放大：1MB 分块永久失败，抓包确认单分块请求数 ≤15。
- [ ] 杀进程/掉电：write/fsync/bitmap/DB 四个断点各 25 次；恢复后必须通过完成校验或明确 FAILED，不得假完成。
- [ ] FGS 超时：`adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000`，确认 `onTimeout` 停止且无 `RemoteServiceException`。
- [ ] UIDT：Android 14/15/16 各一台，确认 JobService 启动、通知、暂停/恢复。
- [ ] ROM 存活：HyperOS/EMUI/ColorOS/OriginOS 后台 30 分钟存活率。
- [ ] 媒体库：Android 10+ 完成后公共 Downloads 可打开，私有副本仍在。
- [ ] 边下边播：暂停/失败/重启后打开播放器，30s 内给出错误或继续播放，不永久卡死。
- [ ] peer：确认未启用明文 HTTP；L2 进入前必须完成 HTTPS/内容签名信任模型评审。

---

## 任务状态

| 阶段 | 状态 | 备注 |
|---|---|---|
| 工程初始化（AGENTS/ARCHITECTURE/ROADMAP） | 已完成 | 2026-11-06 |
| Gradle 骨架（Kotlin+Compose+Hilt+Room） | 已完成 | UI 对齐 AiCode |
| 容器内 APK 构建 | 不可行 | aapt2 2.19 不支持 android-36 资源表，须 CI/真机构建（见 AGENTS.md R8） |
| CI 构建（GitHub Actions） | 持续验证 | ubuntu-latest 构建 + 单测 + debug APK artifact |
| P0/T0.1 多链路聚合 | 待真机 | 探针代码已就绪，待回填 |
| P0/T0.2 mDNS | 待真机 | 探针代码已就绪，待回填 |
| P0/T0.3 增量安装降级 | 待真机 | 已核实普通应用无法 `setStaged`，降级为「先下完再装」 |
| P1 传输收敛层（L3） | 已完成 | 状态机/Room/位图/四重 Range 校验 + **T1.4 动态裂解（原子替换）/ T1.5 健康感知并发 / T1.6 stall watchdog** 均已接入运行时引擎；196 个单测通过；**L3 不依赖 P0** |
| 正确性脊柱（完成谓词/提交协议/校验和/原子位图/子块身份） | 已完成 | 完成必须由覆盖+文件长度证明；DONE 前 fsync；位图带 fingerprint/magic/xxHash64 且原子替换；子块 index 从 DB 最大值继续；完成校验失败会把缺失区间退回 PENDING |
| 传输协议（先校验后读/顺序回退/416/429/弱 ETag） | 已完成 | 200 部分区间不读 body；206 读取上限 expected+1；416/429/非 identity 编码显式归因；非 Range 源走单流顺序下载；裂解预算按 retryCount 收敛 |
| 平台 Carrier（FGS onTimeout/UIDT/Worker 上限） | 已完成（编译级，真机待验证） | dataSync 6 小时超时调用 onTimeout 并停止；Android 14+ UIDT JobService + RUN_USER_INITIATED_JOBS；暂停取消 Worker/UIDT；Worker 最多 5 次尝试 |
| 系统治理与运行遥测 | 已完成（进程内 + 文件） | 全局 8 连接 / 每源 4 连接闸门；进度 200ms 节流；RangeCoverage 有序合并+二分；TransferTelemetry 聚合并写入 `filesDir/telemetry/events.ndjson` |
| Instrumentation 骨架 | 已加（CI 编译门槛） | androidTest：Room DAO 往返、MediaStore 发布、DownloadingDataSource 读取；CI 增加 `assembleDebugAndroidTest`；真机执行与 CI 首跑待确认 |
| UI 接入 | 已完成 | 真实任务流、新建任务、暂停/继续/重试、打开/删除 |
| P2 系统生存层 | 已完成（真机项待验证） | T2.1 PlatformAdapter 五项探测（ROM/UIDT/存储能力/网络可达性/存储加密）；T2.2 Carrier 选择已接入下载路径（前台 FGS / 后台 UIDT 或 WorkManager、Doze 停传）；T2.3 ROM 感知后台引导（小米/华为/OPPO/vivo 直达设置页 + 电池白名单）；T2.4 完成后公共媒体库发布（仅 Android 10+）；T2.5 FsyncGate；FGS `onTimeout` 与 UIDT JobService 已实现；存活率需真机测 |
| P3 内容语义层 | 已完成（真机项待验证） | **T3.1 MP4 moov 优先（可边下边播）/ T3.2 APK Manifest 优先 / T3.3 PDF xref+Root 优先 / T3.4 AvailabilityContract 驱动 UI 解锁 / T3.5 未知格式顺序传输**；语义优先调度只改排队不动分块边界；APK 受 T0.3 约束仍为「下完再装」；PDF 查看器未接入，暂不承诺部分预览 |
| P4 多源协同层 | 未开始（受门槛约束） | P0/P5 通过前不进入；peer 传输必须先定 HTTPS/内容签名信任模型，禁止明文 HTTP |
| P5 集成真机验证 | 未开始（清单已就绪） | 需回填首播时间、完成完整率、请求放大、掉电恢复、ROM 存活率；清单见 P5 节 |

*ROADMAP 版本：v1.0 — 2026-11-06*