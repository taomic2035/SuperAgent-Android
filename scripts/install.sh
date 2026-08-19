#!/usr/bin/env bash
set -euo pipefail

# install.sh — 一键装机：APK 安装 + token 桥接 + brain 打包/Termux 部署指引（AD-08）
# 前置：adb 已连接设备（adb devices 可见）
# 用法：bash scripts/install.sh [APK路径]

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="com.superagent.body"
APK="${1:-$ROOT/body/app/build/outputs/apk/debug/app-debug.apk}"

echo "== 1/3 安装 APK =="
if [ ! -f "$APK" ]; then
  echo "APK 不存在: $APK"
  echo "请先运行: cd body && ./gradlew :app:assembleDebug"
  exit 1
fi
adb install -r "$APK"
echo "  APK 已安装"

echo "== 2/3 桥接 token =="
TOKEN=$(adb shell run-as "$PKG" cat files/token 2>/dev/null || echo "")
if [ -z "$TOKEN" ]; then
  echo "  token 文件不存在（首次安装需先启动 App 一次生成 token）"
  echo "  请在手机上打开'超级AI助手'→ 启动躯体服务，然后重跑本脚本"
  exit 1
fi
echo "  TOKEN=$TOKEN"
echo "  在 Termux 中设置: export BODY_TOKEN=$TOKEN"

echo "== 3/3 brain 打包与 Termux 部署（AD-08）=="
DEPLOY="$ROOT/scripts/deploy-brain.sh"
if [ -f "$DEPLOY" ]; then
  echo "  PC 侧打包 brain（见 scripts/deploy-brain.sh）："
  bash "$DEPLOY" --build || echo "  [警告] brain 打包失败，不影响已完成步骤；可稍后重试: bash scripts/deploy-brain.sh --build"
else
  echo "  [跳过] 未找到 scripts/deploy-brain.sh，本步骤不阻塞；获取后可单独执行: bash scripts/deploy-brain.sh --build"
fi
echo ""
echo "  Termux 侧（AD-08：brain.mjs 为 esbuild ESM bundle，pi 依赖 external，放 ~/brain-lite/ 运行）："
echo "  1. 安装 Termux (F-Droid 版)，pkg install nodejs"
echo "  2. Termux 内准备依赖：mkdir -p ~/brain-lite && cd ~/brain-lite && npm install @earendil-works/pi-agent-core @earendil-works/pi-ai typebox"
echo "  3. PC 侧把产物送进设备：bash scripts/deploy-brain.sh --device   （需 adb；push 到 /data/local/tmp 再 run-as cp，~ 在 adb shell 不展开）"
echo "  4. Termux 内：export BODY_TOKEN=$TOKEN"
echo "  5. Termux 内：export GLM_API_KEY=你的key"
echo "  6. Termux 内：cd ~/brain-lite && node brain.mjs   （VOICE_MODE=1 开语音模式）"
echo ""
echo "== 完成 =="
echo "  手机上：开启无障碍服务（设置→无障碍→超级AI助手）"
echo "  通知栏点击'说话'按钮开始语音对话"
