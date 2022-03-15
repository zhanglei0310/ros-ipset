package cn.foperate.ros.netty

import io.netty.handler.codec.dns.DnsRecord
import io.vertx.core.dns.DnsResponseCode

class DnsError(val code: DnsResponseCode, val record: List<DnsRecord>): RuntimeException("DNS错误：${code.name}")