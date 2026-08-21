# C15 Documentation Fact-Drift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Calibrate known stale project facts and add a fast CI guard that prevents closed audit gaps or provider-specific architecture claims from returning.

**Architecture:** A standalone TypeScript test reads a fixed list of authoritative Markdown files and reports line-level violations. Documentation is corrected only where current code/tests prove closure; device-only claims remain pending.

**Tech Stack:** TypeScript, Node.js built-ins, Markdown, GitHub Actions.

---

### Task 1: RED documentation drift test

**Files:**
- Create: `brain/test/docs-consistency.ts`
- Modify: `brain/package.json`

- [x] Write a test that scans README/docs05/docs06/docs14/docs17 for the known stale C01/C02/C05/C06/C07/C10/C12/C14 phrases, provider-bound architecture wording, required model configuration fields, and dedicated verification scripts.
- [x] Add `docs-consistency: tsx test/docs-consistency.ts` to `brain/package.json`.
- [x] Run `npm run docs-consistency` and confirm it fails with line-level stale facts from the current docs.

### Task 2: Calibrate the facts

**Files:**
- Modify: `README.md`
- Modify: `docs/05-架构设计与移交基线-v2.md`
- Modify: `docs/06-功能规格清单与追踪矩阵.md`
- Modify: `docs/14-复盘与P1准备.md`

- [x] Replace only stale implementation statements proven false by current code/tests.
- [x] Keep historical/model evidence explicitly labelled and keep device validation pending.
- [x] Run `npm run docs-consistency` and confirm it passes.

### Task 3: CI and verification

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/16-当前架构代码审计-2026-08-21.md`
- Modify: `docs/13-多智能体分工与协作规约.md`

- [x] Add `npm run docs-consistency` to the brain CI job after typecheck.
- [x] Mark C15 as guarded/monitoring and refresh the dynamic assignment table without changing device claims.
- [x] Run docs consistency, typecheck, contract, smoke, integration, resume, and vision tests.
- [x] Request independent spec and quality review, then commit/push with the required Codex trailer and verify remote SHA.
