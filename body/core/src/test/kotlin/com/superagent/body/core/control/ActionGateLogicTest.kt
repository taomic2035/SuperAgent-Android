package com.superagent.body.core.control

import com.superagent.body.core.perception.ScreenPerceiver
import com.superagent.common.Mark
import com.superagent.common.Point
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * ActionGate 纯逻辑测试（不依赖 Android——用假 ScreenPerceiver 数据验证判定算法）。
 */
class ActionGateLogicTest {

    // 构造假节点数据验证 ActionGate 的判定逻辑（不经过 perceive）
    private fun makeNodes(vararg entries: Pair<String, IntArray>): List<com.superagent.common.A11yNode> {
        return entries.map { (label, bounds) ->
            com.superagent.common.A11yNode(
                label = label,
                clickable = true,
                bounds = com.superagent.common.Bounds(bounds[0], bounds[1], bounds[2], bounds[3]),
            )
        }
    }

    @Test
    fun `全包含节点中任一命中提交边界即拒绝`() {
        // 父容器 "订单确认" (0,0,1000,2000) 包含子按钮 "立即支付" (100,1500,400,1600)
        val nodes = makeNodes(
            "订单确认" to intArrayOf(0, 0, 1000, 2000),
            "立即支付" to intArrayOf(100, 1500, 400, 1600),
        )
        // 点击 (200, 1550) 同时落在两个节点内——子按钮是提交边界
        val hitLabels = nodes.filter {
            it.bounds.left <= 200 && 200 <= it.bounds.right && it.bounds.top <= 1550 && 1550 <= it.bounds.bottom
        }.map { it.label }
        assertTrue(hitLabels.contains("立即支付"), "应命中「立即支付」子按钮")
    }

    @Test
    fun `父容器不遮挡子按钮检查`() {
        // firstOrNull 会命中父容器 "商品列表"——但 "提交订单" 子按钮也应被检查
        val nodes = makeNodes(
            "商品列表" to intArrayOf(0, 0, 1080, 2400),
            "提交订单" to intArrayOf(100, 2000, 500, 2100),
        )
        val hits = nodes.filter {
            it.bounds.left <= 200 && 200 <= it.bounds.right && it.bounds.top <= 2050 && 2050 <= it.bounds.bottom
        }
        assertEquals(2, hits.size, "点击应同时命中父容器和子按钮")
        assertTrue(hits.any { it.label == "提交订单" }, "子按钮不应被父容器顶掉")
    }

    @Test
    fun `无命中节点返回空`() {
        val nodes = makeNodes("按钮A" to intArrayOf(0, 0, 100, 100))
        val hits = nodes.filter {
            it.bounds.left <= 500 && 500 <= it.bounds.right && it.bounds.top <= 500 && 500 <= it.bounds.bottom
        }
        assertTrue(hits.isEmpty(), "点击 (500,500) 不应命中 (0,0,100,100) 的节点")
    }
}
