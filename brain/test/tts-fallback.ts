/**
 * TTS fallback 链验证（模拟 say() 的异常处理路径——catch Throwable 全覆盖）。
 */
import assert from "node:assert/strict"

function simulateSay(ttsFn: () => unknown): string {
    let t: unknown
    try {
        t = ttsFn()
    } catch {
        t = null  // DS-016：catch Throwable（含 Error 族）→ null → systemTts
    }
    if (t === null || t === undefined) return "systemTts"
    return "sherpa"
}

console.log("== TTS fallback 链验证 ==")
assert.equal(simulateSay(() => ({ sampleRate: 16000 })), "sherpa")
console.log("  ✓ 正常返回 → sherpa")

assert.equal(simulateSay(() => null), "systemTts")
console.log("  ✓ null → systemTts")

assert.equal(simulateSay(() => { throw new Error("SpeechUnavailable") }), "systemTts")
console.log("  ✓ SpeechUnavailable → systemTts")

assert.equal(simulateSay(() => { throw new Error("NoSuchMethodError") }), "systemTts")
console.log("  ✓ NoSuchMethodError → systemTts")

assert.equal(simulateSay(() => { throw new Error("UnsatisfiedLinkError") }), "systemTts")
console.log("  ✓ UnsatisfiedLinkError → systemTts")

assert.equal(simulateSay(() => { throw new TypeError("invoke mismatch") }), "systemTts")
console.log("  ✓ TypeError → systemTts")

assert.equal(simulateSay(() => { throw new RangeError("timeout") }), "systemTts")
console.log("  ✓ RangeError → systemTts")

assert.equal(simulateSay(() => { throw "string error" }), "systemTts")
console.log("  ✓ string throw → systemTts")

console.log("\n8/8 通过——任何异常类型都走 systemTts 兜底 ✓")
