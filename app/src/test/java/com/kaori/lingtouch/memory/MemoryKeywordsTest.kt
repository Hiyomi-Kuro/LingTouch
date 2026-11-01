package com.kaori.lingtouch

import com.kaori.lingtouch.memory.MemoryRepository
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryKeywordsTest {

    @Test
    fun `chinese goals produce bigrams for recall`() {
        val keys = MemoryRepository.keywords("打开设置并找到蓝牙")
        assertTrue(keys.contains("蓝牙"))
        assertTrue(keys.contains("设置"))
    }

    @Test
    fun `english words are kept`() {
        val keys = MemoryRepository.keywords("open Settings and find bluetooth")
        assertTrue(keys.any { it.equals("settings", true) })
    }

    @Test
    fun `keywords are bounded`() {
        val keys = MemoryRepository.keywords("打开设置找到蓝牙然后返回桌面并打开微信发送消息")
        assertTrue(keys.size <= 10)
    }
}
