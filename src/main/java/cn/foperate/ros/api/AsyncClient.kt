package cn.foperate.ros.api

import cn.foperate.ros.munity.AsyncSocketFactory
import io.vertx.mutiny.core.Vertx
import org.slf4j.LoggerFactory

object AsyncClient
{
    private val log = LoggerFactory.getLogger(AsyncClient::class.java)
    @JvmStatic
    fun main(args:Array<String>) {
        log.debug("异步客户端测试开始")

        val vertx = Vertx.vertx()
        val sf = AsyncSocketFactory(vertx, 3000)
        PooledApiConnection.connect(sf, "192.168.28.1", "jdns","Ocwesw2r3")
            .subscribe().with ({ conn ->
                log.info("Login successed")
                val command = "/ip/firewall/address-list/print where list=PROXY return address,timeout"
                conn.executeAsMulti(command)
                    .subscribe().with {
                        log.debug(it.toString())
                        conn.close()
                    }
                }){
                    log.error(it.toString())
                }
    }
}