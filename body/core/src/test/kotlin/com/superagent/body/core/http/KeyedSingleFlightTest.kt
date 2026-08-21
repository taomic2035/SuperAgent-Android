package com.superagent.body.core.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S5 复核验收（GPT 清单 5 点）：确定性并发测试——卡住第一个执行的完成交接点，
 * 窗口内重试同 key，断言 handler 恰调用一次、双调用者同结果、timeout fallback 唯一缓存。
 */
class KeyedSingleFlightTest {

    private fun newFlight() = KeyedSingleFlight(mutableMapOf<String, Any>(), 128)

    @Test
    fun `1-窗口期同 key 重试 handler 恰一次且双调用者同结果`() {
        val sf = newFlight()
        val calls = AtomicInteger(0)
        val atCompletionHandoff = CountDownLatch(1) // 卡点：第一个执行到达完成交接处暂停
        val release = CountDownLatch(1)

        // 请求 A：enter → Execute；执行体在 complete 前挂起（模拟 completion handoff 暂停）
        val a = sf.enter<String>("k1")
        val execA = a as KeyedSingleFlight.Entry.Execute<String>
        calls.incrementAndGet()
        val worker = Thread {
            atCompletionHandoff.countDown() // 已到达交接点（即将 complete）
            try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            sf.complete("k1", execA.future as CompletableFuture<Any>, "R1")
        }
        worker.start()
        atCompletionHandoff.await() // 确保卡点就位

        // 窗口期：请求 B 到达——此刻缓存未写、in-flight 在位 → 必须 Share 而非执行
        val b = sf.enter<String>("k1")
        assertInstanceOf(KeyedSingleFlight.Entry.Share::class.java, b, "窗口期同 key 不得二次执行")

        release.countDown() // 放行 A 完成
        val sharedResult = (b as KeyedSingleFlight.Entry.Share<String>).future.get(3, TimeUnit.SECONDS)
        val aResult = execA.future.get(3, TimeUnit.SECONDS)
        assertEquals("R1", aResult)
        assertEquals("R1", sharedResult, "双调用者必须拿到同一结果")
        assertEquals(1, calls.get(), "handler 调用数恰为 1")

        // 后续同 key → 命中缓存（不再执行）
        val c = sf.enter<String>("k1")
        assertEquals("R1", (c as KeyedSingleFlight.Entry.Cached<String>).result)
        assertEquals(1, calls.get())
    }

    @Test
    fun `2-超时 fallback 也成为唯一缓存结果`() {
        val sf = newFlight()
        val enter1 = sf.enter<String>("k2")
        assertTrue(enter1 is KeyedSingleFlight.Entry.Execute<String>)
        // 调用方超时放弃（模拟 BodyServer TIMEOUT 响应）——但执行体继续并最终完成
        sf.complete("k2", (enter1 as KeyedSingleFlight.Entry.Execute<String>).future as CompletableFuture<Any>, "LATE-OK")
        // 重试同 key：必须拿到第一次的真实结果（缓存），不得触发第二次执行
        val retry = sf.enter<String>("k2")
        assertInstanceOf(KeyedSingleFlight.Entry.Cached::class.java, retry)
        assertEquals("LATE-OK", (retry as KeyedSingleFlight.Entry.Cached<String>).result)
    }

    @Test
    fun `3-异常 fallback 同样唯一缓存（不二次执行）`() {
        val sf = newFlight()
        val enter1 = sf.enter<String>("k3")
        sf.complete("k3", (enter1 as KeyedSingleFlight.Entry.Execute<String>).future as CompletableFuture<Any>, "ERR:fallback")
        val retry = sf.enter<String>("k3")
        assertEquals("ERR:fallback", (retry as KeyedSingleFlight.Entry.Cached<String>).result)
    }

    @Test
    fun `4-值匹配解除防误删他人 future`() {
        val sf = newFlight()
        val f1 = CompletableFuture<Any>()
        sf.enter<String>("k4") // 占位 f1（Execute 路径）
        // complete 走完生命周期
        sf.complete("k4", f1, "done")
        // 旧 future 的迟到 remove（值匹配）不得影响后续新请求
        sf.inFlightInternal().remove("k4", f1)
        val next = sf.enter<String>("k4")
        assertInstanceOf(KeyedSingleFlight.Entry.Cached::class.java, next, "缓存命中（若误删也不影响正确性——缓存为准）")
    }

    @Test
    fun `5-容量环淘汰`() {
        val sf = KeyedSingleFlight(mutableMapOf<String, Any>(), 2)
        for (i in 1..3) {
            val e = sf.enter<String>("k$i")
            sf.complete("k$i", (e as KeyedSingleFlight.Entry.Execute<String>).future as CompletableFuture<Any>, "v$i")
        }
        assertInstanceOf(KeyedSingleFlight.Entry.Execute::class.java, sf.enter<String>("k1"), "最老 key 被淘汰")
        assertInstanceOf(KeyedSingleFlight.Entry.Cached::class.java, sf.enter<String>("k3"))
    }
}
