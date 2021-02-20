package org.xbill.DNS

object Util {
    fun cloneRecord1(source: Record):Record {
        val dest = source.cloneRecord()
        dest.setTTL(86400)
        return dest
    }
}