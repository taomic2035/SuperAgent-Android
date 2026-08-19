#!/usr/bin/env bash
set -euo pipefail

# deploy-brain.sh — brain 打包部署半自动化（AD-08）
# 用法：
#   bash scripts/deploy-brain.sh            # 默认 --build：esbuild 打包 brain → dist/brain.mjs，输出 sha256
#   bash scripts/deploy-brain.sh --build    # 同上
#   bash scripts/deploy-brain.sh --device   # 构建 + adb push + run-as cp 两步送进 Termux ~/brain-lite/
# 前置：
#   --build  : node >= 20（在 brain/ 下执行 node build.mjs）
#   --device : 上述 + adb + 已连接设备（Termux 已安装，~/brain-lite/ 内已有 node_modules）
# 说明：
#   - dist/ 已在 .gitignore，产物不入库；重复执行安全（幂等：覆盖式打包/推送）。
#   - --device 路径写法：adb shell 中 ~ 不展开，必须用绝对路径
#     /data/data/com.termux/files/home/brain-lite/brain.mjs（Termux home 标准位置）；
#     先 push 到中转目录 /data/local/tmp/，再 run-as com.termux cp 进 Termux 私有目录
#     （base64 直传超长文件会失败，此为 SESSION.md 验证过的可靠通道）。

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRAIN="$ROOT/brain"
DIST="$BRAIN/dist/brain.mjs"

TERMUX_PKG="com.termux"
TERMUX_BRAIN="/data/data/$TERMUX_PKG/files/home/brain-lite/brain.mjs"
TMP_DST="/data/local/tmp/brain.mjs"

# Git Bash（MSYS2）会把以 / 开头的参数当 Windows 路径转换（/data/... → D:/Git/data/...），
# 禁止转换设备路径（2026-08-19 真机实测坑）
export MSYS2_ARG_CONV_EXCL="/data"

MODE="${1:---build}"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

# sha256_of <file> — 跨平台摘要（Git Bash/Linux 用 sha256sum，macOS 退回 shasum/openssl）
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    openssl dgst -sha256 "$1" | awk '{print $NF}'
  fi
}

do_build() {
  echo "== 1/2 esbuild 打包 brain =="
  (cd "$BRAIN" && node build.mjs)
  if [[ ! -s "$DIST" ]]; then
    echo "  [错误] 打包完成但 $DIST 不存在或为空" >&2
    exit 1
  fi
  echo "  产物: dist/brain.mjs（$(wc -c < "$DIST" | tr -d ' ') 字节）"
  echo "== 2/2 输出 sha256 =="
  echo "  $(sha256_of "$DIST")  brain.mjs"
  echo "== 完成（部署到设备: bash scripts/deploy-brain.sh --device）=="
}

do_device() {
  # 先构建，保证推的是最新代码
  do_build
  local_hash="$(sha256_of "$DIST")"

  echo "== 1/3 push 到设备中转目录 =="
  adb push "$DIST" "$TMP_DST"

  echo "== 2/3 run-as cp 进 Termux brain-lite =="
  # ~ 在 adb shell 中不展开 → 用绝对路径；cp 属主即 termux uid，无需再 chmod
  adb shell run-as "$TERMUX_PKG" cp "$TMP_DST" "$TERMUX_BRAIN"

  echo "== 3/3 远端摘要核验 =="
  # exec-out 为二进制安全通道（adb shell 可能污染字节流）
  adb exec-out run-as "$TERMUX_PKG" cat "$TERMUX_BRAIN" > "$TMP"
  remote_hash="$(sha256_of "$TMP")"
  if [[ "$remote_hash" != "$local_hash" ]]; then
    echo "  [错误] sha256 不一致：local=$local_hash remote=$remote_hash" >&2
    exit 1
  fi
  echo "  本地/远端 sha256 一致: $local_hash"
  echo "== 完成（Termux 侧: node ~/brain-lite/brain.mjs）=="
}

case "$MODE" in
  --build)  do_build ;;
  --device) do_device ;;
  *)
    echo "用法: bash scripts/deploy-brain.sh [--build|--device]" >&2
    exit 1
    ;;
esac
