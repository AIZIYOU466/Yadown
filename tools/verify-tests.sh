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
com.yadown.core.semantic.ValueMapTest
com.yadown.core.semantic.AvailabilityContractTest
com.yadown.core.semantic.FormatResolverRegistryTest
com.yadown.core.semantic.Mp4FormatResolverTest
com.yadown.core.semantic.ApkFormatResolverTest
com.yadown.core.semantic.PdfFormatResolverTest
com.yadown.core.transfer.ChunkBitmapTest
com.yadown.core.transfer.ChunkSplitterTest
com.yadown.core.transfer.ChunkStateMachineTest
com.yadown.core.transfer.CrossValidatorTest
com.yadown.core.transfer.DispatcherPolicyTest
com.yadown.core.transfer.DownloadEngineTest
com.yadown.core.transfer.DownloadPlanTest
com.yadown.core.transfer.DownloadPlanPriorityTest
com.yadown.core.transfer.FailureRecoveryTest
com.yadown.core.transfer.HealthTrackerTest
com.yadown.core.transfer.IntentStateMachineTest
com.yadown.core.transfer.RangeCoverageTest
com.yadown.core.transfer.RangeValidatorTest
com.yadown.core.transfer.StallWatchdogTest
com.yadown.core.survival.FsyncGateTest
com.yadown.core.survival.LifecycleManagerTest
com.yadown.core.survival.BatteryGuidanceTest
com.yadown.core.survival.RomDetectorTest
"

java -cp "$CP" org.junit.runner.JUnitCore $CLASSES
