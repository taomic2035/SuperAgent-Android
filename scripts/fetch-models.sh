#!/usr/bin/env bash
set -euo pipefail

# fetch-models.sh — 下载 sherpa-onnx v1.13.2 预编译 .so + 三语音模型（带 sha256 校验）
# 用法：
#   bash scripts/fetch-models.sh              # 全量下载 + 校验
#   bash scripts/fetch-models.sh --verify-only  # 只校验本地已就位文件（不下载）
# 前置：curl、tar（支持 .bz2）
# 输出：
#   third/sherpa-onnx/jniLibs/arm64-v8a/*.so
#   body/app/src/main/assets/sherpa/models/<model>/

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$ROOT/third/sherpa-onnx/jniLibs/arm64-v8a"
ASSETS="$ROOT/body/app/src/main/assets/sherpa/models"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

SHERPA_VER="v1.13.2"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download"

# 期望 sha256（2026-08-19 官方 release 实测，升级模型版本时必须更新）。
# 注意：键用普通数组（关联数组键含 - / 会被 bash 算术求值，set -u 下报 unbound）
MODEL_FILES=(
  "sensevoice-ctc-int8-zh/model.onnx"
  "kokoro-multi-lang-v1_0/model.onnx"
  "kokoro-multi-lang-v1_0/voices.bin"
  "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
)
MODEL_SHA256=(
  "C71F0CE00BEC95B07744E116345E33D8CBBE08CEF896382CF907BF4B51A2CD51"
  "C436DC6A842B62ABA06AF67E40BAFCFB9C60AC3AF895358F1974AD9A7F7C026B"
  "8A77C0D397026208D22211F37670B5B3B11E03F190756B25A1D24041FCED82A9"
  "1A331345F04805BADBB495C775A6DDFFCDD1A732567D5EC8B3D5749E3C7A5E4B"
)

MODE="${1:---download}"

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

# expect_sha256 <assets 相对路径> — 按路径查期望 sha256（MODEL_FILES/MODEL_SHA256 平行数组）
expect_sha256() {
  local rel="$1"
  for i in "${!MODEL_FILES[@]}"; do
    if [[ "${MODEL_FILES[$i]}" == "$rel" ]]; then
      echo "${MODEL_SHA256[$i]}"
      return 0
    fi
  done
  return 1
}

# verify_asset <assets 相对路径> — sha256 校验，不匹配即报错退出（列出期望 vs 实际）
verify_asset() {
  local rel="$1"
  local file="$ASSETS/$rel"
  local expect
  expect="$(expect_sha256 "$rel" || true)"
  if [[ ! -f "$file" ]]; then
    echo "  [错误] 文件缺失: $rel" >&2
    exit 1
  fi
  if [[ -z "$expect" ]]; then
    echo "  [错误] $rel 未登记期望 sha256（MODEL_FILES/MODEL_SHA256 数组）" >&2
    exit 1
  fi
  local actual
  actual="$(sha256_of "$file")"
  if [[ "${actual,,}" != "${expect,,}" ]]; then
    echo "  [错误] $rel sha256 校验失败：" >&2
    echo "    期望: $expect" >&2
    echo "    实际: $actual" >&2
    exit 1
  fi
  echo "  ✓ $rel"
}

# zh voices 确定性断言（voices.bin 内嵌音色名 zf/zm；不退出，仅警告 + Plan B）
verify_zh_voices() {
  local vb="$ASSETS/kokoro-multi-lang-v1_0/voices.bin"
  if [[ ! -f "$vb" ]]; then
    echo "  [错误] voices.bin 缺失" >&2
    exit 1
  fi
  if grep -qa "zf" "$vb" || grep -qa "zm" "$vb"; then
    echo "  ✓ Kokoro zh voices 已确认（zf/zm 命中）"
  else
    echo "  [警告] voices.bin 中未检测到中文声线（zf/zm），TTS 中文可能不可用"
    echo "  Plan B: 使用 vits-zh-hf-fanchen 模型"
  fi
}

# 只校验本地已就位文件（不下载、不碰网络）
verify_only() {
  echo "== --verify-only：本地校验已就位文件 =="
  for rel in "${MODEL_FILES[@]}"; do
    verify_asset "$rel"
  done
  verify_zh_voices
  echo "== 校验全部通过（4 模型产物 + zh voices）=="
}

do_download() {
  echo "== 1/4 下载 sherpa-onnx $SHERPA_VER Android 预编译 =="
  curl -L "$BASE/$SHERPA_VER/sherpa-onnx-$SHERPA_VER-android.tar.bz2" -o "$TMP/sherpa-android.tar.bz2"
  tar xf "$TMP/sherpa-android.tar.bz2" -C "$TMP"
  mkdir -p "$JNI_DIR"
  cp "$TMP"/sherpa-onnx-android/jniLibs/arm64-v8a/libonnxruntime.so "$JNI_DIR/"
  cp "$TMP"/sherpa-onnx-android/jniLibs/arm64-v8a/libsherpa-onnx-jni.so "$JNI_DIR/"
  echo "  .so 已就位: $(ls -la "$JNI_DIR"/*.so | wc -l) 个"

  echo "== 2/4 下载 SenseVoice ASR 模型 =="
  mkdir -p "$ASSETS/sensevoice-ctc-int8-zh"
  curl -L "$BASE/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2" -o "$TMP/sense.tar.bz2"
  tar xf "$TMP/sense.tar.bz2" -C "$TMP"
  cp "$TMP"/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/model.int8.onnx "$ASSETS/sensevoice-ctc-int8-zh/model.onnx"
  cp "$TMP"/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tokens.txt "$ASSETS/sensevoice-ctc-int8-zh/"
  verify_asset "sensevoice-ctc-int8-zh/model.onnx"
  echo "  SenseVoice: $(du -sh "$ASSETS/sensevoice-ctc-int8-zh" | cut -f1)"

  echo "== 3/4 下载 Kokoro TTS 多语言模型 =="
  mkdir -p "$ASSETS/kokoro-multi-lang-v1_0"
  curl -L "$BASE/tts-models/kokoro-multi-lang-v1_0.tar.bz2" -o "$TMP/kokoro.tar.bz2"
  tar xf "$TMP/kokoro.tar.bz2" -C "$TMP"
  KOKORO_DIR=$(find "$TMP" -maxdepth 1 -name "kokoro*" -type d | head -1)
  cp "$KOKORO_DIR"/model.onnx "$ASSETS/kokoro-multi-lang-v1_0/"
  cp "$KOKORO_DIR"/tokens.txt "$ASSETS/kokoro-multi-lang-v1_0/"
  cp "$KOKORO_DIR"/voices.bin "$ASSETS/kokoro-multi-lang-v1_0/"
  cp -r "$KOKORO_DIR"/espeak-ng-data "$ASSETS/kokoro-multi-lang-v1_0/"
  verify_asset "kokoro-multi-lang-v1_0/model.onnx"
  verify_asset "kokoro-multi-lang-v1_0/voices.bin"
  verify_zh_voices
  echo "  Kokoro: $(du -sh "$ASSETS/kokoro-multi-lang-v1_0" | cut -f1)"

  mkdir -p "$ASSETS/silero_vad"
  curl -L "$BASE/asr-models/silero_vad.onnx" -o "$ASSETS/silero_vad/model.onnx"
  echo "  Silero VAD: $(du -sh "$ASSETS/silero_vad" | cut -f1)"
  echo "== 5/5 下载 3D-Speaker 声纹模型 =="
  curl -L "$BASE/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" \
    -o "$ASSETS/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
  verify_asset "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
  echo "  3D-Speaker: $(du -sh "$ASSETS/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" | cut -f1)"

  echo ""
  echo "== 完成 =="
  echo "  .so → third/sherpa-onnx/jniLibs/arm64-v8a/"
  echo "  模型 → body/app/src/main/assets/sherpa/models/"
  echo "  下一步: cd body && ./gradlew :app:assembleDebug"
}

case "$MODE" in
  --verify-only) verify_only ;;
  --download)    do_download ;;
  *)
    echo "用法: bash scripts/fetch-models.sh [--download|--verify-only]" >&2
    exit 1
    ;;
esac
