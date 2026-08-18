#!/usr/bin/env bash
set -euo pipefail

# fetch-models.sh — 下载 sherpa-onnx v1.13.2 预编译 .so + 三语音模型
# 用法：bash scripts/fetch-models.sh
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
# zh voices 断言
if ! xxd "$ASSETS/kokoro-multi-lang-v1_0/voices.bin" | grep -q "zf\|zm"; then
  echo "  [警告] voices.bin 中未检测到中文声线（zf/zm），TTS 中文可能不可用"
  echo "  Plan B: 使用 vits-zh-hf-fanchen 模型"
else
  echo "  Kokoro zh voices: 已确认"
fi
echo "  Kokoro: $(du -sh "$ASSETS/kokoro-multi-lang-v1_0" | cut -f1)"

echo "== 4/4 下载 3D-Speaker 声纹模型 =="
curl -L "$BASE/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" \
  -o "$ASSETS/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
echo "  3D-Speaker: $(du -sh "$ASSETS/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" | cut -f1)"

echo ""
echo "== 完成 =="
echo "  .so → third/sherpa-onnx/jniLibs/arm64-v8a/"
echo "  模型 → body/app/src/main/assets/sherpa/models/"
echo "  下一步: cd body && ./gradlew :app:assembleDebug"
