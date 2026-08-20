#!/usr/bin/env bash
set -euo pipefail

# benchmark.sh — NFR 性能基准（docs/08 §1.1 指标）
# 前置：adb forward tcp:8765 + body 运行中 + TOKEN 已设
# 用法：BODY_TOKEN=xxx bash scripts/benchmark.sh

BASE="http://127.0.0.1:8765"
TOKEN="${BODY_TOKEN:?设置 BODY_TOKEN}"
RESULTS="benchmark-results-$(date +%Y%m%d-%H%M%S).txt"

rpc() {
  local method=$1 params=$2 timeout=${3:-10000}
  local start=$(python3 -c "import time; print(int(time.time()*1000))")
  local resp=$(curl -s -m $((timeout/1000)) -X POST \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"id\":1,\"method\":\"$method\",\"params\":$params}" "$BASE/rpc")
  local end=$(python3 -c "import time; print(int(time.time()*1000))")
  echo "$((end-start))"
}

echo "== SuperAgent 性能基准 $(date) ==" | tee "$RESULTS"

# 1. perceive 延迟（P0 目标 ≤200ms，测 5 次取中位数）
echo -e "\n--- perceive.screen 延迟（目标 ≤200ms）---" | tee -a "$RESULTS"
TIMES=()
for i in $(seq 1 5); do
  MS=$(rpc "perceive.screen" '{"mode":"auto"}')
  TIMES+=($MS)
  echo "  第${i}次: ${MS}ms"
done
SORTED=($(printf '%s\n' "${TIMES[@]}" | sort -n))
MEDIAN=${SORTED[2]}
echo "  中位数: ${MEDIAN}ms $([ $MEDIAN -le 200 ] && echo '✓ PASS' || echo '✗ FAIL')" | tee -a "$RESULTS"

# 2. RPC 往返延迟（control.back，目标 ≤500ms）
echo -e "\n--- control.back RPC 往返（目标 ≤500ms）---" | tee -a "$RESULTS"
MS=$(rpc "control.back" '{}')
echo "  ${MS}ms $([ $MS -le 500 ] && echo '✓ PASS' || echo '✗ FAIL')" | tee -a "$RESULTS"

# 3. health 响应（目标 ≤100ms）
echo -e "\n--- /health 响应（目标 ≤100ms）---" | tee -a "$RESULTS"
START=$(python3 -c "import time; print(int(time.time()*1000))")
curl -s -m 5 -H "Authorization: Bearer $TOKEN" "$BASE/health" > /dev/null
END=$(python3 -c "import time; print(int(time.time()*1000))")
MS=$((END-START))
echo "  ${MS}ms $([ $MS -le 100 ] && echo '✓ PASS' || echo '✗ FAIL')" | tee -a "$RESULTS"

# 4. skill.search 延迟（目标 ≤100ms）
echo -e "\n--- skill.search 延迟（目标 ≤100ms）---" | tee -a "$RESULTS"
MS=$(rpc "skill.search" '{"query":"打开设置"}')
echo "  ${MS}ms $([ $MS -le 100 ] && echo '✓ PASS' || echo '✗ FAIL')" | tee -a "$RESULTS"

# 5. events 轮询延迟（目标 ≤50ms）
echo -e "\n--- /events 轮询（目标 ≤50ms）---" | tee -a "$RESULTS"
START=$(python3 -c "import time; print(int(time.time()*1000))")
curl -s -m 5 -H "Authorization: Bearer $TOKEN" "$BASE/events?since=0" > /dev/null
END=$(python3 -c "import time; print(int(time.time()*1000))")
MS=$((END-START))
echo "  ${MS}ms $([ $MS -le 50 ] && echo '✓ PASS' || echo '✗ FAIL')" | tee -a "$RESULTS"

# 6. 并发 RPC（8 并发，目标无死锁 <35s）
echo -e "\n--- 8 并发 perceive（无死锁）---" | tee -a "$RESULTS"
bash scripts/rpc-concurrency-test.sh 8 2>&1 | tail -1 | tee -a "$RESULTS"

echo -e "\n== 完成，结果已存 $RESULTS ==" | tee -a "$RESULTS"
