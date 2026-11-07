#!/bin/bash
# 能力解锁基准：编译后单独跑 CapabilityBenchmarkTest，打印并保留 JSON 结果。
# 与 verify-tests.sh 共用 /tmp/yadown-verify 与 /tmp/yadown-cp.txt。
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

java -cp "$CP" org.junit.runner.JUnitCore com.yadown.core.bench.CapabilityBenchmarkTest
STATUS=$?

echo "== benchmark result =="
if [ -f build/reports/yadown-benchmark.json ]; then
  cat build/reports/yadown-benchmark.json
else
  echo "未生成 build/reports/yadown-benchmark.json" >&2
fi

exit $STATUS
