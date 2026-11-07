#!/bin/bash
# 容器内 Kotlin 编译验证：绕过 processDebugResources（系统 aapt2 无法解析 android-36 资源表）。
# 用 Gradle 缓存里的 Kotlin 2.2.21 编译器直接编译 main + test 源集。
# classpath 的收集结果缓存在 /tmp/yadown-cp.txt，避免每次重新扫描整个 Gradle 缓存。
set -euo pipefail
cd "$(dirname "$0")/../android"

GRADLE_CACHE=/root/.gradle/caches/modules-2/files-2.1
OUT=/tmp/yadown-verify
KC=/tmp/ktc/kc.jar
CP_CACHE=/tmp/yadown-cp.txt

if [ ! -f "$KC" ]; then
  echo "缺少 Kotlin 2.2.21 编译器：/tmp/ktc/kc.jar" >&2
  exit 2
fi

STDLIB=$(find "$GRADLE_CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2.2.21.jar' | head -1)
TROVE=$(find "$GRADLE_CACHE/org.jetbrains.intellij.deps/trove4j" -name 'trove4j-*.jar' | head -1)
ANDROID_JAR=$(find /root/android-sdk/platforms -name android.jar | sort | tail -1)
COMPILER_CP="$KC:$STDLIB:$TROVE:/tmp/ktc/reflect.jar:$(find "$GRADLE_CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2" -name '*.jar' | grep -v sources | head -1):$(find "$GRADLE_CACHE/org.jetbrains/annotations/23.0.0" -name 'annotations-*.jar' | head -1)"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/test-classes" "$OUT/gen"

if [ ! -f "$CP_CACHE" ]; then
  echo "== building classpath (first run) =="
  CP="$STDLIB:$ANDROID_JAR"
  for j in $(find "$GRADLE_CACHE" -name '*.jar' \
    \( -path '*kotlinx-serialization*' -o -path '*androidx.media3*' -o -path '*okhttp*' \
       -o -path '*okio*' -o -path '*androidx.room*' -o -path '*androidx.work*' \
       -o -path '*androidx.lifecycle*' -o -path '*androidx.core*' -o -path '*androidx.activity*' \
       -o -path '*androidx.annotation*' -o -path '*androidx.hilt*' -o -path '*androidx.compose*' \
       -o -path '*com.google.dagger*' -o -path '*javax.inject*' -o -path '*androidx.navigation*' \
       -o -path '*androidx.savedstate*' -o -path '*androidx.emoji2*' -o -path '*androidx.tracing*' \
       -o -path '*androidx.startup*' -o -path '*androidx.vectordrawable*' -o -path '*androidx.interpolator*' \
       -o -path '*androidx.cursoradapter*' -o -path '*androidx.documentfile*' -o -path '*androidx.customview*' \
       -o -path '*androidx.dynamicanimation*' -o -path '*androidx.loader*' -o -path '*androidx.fragment*' \
       -o -path '*androidx.arch.core*' -o -path '*androidx.collection*' -o -path '*androidx.concurrent*' \
       -o -path '*androidx.autofill*' -o -path '*androidx.legacy*' \
       -o -path '*androidx.localbroadcastmanager*' -o -path '*androidx.exifinterface*' \) 2>/dev/null); do
    case "$j" in *-sources.jar|*-javadoc.jar) continue;; esac
    CP="$CP:$j"
  done
  # 协程只取 1.10.2，旧版本会让 collect 被解析成内部 API。
  for j in $(find "$GRADLE_CACHE" -name 'kotlinx-coroutines-*-1.10.2.jar' 2>/dev/null); do
    case "$j" in *-sources.jar|*-javadoc.jar) continue;; esac
    CP="$CP:$j"
  done
  # 测试库从本地缓存缺失时用显式路径补上。
  [ -f /tmp/ktc/coroutines-test.jar ] && CP="$CP:/tmp/ktc/coroutines-test.jar"
  # AAR 里的 classes.jar 由 Gradle transform 解出；9.7.1 是其他工程的缓存，不能混入。
  for j in $(find /root/.gradle/caches/8.14.3/transforms /root/.gradle/caches/8.14/transforms \
               /root/.gradle/caches/transforms-4 \
               -name 'classes.jar' 2>/dev/null); do
    CP="$CP:$j"
  done
  CP="$CP:$(find "$GRADLE_CACHE/junit" -name 'junit-4*.jar' | head -1)"
  CP="$CP:$(find "$GRADLE_CACHE/org.hamcrest" -name 'hamcrest-core-*.jar' | head -1)"
  # 过滤掉其他 Kotlin 版本编译的库（元数据版本高于本工程编译器会直接报错）。
  CP=$(printf '%s' "$CP" | tr ':' '\n' | grep -v -e 'kaverit' -e 'kodein' | paste -sd ':' -)
  printf '%s' "$CP" > "$CP_CACHE"
else
  CP=$(cat "$CP_CACHE")
fi

# BuildConfig 由 Gradle 生成为 .java，先编译。
for gen in $(find app/build/generated/source/buildConfig -name 'BuildConfig.java' 2>/dev/null); do
  javac -d "$OUT/gen" -classpath "$ANDROID_JAR" "$gen" 2>/dev/null || true
done
CP="$CP:$OUT/gen"

MAIN_SRC=$(find app/src/main/java -name '*.kt' | tr '\n' ' ')
TEST_SRC=$(find app/src/test/java -name '*.kt' | tr '\n' ' ')
# Compose 编译器插件：Kotlin 2.x 的 Compose 代码必须经过它才能生成字节码。
# kotlinx.serialization 也需要编译器插件生成 serializer，Gradle 构建由插件自动加载，
# 独立编译器则要显式加 -Xplugin。
COMPOSE_PLUGIN=/tmp/ktc/compose-plugin.jar
SERIALIZATION_PLUGIN=$(find /root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable -name '*.jar' 2>/dev/null | head -1)
PLUGIN_ARG=""
if [ -f "$COMPOSE_PLUGIN" ]; then PLUGIN_ARG="$PLUGIN_ARG -Xplugin=$COMPOSE_PLUGIN"; fi
if [ -n "$SERIALIZATION_PLUGIN" ]; then PLUGIN_ARG="$PLUGIN_ARG -Xplugin=$SERIALIZATION_PLUGIN"; fi

echo "== compiling main =="
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath "$CP" -d "$OUT/classes" -jvm-target 17 -no-stdlib -no-reflect -nowarn $PLUGIN_ARG $MAIN_SRC 2>&1 | tail -60

echo "== compiling tests =="
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath "$CP:$OUT/classes" -d "$OUT/test-classes" -jvm-target 17 -no-stdlib -no-reflect -nowarn $PLUGIN_ARG $TEST_SRC 2>&1 | tail -60

echo "== compile done =="
