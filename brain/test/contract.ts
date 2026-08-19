/**
 * CT-05 契约镜像（brain 侧）：src/ipc/types.ts 全部 interface 的字段名集合
 * 必须与 body/common/src/main/resources/contract.json 一致（含 brainOnly 扩展字段）。
 * body 侧对等测试：ContractMirrorTest.kt。改任一侧字段 → 先改 contract.json。
 */
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import ts from "typescript"

const TYPES_PATH = fileURLToPath(new URL("../src/ipc/types.ts", import.meta.url))
const CONTRACT_PATH = fileURLToPath(new URL("../../body/common/src/main/resources/contract.json", import.meta.url))

interface ContractEntry {
  fields: string[]
  brainOnly?: string[]
  bodyOnly?: boolean
}

const contract = JSON.parse(readFileSync(CONTRACT_PATH, "utf8")).types as Record<string, ContractEntry>

const source = ts.createSourceFile("types.ts", readFileSync(TYPES_PATH, "utf8"), ts.ScriptTarget.ES2022, true)
const interfaces = new Map<string, string[]>()
for (const stmt of source.statements) {
  if (ts.isInterfaceDeclaration(stmt)) {
    interfaces.set(
      stmt.name.text,
      stmt.members.filter(ts.isPropertySignature).map((m) => m.name.getText(source)),
    )
  } else if (ts.isTypeAliasDeclaration(stmt)) {
    // type X = {...} | {...}（如 RpcResponse 联合类型）：收集所有对象字面量分支的属性名并集
    const parts = stmt.type.kind === ts.SyntaxKind.UnionType
      ? (stmt.type as ts.UnionTypeNode).types.filter(ts.isTypeLiteralNode)
      : ts.isTypeLiteralNode(stmt.type) ? [stmt.type] : []
    if (parts.length) {
      interfaces.set(
        stmt.name.text,
        [...new Set(parts.flatMap((p) => p.members.filter(ts.isPropertySignature).map((m) => m.name.getText(source))))],
      )
    }
  }
}

const failures: string[] = []
for (const [name, entry] of Object.entries(contract)) {
  if (entry.bodyOnly) continue // body 独有子结构（brain 内联表达）
  const actual = interfaces.get(name)
  if (!actual) {
    failures.push(`types.ts 缺少接口 ${name}（契约有但 brain 没实现）`)
    continue
  }
  const expected = [...entry.fields, ...(entry.brainOnly ?? [])]
  const missing = expected.filter((f) => !actual.includes(f))
  const extra = actual.filter((f) => !expected.includes(f))
  if (missing.length || extra.length) {
    failures.push(`${name}: 缺 [${missing.join(",")}] 多 [${extra.join(",")}]`)
  }
}
const uncontracted = [...interfaces.keys()].filter((n) => !(n in contract))
if (uncontracted.length) failures.push(`types.ts 未入契约的接口: ${uncontracted.join(",")}`)

if (failures.length) {
  console.error("契约镜像不一致：\n" + failures.map((f) => `  - ${f}`).join("\n"))
  process.exit(1)
}
console.log(`契约镜像一致：${Object.entries(contract).filter(([, e]) => !e.bodyOnly).length} 个共享类型 ↔ body Protocol.kt`)
