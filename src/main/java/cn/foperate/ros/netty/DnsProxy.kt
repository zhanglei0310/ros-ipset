package cn.foperate.ros.netty

import io.netty.handler.codec.dns.DnsQuestion
import io.netty.handler.codec.dns.DnsRawRecord
import io.vertx.core.Future

interface DnsProxy {
    fun proxy(question: DnsQuestion): Future<List<DnsRawRecord>>
    fun connect(): Future<Void>
}