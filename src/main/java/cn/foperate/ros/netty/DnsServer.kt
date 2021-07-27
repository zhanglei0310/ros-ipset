package cn.foperate.ros.netty

import io.netty.channel.ChannelFuture
import io.netty.handler.codec.dns.DatagramDnsQuery
import io.netty.handler.codec.dns.DatagramDnsResponse
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.net.SocketAddress

interface DnsServer {
    fun listen(port: Int, address: String): Future<DnsServer>
    fun close(): Future<Void>
    //fun writeAndFlush(response: DatagramDnsResponse): ChannelFuture
    fun exceptionHandler(handler: Handler<Throwable>): DnsServer
    fun endHandler(handler: Handler<Void>): DnsServer
    fun handler(handler: Handler<DatagramDnsQuery>): DnsServer
    fun listen(port: Int, address: String, handler: Handler<AsyncResult<DnsServer>>): DnsServer
    fun close(handler: Handler<AsyncResult<Void>>)
    fun localAddress(): SocketAddress
    fun send(response: DatagramDnsResponse): Future<Void>
    fun send(response: DatagramDnsResponse, handler: Handler<AsyncResult<Void>>?): DnsServer
}