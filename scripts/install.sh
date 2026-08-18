#!/usr/bin/env bash
set -euo pipefail

# install.sh — 一键装机：APK 安装 + token 桥接 + Termux bootstrap
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

echo "== 3/3 Termux bootstrap 提示 =="
echo "  1. 安装 Termux (F-Droid 版)"
echo "  2. pkg install nodejs git"
echo "  3. git clone <repo> && cd super-agent/brain && npm install"
echo "  4. export BODY_TOKEN=$TOKEN"
echo "  5. export GLM_API_KEY=你的key"
echo "  6. VOICE_MODE=1 npm run start  (语音模式)"
echo "  7. 或 npm run start            (REPL 模式)"
echo ""
echo "== 完成 =="
echo "  手机上：开启无障碍服务（设置→无障碍→超级AI助手）"
echo "  通知栏点击'说话'按钮开始语音对话"
