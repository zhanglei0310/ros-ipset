package cn.foperate.ros.api

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.net.NetClientOptions
import io.vertx.core.net.NetSocket

@Deprecated("改用RestAPI实现")
class AsyncSocketFactory(val vertx:Vertx, val options: NetClientOptions) {
   fun createSocket(host: String, port: Int): Future<NetSocket> {
       val client = vertx.createNetClient(options)
       return client.connect(port, host)
    }
}