import assert from "node:assert/strict"
import { parseVisionOutput, visionResultFromProvider } from "../src/guards/vision.ts"
import { resolveVisionScreen } from "../src/tools/index.ts"
import type { ScreenResult, VisionFallback } from "../src/ipc/types.ts"

let passed = 0
function ok(name: string): void {
  passed++
  console.log(`  ✓ ${name}`)
}

async function main(): Promise<void> {
  assert.deepEqual(parseVisionOutput("not json", 800, 1200), { status: "invalid_output" })
  assert.deepEqual(parseVisionOutput('{"x":1,"y":2}', 800, 1200), { status: "invalid_output" })
  ok("malformed and non-array output are invalid_output")

  for (const raw of [
    '[{"text":"x","x":"NaN","y":2}]',
    '[{"text":"x","x":1e309,"y":2}]',
    '[{"text":"x","x":-1,"y":2}]',
    '[{"text":"x","x":800,"y":2}]',
    '[{"text":"x","x":2,"y":1200}]',
  ]) {
    assert.deepEqual(parseVisionOutput(raw, 800, 1200), { status: "invalid_coordinates" })
  }
  ok("non-finite negative and out-of-image coordinates are invalid_coordinates")

  assert.deepEqual(parseVisionOutput("[]", 800, 1200), { status: "success", marks: [] })
  ok("valid empty array is successful no-elements result")

  const provider = await visionResultFromProvider(
    async () => { throw new Error("secret provider details") },
    800,
    1200,
  )
  assert.deepEqual(provider, { status: "provider_unavailable" })
  assert.equal(JSON.stringify(provider).includes("secret provider details"), false)
  ok("provider rejection is typed without provider text")

  const visual: ScreenResult = {
    signature: "vision-sig",
    kind: "vision",
    blank: false,
    screenshotRef: "shot.jpg",
    screenWidth: 1200,
    screenHeight: 2400,
    screenshotWidth: 600,
    screenshotHeight: 800,
  }
  const fresh: ScreenResult = {
    signature: "fresh-a11y",
    kind: "a11y",
    blank: false,
    marks: [{ index: 0, text: "返回", center: { x: 20, y: 40 } }],
  }

  const success = await resolveVisionScreen(
    visual,
    async () => Buffer.from("image"),
    async () => ({ status: "success", marks: [{ index: 0, text: "按钮", center: { x: 100, y: 300 } }] }),
    async () => fresh,
  )
  assert.equal(success.kind, "vision")
  assert.deepEqual(success.marks?.[0].center, { x: 200, y: 900 })
  ok("success scales independently from actual X and Y dimensions")

  async function expectFreshFallback(
    reason: VisionFallback,
    visionResult: Parameters<typeof resolveVisionScreen>[2],
    screen: ScreenResult = visual,
  ): Promise<void> {
    let freshCalls = 0
    const fallback = await resolveVisionScreen(
      screen,
      async () => Buffer.from("image"),
      visionResult,
      async () => {
        freshCalls++
        return fresh
      },
    )
    assert.equal(freshCalls, 1)
    assert.equal(fallback.kind, "a11y")
    assert.equal(fallback.signature, "fresh-a11y")
    assert.equal(fallback.visionFallback, reason)
    assert.equal(fallback.screenshotRef, undefined)
  }

  await expectFreshFallback(
    "provider_unavailable",
    async () => visionResultFromProvider(async () => { throw new Error("provider secret") }, 600, 800),
  )
  await expectFreshFallback("invalid_output", async () => parseVisionOutput("malformed", 600, 800))
  await expectFreshFallback(
    "invalid_coordinates",
    async () => parseVisionOutput('[{"x":600,"y":10}]', 600, 800),
  )
  ok("typed VLM failures return fresh truthful a11y fallback")

  let missingDimensionsVisionCalls = 0
  await expectFreshFallback(
    "missing_dimensions",
    async () => {
      missingDimensionsVisionCalls++
      return { status: "success", marks: [] }
    },
    { ...visual, screenshotWidth: undefined },
  )
  assert.equal(missingDimensionsVisionCalls, 0)
  ok("missing dimensions fall back before VLM call")

  await expectFreshFallback(
    "invalid_coordinates",
    async () => ({
      status: "success",
      marks: [{ index: 0, text: "edge", center: { x: 599.9, y: 10 } }],
    }),
    { ...visual, screenWidth: 1 },
  )
  ok("scaled coordinates are revalidated against final screen bounds")

  console.log(`\nvision parser: ${passed} passed`)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
