package cn.foperate.ros.netty

import io.netty.handler.codec.dns.DatagramDnsQuery
import io.netty.handler.codec.dns.DatagramDnsResponse
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.impl.VertxInternal
import io.vertx.core.net.SocketAddress

interface DnsServer {
    // 服务启动监听
    fun listen(port: Int, address: String): Future<DnsServer>
    fun listen(port: Int, address: String, handler: Handler<AsyncResult<DnsServer>>): DnsServer

    // 服务结束
    fun close(): Future<Void>
    fun close(handler: Handler<AsyncResult<Void>>)

    // 一组处理模块
    fun handler(handler: Handler<DatagramDnsQuery>): DnsServer
    fun exceptionHandler(handler: Handler<Throwable>): DnsServer
    fun endHandler(handler: Handler<Void>): DnsServer

    // 发出结果数据
    fun send(response: DatagramDnsResponse): Future<Void>
    fun send(response: DatagramDnsResponse, handler: Handler<AsyncResult<Void>>?): DnsServer

    // 获取本地地址的工具函数
    fun localAddress(): SocketAddress

    companion object {
        fun create(vertx: VertxInternal, options: DnsServerOptions): DnsServer {
            val socket = DnsServerImpl(vertx, options)
            // Make sure object is fully initiliased to avoid race with async registration
            socket.initial()
            return socket
        }
    }
}