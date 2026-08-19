package com.superagent.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * CT-05 契约镜像（body 侧）：Protocol.kt 全部 @Serializable 类型的字段名集合
 * 必须与 src/main/resources/contract.json 一致。brain 侧对等测试 brain/test/contract.ts。
 * 改任一侧字段 → 先改 contract.json（docs/07 契约裁决规则）。
 */
class ContractMirrorTest {

    private val serializers: Map<String, kotlinx.serialization.KSerializer<*>> = mapOf(
        "RpcRequest" to serializer<RpcRequest>(),
        "RpcError" to serializer<RpcError>(),
        "RpcResponse" to serializer<RpcResponse>(),
        "Bounds" to serializer<Bounds>(),
        "Point" to serializer<Point>(),
        "BodyEvent" to serializer<BodyEvent>(),
        "HealthStatus" to serializer<HealthStatus>(),
        "ActionResult" to serializer<ActionResult>(),
        "A11yNode" to serializer<A11yNode>(),
        "Mark" to serializer<Mark>(),
        "ScreenResult" to serializer<ScreenResult>(),
        "AsrResult" to serializer<AsrResult>(),
        "SayResult" to serializer<SayResult>(),
        "VoiceprintEnrollResult" to serializer<VoiceprintEnrollResult>(),
        "VoiceprintIdentifyResult" to serializer<VoiceprintIdentifyResult>(),
        "SensorResult" to serializer<SensorResult>(),
        "HeadsetResult" to serializer<HeadsetResult>(),
        "SkillMeta" to serializer<SkillMeta>(),
        "SkillListResult" to serializer<SkillListResult>(),
        "SkillSearchHit" to serializer<SkillSearchHit>(),
        "SkillSearchResult" to serializer<SkillSearchResult>(),
        "SkillRunResult" to serializer<SkillRunResult>(),
        "SkillLearnResult" to serializer<SkillLearnResult>(),
        "HitlConfirmResult" to serializer<HitlConfirmResult>(),
        "HitlAskResult" to serializer<HitlAskResult>(),
        "HitlHandoffResult" to serializer<HitlHandoffResult>(),
        "TraceStep" to serializer<TraceStep>(),
    )

    @Test
    fun `body 协议类型字段与契约镜像一致`() {
        val raw = javaClass.classLoader!!.getResourceAsStream("contract.json")!!
            .readBytes().decodeToString()
        val types = Json.parseToJsonElement(raw).jsonObject["types"]!!.jsonObject

        for ((name, ser) in serializers) {
            val entry = types[name]?.jsonObject
                ?: error("contract.json 缺少类型 $name（新增协议类型必须入契约）")
            val expected = entry["fields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val actual = (0 until ser.descriptor.elementsCount)
                .map { ser.descriptor.getElementName(it) }.toSet()
            assertEquals(expected, actual, "类型 $name 字段漂移：body Protocol.kt 与 contract.json 不一致（先改契约再改代码）")
        }

        val uncontracted = types.keys - serializers.keys
        assertEquals(emptySet<String>(), uncontracted, "contract.json 含 body 未定义的类型：$uncontracted（契约腐烂，需清理）")
    }
}
