#!/bin/bash
# 容器内 aapt2 2.19 只能解析到 android-34 的资源表，加载 android-36 会段错误。
# 本脚本把 android-36/android.jar 里的 resources.arsc 换成 android-34 的，
# 保留 36 的类文件，使容器内 Gradle 能完成资源链接与打包。
#
# 仅用于容器内构建/验证；CI 与发布使用官方 x86_64 SDK，不要执行本脚本。
# 用法：container-android36-resources.sh apply|restore|status
set -euo pipefail

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/root/android-sdk}}
J36="$SDK/platforms/android-36/android.jar"
J34="$SDK/platforms/android-34/android.jar"
BACKUP="$J36.orig"

usage() {
    echo "用法: $0 apply|restore|status" >&2
    exit 2
}

require_files() {
    [ -f "$J36" ] || { echo "缺少 $J36" >&2; exit 1; }
    [ -f "$J34" ] || { echo "缺少 $J34" >&2; exit 1; }
}

sha_of_arsc() {
    python3 - "$1" <<'PY'
import hashlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    print(hashlib.sha256(z.read('resources.arsc')).hexdigest())
PY
}

case "${1:-status}" in
    apply)
        require_files
        if [ ! -f "$BACKUP" ]; then
            cp -p "$J36" "$BACKUP"
            echo "已备份原始 android-36/android.jar -> $BACKUP"
        fi
        python3 - "$J34" "$J36" <<'PY'
import shutil, sys, zipfile
src34, dst36 = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src34) as z:
    arsc = z.read('resources.arsc')
tmp = dst36 + '.tmp'
with zipfile.ZipFile(dst36) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = arsc if item.filename == 'resources.arsc' else zin.read(item.filename)
        zout.writestr(item, data)
shutil.move(tmp, dst36)
print('resources.arsc 已替换为 android-34 版本')
PY
        if command -v aapt2 >/dev/null 2>&1; then
            aapt2 dump resources "$J36" >/dev/null 2>/tmp/aapt2-resource-check.err \
                || { echo "aapt2 校验失败：" >&2; head -5 /tmp/aapt2-resource-check.err >&2; exit 1; }
            echo "aapt2 校验通过"
        fi
        ;;
    restore)
        require_files
        [ -f "$BACKUP" ] || { echo "没有备份 $BACKUP，无法恢复" >&2; exit 1; }
        cp -p "$BACKUP" "$J36"
        echo "已从 $BACKUP 恢复原始 android-36/android.jar"
        ;;
    status)
        require_files
        if [ ! -f "$BACKUP" ]; then
            echo "未打过补丁（无备份文件）"
            exit 0
        fi
        if [ "$(sha_of_arsc "$J36")" = "$(sha_of_arsc "$J34")" ]; then
            echo "已应用补丁：android-36 使用 android-34 资源表"
        else
            echo "未应用补丁：android-36 使用自身资源表"
        fi
        ;;
    *)
        usage
        ;;
esac
