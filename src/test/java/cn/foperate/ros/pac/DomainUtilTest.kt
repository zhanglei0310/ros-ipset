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
}