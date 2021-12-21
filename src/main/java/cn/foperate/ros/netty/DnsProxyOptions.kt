package cn.foperate.ros.netty

import io.vertx.core.dns.DnsClientOptions

class DnsProxyOptions(
    var timeToLive: Long = 600L,
    var localPort: Int = 0
): DnsClientOptions()

fun dnsProxyOptionsOf(
    host: String? = null,
    port: Int? = null,
    logActivity: Boolean? = null,
    queryTimeout: Long? = null,
    recursionDesired: Boolean? = null,
    timeToLive: Long = 600L,
    localPort: Int = 0): DnsProxyOptions
{
    val options = DnsProxyOptions(timeToLive = timeToLive, localPort = localPort)
    if (host!=null) options.host = host
    if (port!=null) options.port = port
    if (logActivity!=null) options.logActivity = logActivity
    if (queryTimeout!=null) options.queryTimeout = queryTimeout
    if (recursionDesired!=null) options.isRecursionDesired = recursionDesired
    return options
}