package cn.foperate.ros.pac

import org.junit.Assert
import org.junit.Test

class DomainUtilTest: Assert() {
    @Test
    fun testParse() {
        val n = DomainUtil.parse("www.baidu.com")
        assertEquals(n.size, 3)
    }

    @Test
    fun testMatch() {
        assertTrue(DomainUtil.match("www.google.com"))
        assertTrue(DomainUtil.match("dev.apple.com"))
        assertFalse(DomainUtil.match("www.apple.com"))
    }

    @Test
    fun testTimeout() {
        assertEquals(DomainUtil.getTimeout("alskdjflsf"), 24*3600)
        assertEquals(DomainUtil.getTimeout("36s"), 36)
        assertEquals(DomainUtil.getTimeout("5m11s"), 311)
    }
}