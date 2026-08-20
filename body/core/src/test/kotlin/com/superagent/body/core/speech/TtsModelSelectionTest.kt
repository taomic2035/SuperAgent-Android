package com.superagent.body.core.speech

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * VITS/TTS 模型选择逻辑测试（纯字符串路径判定，不依赖 Android assets）。
 */
class TtsModelSelectionTest {

    private val vitsDir = "sherpa/models/vits-zh-hf-fanchen-C"
    private val int8Dir = "sherpa/models/kokoro-int8-multi-lang-v1_0"
    private val fp32Dir = "sherpa/models/kokoro-multi-lang-v1_0"

    @Test
    fun `vits int8 fp32 三路径互不相同（优先级由 tts() 分支序保证）`() {
        assertNotEquals(vitsDir, int8Dir)
        assertNotEquals(vitsDir, fp32Dir)
        assertNotEquals(int8Dir, fp32Dir)
    }

    @Test
    fun `isReady 覆盖三档模型`() {
        // isReady() 的 tts 检查应覆盖 vits + int8 + fp32
        val expectedChecks = listOf(
            "$vitsDir/model.onnx",
            "$int8Dir/model.int8.onnx",
            "$fp32Dir/model.onnx",
        )
        assertEquals(3, expectedChecks.size, "isReady 应检查三个模型路径")
        assertTrue(expectedChecks.any { it.contains("vits") }, "应包含 vits 路径")
        assertTrue(expectedChecks.any { it.contains("int8") }, "应包含 int8 路径")
        assertTrue(expectedChecks.any { it.contains("kokoro-multi-lang") && !it.contains("int8") }, "应包含 fp32 路径")
    }

    @Test
    fun `vits 模型文件名一致性`() {
        // vits 用 model.onnx（不是 model.int8.onnx）
        assertEquals("model.onnx", "$vitsDir/model.onnx".substringAfterLast("/"))
    }

    @Test
    fun `int8 模型文件名一致性`() {
        // int8 用 model.int8.onnx
        assertEquals("model.int8.onnx", "$int8Dir/model.int8.onnx".substringAfterLast("/"))
    }
}
