# P0 工程假设验证

> 依据 `docs/ARCHITECTURE.md` 第 8 节与 `ROADMAP.md` P0。**P0 未完成前禁止进入 P1 细节实现。**

三项假设决定 L1/L2 的实现范围，不改变架构骨架。每项都要求「真机实测数据 + 判定结论 + 降级路径」。

## 验证边界声明

- 本环境（PRoot 容器）**无法运行 Android 真机验证**：三项均依赖 `ConnectivityManager` / `NsdManager` / `PackageInstaller` 等系统服务。
- 因此本目录交付的是**可编译到 APK 的探针代码 + 真机执行步骤 + 记录表**；结论须在真机（目标 API 28/31/34）上跑完后回填。
- 已完成的验证层级会在报告里显式标注：`编译级` / `真机级`。

## T0.1 多链路聚合

**假设**：在目标 API 上绑定 Wi-Fi 和蜂窝，同时发起两个 Range 请求，可同时从两条链路获取数据。

**代码**：`T0.1_MultiLink/MultiLinkProbe.kt`

**步骤**：
1. 设备同时开启 Wi-Fi 与移动数据。
2. 调 `MultiLinkProbe.probe(url)`，分别请求 Wi-Fi 与蜂窝网络的 `Network`，各发一个 `Range: bytes=0-1023` 请求。
3. 对比单链路与双链路的字节到达时间与吞吐。

**判定**：
- 双链路吞吐 ≥ 单链路 1.5x → 通过
- 只能单链路 → 失败，按架构降级为单链路

**记录表**：

| API | Wi-Fi 吞吐 | 蜂窝吞吐 | 双链路吞吐 | 单链路吞吐 | 结论 |
|---|---|---|---|---|---|
| 28 | | | | | |
| 31 | | | | | |
| 34 | | | | | |

## T0.2 mDNS 可靠性

**假设**：家庭/办公网络下 mDNS peer 发现率和传输成功率可用。

**代码**：`T0.2_Mdns/MdnsProbe.kt`

**步骤**：
1. 两台设备连同一路由，各自 `registerProbe()` 注册 `_yadown._tcp`。
2. 另一台 `discoverProbe()` 记录发现耗时与成功率。
3. 在 5 种常见路由器（家用双频、公司 AP、手机热点、Mesh、老旧路由）下各跑 10 轮。

**判定**：
- 发现率 > 80% → 通过
- 否则降级为手动配对或移除 peer 功能

**记录表**：

| 路由器 | 轮数 | 发现成功 | 平均耗时 | 结论 |
|---|---|---|---|---|
| 家用双频 | | | | |
| 公司 AP | | | | |
| 手机热点 | | | | |
| Mesh | | | | |
| 老旧路由 | | | | |

## T0.3 低版本 APK 流式安装降级

**假设**：Android 12 以下存在可接受的增量安装降级路径。

**已核实事实（2026-11-06）**：`PackageInstaller.SessionParams.setStaged()` 是 `@SystemApi @TestApi @hide`，普通应用**无法调用**；公开 API 只有 `PackageInstaller.Session.isStaged()`（API 29+）。因此对普通应用而言 staged/incremental install 不可直接使用，降级路径为「先下载完成后安装」。T0.3 的验证重点转为：测量「先下载完成后安装」的 Time-to-First-Launchable 是否可接受。

**代码**：`T0.3_IncrementalInstall/IncrementalInstallProbe.kt`

**步骤**：
1. 在 API 31+ 检查 `PackageInstaller.SessionParams` 的增量/暂存能力。
2. 在 API 26-30 检查是否只能「先下完再安装」。
3. 对比两种路径的 Time-to-First-Launchable。

**判定**：
- API 31+ 支持增量安装、低版本降级体验可接受 → 通过
- 低版本体验不可接受 → 流式安装仅支持 Android 12+，低版本走先下完再装

**记录表**：

| API 范围 | 增量安装 | Time-to-First-Launchable | 结论 |
|---|---|---|---|
| 31+ | | | |
| 26-30 | | | |

---

## P0 结论汇总（真机回填）

| 任务 | 状态 | 结论 | 影响的阶段 |
|---|---|---|---|
| T0.1 | 未验证 | | L2 LinkAggregator |
| T0.2 | 未验证 | | L2 PeerDiscovery |
| T0.3 | 未验证 | | L1 ApkSink |