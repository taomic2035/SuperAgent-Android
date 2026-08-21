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
  optional?: string[]
  enums?: Record<string, string[]>
}

const contract = JSON.parse(readFileSync(CONTRACT_PATH, "utf8")).types as Record<string, ContractEntry>

assert.deepEqual(contract.ScreenResult.fields, [
  "signature",
  "kind",
  "blank",
  "nodes",
  "marks",
  "pageTexts",
  "appPackage",
  "sensitiveSession",
  "screenshotRef",
  "screenWidth",
  "screenHeight",
  "screenshotWidth",
  "screenshotHeight",
  "visionFallback",
  "visionActionToken",
])
assert.deepEqual(contract.ScreenResult.optional, [
  "nodes",
  "marks",
  "pageTexts",
  "appPackage",
  "sensitiveSession",
  "screenshotRef",
  "screenWidth",
  "screenHeight",
  "screenshotWidth",
  "screenshotHeight",
  "visionFallback",
  "visionActionToken",
])

const source = ts.createSourceFile("types.ts", readFileSync(TYPES_PATH, "utf8"), ts.ScriptTarget.ES2022, true)
interface InterfaceShape {
  fields: string[]
  optional: string[]
  propertyTypes: Map<string, ts.TypeNode | undefined>
}

const interfaces = new Map<string, InterfaceShape>()
const typeAliases = new Map<string, ts.TypeNode>()
for (const stmt of source.statements) {
  if (ts.isInterfaceDeclaration(stmt)) {
    const properties = stmt.members.filter(ts.isPropertySignature)
    interfaces.set(stmt.name.text, {
      fields: properties.map((m) => m.name.getText(source)),
      optional: properties.filter((m) => m.questionToken !== undefined).map((m) => m.name.getText(source)),
      propertyTypes: new Map(properties.map((m) => [m.name.getText(source), m.type])),
    })
  } else if (ts.isTypeAliasDeclaration(stmt)) {
    typeAliases.set(stmt.name.text, stmt.type)
    // type X = {...} | {...}（如 RpcResponse 联合类型）：收集所有对象字面量分支的属性名并集
    const parts = stmt.type.kind === ts.SyntaxKind.UnionType
      ? (stmt.type as ts.UnionTypeNode).types.filter(ts.isTypeLiteralNode)
      : ts.isTypeLiteralNode(stmt.type) ? [stmt.type] : []
    if (parts.length) {
      const properties = parts.flatMap((p) => p.members.filter(ts.isPropertySignature))
      const namesByPart = parts.map((p) => new Set(
        p.members.filter(ts.isPropertySignature).map((m) => m.name.getText(source)),
      ))
      const allNames = [...new Set(properties.map((m) => m.name.getText(source)))]
      interfaces.set(stmt.name.text, {
        fields: allNames,
        optional: allNames.filter((name) =>
          namesByPart.some((names) => !names.has(name)) ||
          properties.some((m) => m.name.getText(source) === name && m.questionToken !== undefined),
        ),
        propertyTypes: new Map(properties.map((m) => [m.name.getText(source), m.type])),
      })
    }
  }
}

function literalUnionValues(node: ts.TypeNode | undefined): string[] {
  if (node && ts.isTypeReferenceNode(node) && ts.isIdentifier(node.typeName)) {
    node = typeAliases.get(node.typeName.text)
  }
  if (!node || !ts.isUnionTypeNode(node)) return []
  return node.types
    .filter(ts.isLiteralTypeNode)
    .map((literal) => literal.literal)
    .filter(ts.isStringLiteral)
    .map((literal) => literal.text)
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
  const missing = expected.filter((f) => !actual.fields.includes(f))
  const extra = actual.fields.filter((f) => !expected.includes(f))
  if (missing.length || extra.length) {
    failures.push(`${name}: 缺 [${missing.join(",")}] 多 [${extra.join(",")}]`)
  }
  const expectedOptional = entry.optional ?? []
  const missingOptional = expectedOptional.filter((f) => !actual.optional.includes(f))
  const extraOptional = actual.optional.filter((f) => !expectedOptional.includes(f))
  if (missingOptional.length || extraOptional.length) {
    failures.push(`${name}: optional 缺 [${missingOptional.join(",")}] 多 [${extraOptional.join(",")}]`)
  }
  for (const [field, values] of Object.entries(entry.enums ?? {})) {
    const actualValues = literalUnionValues(actual.propertyTypes.get(field))
    const missingValues = values.filter((value) => !actualValues.includes(value))
    const extraValues = actualValues.filter((value) => !values.includes(value))
    if (missingValues.length || extraValues.length) {
      failures.push(`${name}.${field}: 枚举缺 [${missingValues.join(",")}] 多 [${extraValues.join(",")}]`)
    }
  }
}
const uncontracted = [...interfaces.keys()].filter((n) => !(n in contract))
if (uncontracted.length) failures.push(`types.ts 未入契约的接口: ${uncontracted.join(",")}`)

if (failures.length) {
  console.error("契约镜像不一致：\n" + failures.map((f) => `  - ${f}`).join("\n"))
  process.exit(1)
}
console.log(`契约镜像一致：${Object.entries(contract).filter(([, e]) => !e.bodyOnly).length} 个共享类型 ↔ body Protocol.kt`)
