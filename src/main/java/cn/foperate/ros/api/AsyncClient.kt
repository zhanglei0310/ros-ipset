package cn.foperate.ros.api

import cn.foperate.ros.munity.AsyncSocketFactory
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.mutiny.core.Vertx
import me.legrange.mikrotik.impl.AsyncApiConnection

object AsyncClient
{
    private val log = LoggerFactory.getLogger(AsyncClient::class.java)
    @JvmStatic
    fun main(args:Array<String>) {
        log.debug("异步客户端测试开始")

        val vertx = Vertx.vertx()
        val sf = AsyncSocketFactory(vertx, 3000)
        AsyncApiConnection.connect(sf, "192.168.28.1")
            .subscribe().with { conn ->
                conn.login("jdns","Ocwesw2r3").subscribe().with ({
                    log.info("Login successed")
                    val command = "/ip/firewall/address-list/print where list=PROXY return address,timeout"
                    conn.executeAsMulti(command)
                        .subscribe().with {
                            log.debug(it)
                        }
                }){
                    log.error(it)
                }
            }
    }
}