#!/usr/bin/env bash
set -euo pipefail

# rpc-concurrency-test.sh — BD-01.1 并发验证：≥4 并发 RPC 不死锁（docs/06 规格）
# 前置：adb forward tcp:8765 tcp:8765 已设置；body 服务运行中
# 用法：bash scripts/rpc-concurrency-test.sh [并发数，默认 8] [TOKEN（默认从设备读取）]
# 判据：全部请求 35s 内返回且 ok=true；无请求挂死；结束后 /health 仍 ok

N="${1:-8}"
TOKEN="${2:-$(adb shell run-as com.superagent.body cat files/token | tr -d '\r\n')}"
BASE="http://127.0.0.1:8765"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "== 并发 $N 混合请求（perceive.screen / health）=="
START=$(date +%s)
for i in $(seq 1 "$N"); do
  if (( i % 2 == 0 )); then
    curl -s -m 35 -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -d "{\"id\":$i,\"method\":\"perceive.screen\",\"params\":{}}" "$BASE/rpc" > "$OUT/r-$i.json" &
  else
    curl -s -m 35 -H "Authorization: Bearer $TOKEN" "$BASE/health" > "$OUT/r-$i.json" &
  fi
done
wait
ELAPSED=$(( $(date +%s) - START ))

FAIL=0
for i in $(seq 1 "$N"); do
  if grep -q '"ok" *: *true' "$OUT/r-$i.json"; then
    echo "  [$i] ok"
  else
    echo "  [$i] FAIL: $(head -c 200 "$OUT/r-$i.json")"
    FAIL=1
  fi
done
echo "耗时 ${ELAPSED}s（应 < 35s）"

# 尾部健康：并发结束后服务仍可用
if curl -s -m 5 -H "Authorization: Bearer $TOKEN" "$BASE/health" | grep -q '"ok" *: *true'; then
  echo "== 尾部 health ok =="
else
  echo "== 尾部 health FAIL =="
  FAIL=1
fi

if (( FAIL )); then echo "结果：FAIL"; exit 1; fi
echo "结果：PASS（$N 并发无死锁）"
