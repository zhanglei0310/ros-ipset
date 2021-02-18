package cn.foperate.ros.munity

import io.smallrye.mutiny.Uni
import io.vertx.kotlin.core.net.netClientOptionsOf
import io.vertx.mutiny.core.Vertx
import io.vertx.mutiny.core.net.NetSocket

class AsyncSocketFactory(val vertx:Vertx, val timeout: Int = 3000) {
   fun createSocket(host: String, port: Int): Uni<NetSocket> {
       val client = vertx.createNetClient(netClientOptionsOf(
           connectTimeout = timeout
       ))
       return client.connect(port, host)
    }
}