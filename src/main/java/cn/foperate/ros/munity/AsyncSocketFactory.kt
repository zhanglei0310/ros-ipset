package cn.foperate.ros.munity

import io.smallrye.mutiny.Uni
import io.vertx.core.net.NetClientOptions
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.net.NetSocket

class AsyncSocketFactory(val vertx:Vertx, val options: NetClientOptions) {
   fun createSocket(host: String, port: Int): Uni<NetSocket> {
       val client = vertx.createNetClient(options)
       return client.connect(port, host)
    }
}