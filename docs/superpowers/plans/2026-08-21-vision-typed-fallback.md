# Configurable Vision Typed-Fallback Plan

**Goal:** Complete the configurable visual-model migration without presenting provider, parsing, or coordinate failures as successful visual perception.

**Architecture:** Body remains the screenshot/privacy authority and attaches actual screen and encoded-image dimensions to `ScreenResult`. Brain's VLM adapter returns a discriminated result: `success`, `provider_unavailable`, `invalid_output`, or `invalid_coordinates`. Only `success` may preserve `kind=vision`. Every failure performs a fresh `perceive.screen(mode=a11y)` fallback and returns `kind=a11y` with a typed, non-sensitive fallback code. Coordinate scaling uses independent X/Y ratios derived from actual dimensions; no device resolution or model name appears in business logic.

## Task 1: Extend optional perception metadata

- [x] Add optional screen/screenshot dimensions and typed `visionFallback` to the shared contract, Kotlin protocol, and TypeScript mirror.
- [x] Have `ScreenshotService` retain real display dimensions and expose capture metadata without changing the privacy gate.
- [x] Attach metadata only to successfully captured `ScreenResult` values.
- [x] Run contract mirror RED/GREEN.

## Task 2: Make VLM results typed and bounded

- [x] Extract a pure parser accepting raw output and screenshot dimensions.
- [x] Behavior RED for malformed JSON, non-array output, NaN/infinite/negative/out-of-image coordinates.
- [x] Return discriminated results; valid empty array remains a successful no-elements result.
- [x] Catch provider errors inside the adapter and return `provider_unavailable` without exposing provider text.

## Task 3: Enforce truthful fallback in the tool path

- [x] On typed VLM failure, request fresh a11y and return it with `visionFallback`; remove screenshot reference from model-facing fallback.
- [x] On success, scale coordinates with actual X/Y ratios and validate final screen bounds.
- [x] Add tool-chain tests for success, provider failure, malformed output, invalid coordinates, and missing dimensions.
- [x] Preserve C-02: sensitive screenshot refusal happens in Body before any VLM call.

## Task 4: Document configuration and verify

- [x] Add a README configuration table and placeholder-only `qwen3.7-plus` example.
- [x] Update docs/07 with optional metadata and typed fallback contract.
- [x] Update docs/17 diagrams/tables with provider-independent fallback flow.
- [x] Run brain typecheck, contract, smoke, integration, dedicated fallback tests, and body full build.
- [x] Request architecture and safety cross-review before closing the parent migration claim.
