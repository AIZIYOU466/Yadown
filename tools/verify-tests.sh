#!/bin/bash
# 容器内执行纯 JVM 单元测试（跳过依赖 Android 框架桩的用例）。
# 先编译，再用 JUnitCore 跑指定测试类。
set -uo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR/../android"

OUT=/tmp/yadown-verify
bash "$SCRIPT_DIR/verify-compile.sh" || true
if [ ! -d "$OUT/test-classes" ]; then
  echo "测试类未生成，终止" >&2
  exit 2
fi

CP=$(cat /tmp/yadown-cp.txt)
CP="$CP:$OUT/classes:$OUT/test-classes"

# 依赖 android.os.Build 桩的用例（RomDetectorTest）在容器里无法运行，其余均为纯 JVM。
CLASSES="
com.yadown.core.net.OkHttpRangeSourceTest
com.yadown.core.semantic.ValueMapTest
com.yadown.core.source.HostTuningPolicyTest
com.yadown.core.semantic.AvailabilityContractTest
com.yadown.core.semantic.Mp4FormatResolverTest
com.yadown.core.semantic.ApkFormatResolverTest
com.yadown.core.semantic.PdfFormatResolverTest
com.yadown.core.transfer.BitmapFileCodecTest
com.yadown.core.transfer.ChunkBitmapTest
com.yadown.core.transfer.ChunkSplitterTest
com.yadown.core.transfer.ChunkStateMachineTest
com.yadown.core.transfer.CrossValidatorTest
com.yadown.core.transfer.DispatcherPolicyTest
com.yadown.core.transfer.CompletionVerifierTest
com.yadown.core.transfer.DownloadEngineTest
com.yadown.core.transfer.MultiSourceDownloadTest
com.yadown.core.transfer.CrashRecoveryTest
com.yadown.core.transfer.EndToEndDownloadTest
com.yadown.core.bench.CapabilityBenchmarkTest
com.yadown.core.transfer.DownloadPlanTest
com.yadown.core.transfer.DownloadPlanPriorityTest
com.yadown.core.transfer.FailureRecoveryTest
com.yadown.core.transfer.HealthTrackerTest
com.yadown.core.transfer.IntentStateMachineTest
com.yadown.core.transfer.PlanFingerprintTest
com.yadown.core.transfer.RangeCoverageTest
com.yadown.core.transfer.RangeValidatorTest
com.yadown.core.transfer.StallWatchdogTest
com.yadown.core.transfer.XxHash64Test
com.yadown.core.transfer.TransferLimiterTest
com.yadown.core.transfer.DownloadEngineLinkTest
com.yadown.core.transfer.ChunkThroughputMonitorTest
com.yadown.core.transfer.ChunkSplitterAdaptiveTest
com.yadown.core.transfer.ChunkSplitPolicyTest
com.yadown.core.transfer.DownloadEngineSlowSplitTest
com.yadown.core.transfer.DynamicLimiterTest
com.yadown.core.transfer.SourceCircuitBreakerTest
com.yadown.core.transfer.DownloadEngineCircuitTest
com.yadown.core.transfer.WritePipelineTest
com.yadown.core.transfer.CommitCoordinatorTest
com.yadown.core.transfer.RangeReadySignalTest
com.yadown.core.transfer.AsyncPersistenceRecoveryTest
com.yadown.core.telemetry.TelemetryStoreTest
com.yadown.core.telemetry.TransferTelemetryTest
com.yadown.core.telemetry.SessionTelemetryTest
com.yadown.core.io.DownloadPathsTest
com.yadown.core.net.TopologyRangeSourceTest
com.yadown.core.net.Http1TopologyTest
com.yadown.core.net.TopologyConcurrencyTest
com.yadown.core.net.MultiConnectionThroughputTest
com.yadown.core.net.WarmupTest
com.yadown.core.survival.FsyncGateTest
com.yadown.core.survival.LifecycleManagerTest
com.yadown.core.survival.BatteryGuidanceTest
com.yadown.core.survival.RomDetectorTest
com.yadown.core.source.SourceHealthModelTest
com.yadown.core.source.RoomSourceHealthRegistryTest
com.yadown.core.source.SourceRouterTest
com.yadown.core.peer.PeerIndexTest
com.yadown.core.peer.PeerProtocolTest
com.yadown.core.peer.PeerCertificateTest
com.yadown.core.peer.PeerTrustStoreTest
com.yadown.core.peer.PeerChunkVerifierTest
com.yadown.core.peer.PeerTlsTest
com.yadown.core.peer.PeerServerTest
com.yadown.core.peer.PeerHandshakeTest
com.yadown.core.peer.PeerManifestClientTest
com.yadown.core.peer.PeerSourceProviderTest
com.yadown.core.peer.PeerDownloadIntegrationTest
com.yadown.core.net.LinkAllocatorTest
com.yadown.core.net.LinkHealthStoreTest
com.yadown.core.net.MultiLinkRangeSourceTest
com.yadown.core.net.ConnectionMetricsTest
com.yadown.core.net.ConnectionBudgetTest
"

java -cp "$CP" org.junit.runner.JUnitCore $CLASSES
